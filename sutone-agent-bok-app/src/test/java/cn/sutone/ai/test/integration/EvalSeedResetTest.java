package cn.sutone.ai.test.integration;

import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * seed 幂等与 reset 物理删除集成测试（真实 MySQL，内存向量库）。
 *
 * <p>验证 {@code uk_user_hash(user_id, content_hash)} 唯一索引与物理删除的联动：
 * 软删行仍占唯一键，故 reset 必须物理删才能重新 seed 相同内容。</p>
 */
@SpringBootTest(properties = "memory.vector-store=memory")
@EnabledIf("cn.sutone.ai.test.integration.EvalInfra#mysqlAvailable")
@DisplayName("seed/reset 集成测试（真实 MySQL）")
class EvalSeedResetTest {

    private static final Long USER = 9_000_000_998L;

    @Resource
    private MemoryManager memoryManager;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM memory_record WHERE user_id = ?", USER);
    }

    private int countByUser() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_record WHERE user_id = ?", Integer.class, USER);
        return c == null ? 0 : c;
    }

    @Test
    @DisplayName("相同内容连续 seed 两次：首次 inserted，再次 existed 且复用同一 id")
    void seedTwiceIsIdempotent() {
        MemoryManager.AddDirectResult first =
                memoryManager.addDirect(USER, MemoryTypeVO.FACT, "集成测试语料-幂等校验");
        MemoryManager.AddDirectResult second =
                memoryManager.addDirect(USER, MemoryTypeVO.FACT, "集成测试语料-幂等校验");

        assertTrue(first.inserted(), "首次 seed 应为新增");
        assertFalse(second.inserted(), "重复 seed 应为已存在");
        assertEquals(first.id(), second.id(), "应复用同一记忆 id");
        assertEquals(1, countByUser(), "重复 seed 不得产生第二行");
    }

    @Test
    @DisplayName("reset 物理删除该命名空间全部记录")
    void resetPhysicallyDeletesRecords() {
        memoryManager.addDirect(USER, MemoryTypeVO.FACT, "待删除语料A");
        memoryManager.addDirect(USER, MemoryTypeVO.KNOWLEDGE, "待删除语料B");
        assertEquals(2, countByUser());

        int deleted = memoryManager.reset(USER);

        assertEquals(2, deleted);
        assertEquals(0, countByUser(), "reset 后 MySQL 无残留");
    }

    @Test
    @DisplayName("reset 后重新 seed 相同内容成功（唯一键已随物理删除释放）")
    void resetThenReseedSameContentSucceeds() {
        memoryManager.addDirect(USER, MemoryTypeVO.FACT, "循环语料-唯一键释放校验");
        memoryManager.reset(USER);

        MemoryManager.AddDirectResult again =
                memoryManager.addDirect(USER, MemoryTypeVO.FACT, "循环语料-唯一键释放校验");

        assertTrue(again.inserted(), "物理删除释放唯一键后应可重新新增（软删则会被唯一键挡住）");
        assertEquals(1, countByUser());
    }

    @Test
    @DisplayName("reset 幂等：空命名空间重复 reset 不报错且删除 0 行")
    void resetIsIdempotentOnEmptyNamespace() {
        memoryManager.reset(USER);

        int deleted = memoryManager.reset(USER);

        assertEquals(0, deleted);
        assertEquals(0, countByUser());
    }

    @Test
    @DisplayName("reset 只影响自身命名空间，不触碰其他 eval_user_id")
    void resetIsNamespaceScoped() {
        Long other = USER + 1;
        try {
            memoryManager.addDirect(USER, MemoryTypeVO.FACT, "本命名空间语料");
            memoryManager.addDirect(other, MemoryTypeVO.FACT, "邻居命名空间语料");

            memoryManager.reset(USER);

            assertEquals(0, countByUser());
            Integer neighbor = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM memory_record WHERE user_id = ?", Integer.class, other);
            assertEquals(1, neighbor, "reset 不得跨命名空间删除");
        } finally {
            jdbcTemplate.update("DELETE FROM memory_record WHERE user_id = ?", other);
        }
    }
}
