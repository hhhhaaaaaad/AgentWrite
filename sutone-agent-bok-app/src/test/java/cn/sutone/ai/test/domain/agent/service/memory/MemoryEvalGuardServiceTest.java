package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IEvalFencingRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.exception.MemoryEvalFencingException;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.service.memory.MemoryEvalGuardService;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 评测破坏性写守卫服务测试（AW-7 同事务保证）。
 *
 * <p><b>核心不变量</b>：fencing 校验失败时，破坏性写（MySQL 删除/插入）绝不允许发生，
 * 向量也不得被清理——即「校验 + 写」必须原子。</p>
 *
 * <p>用<b>真实的 TransactionTemplate</b>（配 mock 的 PlatformTransactionManager）驱动，
 * 使 {@code txTemplate.execute(...)} 真正执行回调，从而能验证「异常 → 未落库」。</p>
 */
@DisplayName("MemoryEvalGuardService 守卫测试")
@ExtendWith(MockitoExtension.class)
class MemoryEvalGuardServiceTest {

    private static final Long USER = 9_000_000_001L;
    private static final String RUN_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private IEvalFencingRepository fencingRepository;
    @Mock
    private IMemoryRepository memoryRepository;
    @Mock
    private IMemoryVectorStore vectorStore;
    @Mock
    private IMemoryEmbeddingClient embeddingClient;
    @Mock
    private MemoryManager memoryManager;
    @Mock
    private PlatformTransactionManager transactionManager;

    private MemoryEvalGuardService guardService;

    @BeforeEach
    void setUp() throws Exception {
        guardService = new MemoryEvalGuardService();
        setField("fencingRepository", fencingRepository);
        setField("memoryRepository", memoryRepository);
        setField("vectorStore", vectorStore);
        setField("embeddingClient", embeddingClient);
        setField("memoryManager", memoryManager);
        setField("transactionManager", transactionManager);
        // 让真实的 TransactionTemplate 能跑起来
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        // @PostConstruct 不会自动执行，手动触发以构造 txTemplate
        var init = MemoryEvalGuardService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(guardService);
    }

    private void setField(String name, Object value) throws Exception {
        var f = MemoryEvalGuardService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(guardService, value);
    }

    // ==================== resetGuarded ====================

    @Test
    @DisplayName("fencing 校验失败：绝不删除 MySQL、绝不清向量，且事务回滚")
    void resetRejectedWhenFencingFails() {
        doThrow(new MemoryEvalFencingException("run_id 不匹配"))
                .when(fencingRepository).lockAndValidate(USER, RUN_ID, 1L);

        assertThrows(MemoryEvalFencingException.class,
                () -> guardService.resetGuarded(USER, RUN_ID, 1L));

        verify(memoryRepository, never()).deleteByUserId(anyLong());
        verify(vectorStore, never()).removeByUserId(anyLong());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("reset 成功：删 MySQL → 提交 → 提交后才清向量")
    void resetSuccessDeletesThenClearsVectorsAfterCommit() {
        when(memoryRepository.deleteByUserId(USER)).thenReturn(7);

        int deleted = guardService.resetGuarded(USER, RUN_ID, 1L);

        assertEquals(7, deleted);
        verify(fencingRepository).lockAndValidate(USER, RUN_ID, 1L);
        verify(memoryRepository).deleteByUserId(USER);
        verify(vectorStore).removeByUserId(USER);
        verify(transactionManager).commit(any());

        // 向量清理必须发生在事务提交之后
        InOrder inOrder = inOrder(transactionManager, vectorStore);
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(vectorStore).removeByUserId(USER);
    }

    @Test
    @DisplayName("fencing 版本不传时按 null 透传（只校验 run_id）")
    void resetPassesNullVersionThrough() {
        when(memoryRepository.deleteByUserId(USER)).thenReturn(0);

        guardService.resetGuarded(USER, RUN_ID, null);

        verify(fencingRepository).lockAndValidate(USER, RUN_ID, null);
    }

    // ==================== seedGuarded ====================

    @Test
    @DisplayName("seed 幂等：已存在内容计入 existed 且不重复插入")
    void seedIsIdempotentForExistingContent() {
        MemoryRecordEntity existing = MemoryRecordEntity.create(
                42L, USER, "fact", "已有内容", "hash-existing", "eval-seed");
        when(memoryRepository.selectByUserIdAndHash(eq(USER), anyString())).thenReturn(existing);

        MemoryEvalGuardService.SeedOutcome outcome = guardService.seedGuarded(USER, RUN_ID, 1L,
                List.of(new MemoryEvalGuardService.SeedItem(MemoryTypeVO.FACT, "已有内容")));

        assertEquals(0, outcome.inserted());
        assertEquals(1, outcome.existed());
        assertEquals(42L, outcome.contentToId().get("已有内容"));
        verify(memoryRepository, never()).insert(any(MemoryRecordEntity.class));
        verify(vectorStore, never()).upsert(anyLong(), anyLong(), any(float[].class), anyString(), anyString());
    }

    @Test
    @DisplayName("seed 新内容：插入并为每条写向量，且向量写入在提交之后")
    void seedInsertsNewContentAndWritesVectorsAfterCommit() {
        when(memoryRepository.selectByUserIdAndHash(eq(USER), anyString())).thenReturn(null);
        when(memoryRepository.insert(any(MemoryRecordEntity.class))).thenReturn(11L);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.2f});

        MemoryEvalGuardService.SeedOutcome outcome = guardService.seedGuarded(USER, RUN_ID, 1L,
                List.of(new MemoryEvalGuardService.SeedItem(MemoryTypeVO.FACT, "新内容")));

        assertEquals(1, outcome.inserted());
        assertEquals(0, outcome.existed());
        assertEquals(11L, outcome.contentToId().get("新内容"));
        verify(memoryRepository).insert(any(MemoryRecordEntity.class));
        verify(vectorStore).upsert(eq(11L), eq(USER), any(float[].class), eq("新内容"), anyString());
        verify(memoryRepository).updateVectorStatus(11L, "SYNCED");

        InOrder inOrder = inOrder(transactionManager, vectorStore);
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(vectorStore).upsert(anyLong(), anyLong(), any(float[].class), anyString(), anyString());
    }

    @Test
    @DisplayName("embedding 不可用时标记 PENDING，不阻断 seed 落库")
    void seedMarksPendingWhenEmbeddingUnavailable() {
        when(memoryRepository.selectByUserIdAndHash(eq(USER), anyString())).thenReturn(null);
        when(memoryRepository.insert(any(MemoryRecordEntity.class))).thenReturn(12L);
        when(embeddingClient.embed(anyString())).thenReturn(new float[0]);

        MemoryEvalGuardService.SeedOutcome outcome = guardService.seedGuarded(USER, RUN_ID, 1L,
                List.of(new MemoryEvalGuardService.SeedItem(MemoryTypeVO.FACT, "无向量内容")));

        assertEquals(1, outcome.inserted());
        verify(vectorStore, never()).upsert(anyLong(), anyLong(), any(float[].class), anyString(), anyString());
        verify(memoryRepository).updateVectorStatus(12L, "PENDING");
    }

    @Test
    @DisplayName("seed fencing 校验失败：绝不插入任何行")
    void seedRejectedWhenFencingFails() {
        doThrow(new MemoryEvalFencingException("无 fencing 行"))
                .when(fencingRepository).lockAndValidate(USER, RUN_ID, 1L);

        assertThrows(MemoryEvalFencingException.class,
                () -> guardService.seedGuarded(USER, RUN_ID, 1L,
                        List.of(new MemoryEvalGuardService.SeedItem(MemoryTypeVO.FACT, "内容"))));

        verify(memoryRepository, never()).insert(any(MemoryRecordEntity.class));
        verify(memoryRepository, never()).selectByUserIdAndHash(anyLong(), anyString());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("seed 空列表：无插入、无向量写入")
    void seedEmptyListIsNoop() {
        MemoryEvalGuardService.SeedOutcome outcome =
                guardService.seedGuarded(USER, RUN_ID, 1L, List.of());

        assertEquals(0, outcome.inserted());
        assertEquals(0, outcome.existed());
        verify(memoryRepository, never()).insert(any(MemoryRecordEntity.class));
        verifyNoInteractions(vectorStore, embeddingClient);
    }
}
