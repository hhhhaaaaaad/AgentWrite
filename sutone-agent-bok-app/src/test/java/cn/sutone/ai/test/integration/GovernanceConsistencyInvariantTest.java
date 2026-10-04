package cn.sutone.ai.test.integration;

import cn.sutone.ai.domain.agent.service.memory.governance.MemoryGovernanceComputeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「冲突事实」不变量守护测试（真实 MySQL）。
 *
 * <p><b>本测试存在的唯一目的：让一个产品设计决策不要被悄悄改掉。</b></p>
 *
 * <p>背景：{@code MemoryGovernanceComputeService#computeConsistency()} 是
 * <b>结构性不可达的死代码</b>——它要「同一个 (user, subject, predicate) 下两条 ACTIVE 行」，
 * 而唯一索引 {@code uk_user_sp_active} 恰好禁止这种状态存在。两者互斥，
 * 详见该方法的 javadoc。</p>
 *
 * <p>这不是 bug，是<b>产品取舍</b>：冲突事实要么在<b>写入期</b>由唯一索引直接消解
 * （当前选择），要么<b>允许并存</b>再由离线巡检事后检出（computeConsistency 的设计）。
 * 两者不能同时成立。</p>
 *
 * <p><b>这个测试怎么起作用</b>：它断言「同键第二条 ACTIVE 行必被拒绝」。
 * 一旦有人为了启用并存而移除 {@code uk_user_sp_active}，<b>本测试立刻变红</b>——
 * 于是那次改动必须是一次<b>有意识的</b>决定，而不是顺手把索引删掉、连带让
 * 一致性巡检悄悄复活、评测集也失去依据。这也正是本项目反复警惕的
 * 「静默变更」形态：改的人知道，看的人不知道。</p>
 *
 * <p>为什么用裸 SQL 而不是走 {@code seedGuarded}：本测试要断言的是
 * <b>数据库这一层的约束</b>（索引是唯一不可绕过的执法者），
 * 而 {@code seedGuarded} 需要预置 fencing 状态，会把注意力从被守护的东西上引开。</p>
 */
@SpringBootTest(properties = "memory.vector-store=memory")
@EnabledIf("cn.sutone.ai.test.integration.EvalInfra#mysqlAvailable")
@DisplayName("冲突事实不变量（真实 MySQL）：uk_user_sp_active 决定一致性巡检的死活")
class GovernanceConsistencyInvariantTest {

    private static final Long USER = 9_000_000_997L;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private MemoryGovernanceComputeService computeService;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM memory_record WHERE user_id = ?", USER);
    }

    /**
     * 插一条 ACTIVE 记忆。{@code content_hash} 必须在本用户内唯一
     * （另有 {@code uk_user_hash}），故带上 suffix 区分。
     * {@code active_sp_uk} 是生成列，**不能也不该**手动赋值。
     */
    private void insertActive(String suffix, String content, String subject, String predicate, String value) {
        jdbcTemplate.update("""
                INSERT INTO memory_record
                    (user_id, type, content, content_hash, subject, predicate, value, status)
                VALUES (?, 'FACT', ?, ?, ?, ?, ?, 'ACTIVE')
                """, USER, content, "inv-hash-" + suffix, subject, predicate, value);
    }

    private int countByUser() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_record WHERE user_id = ?", Integer.class, USER);
        return c == null ? 0 : c;
    }

    @Test
    @DisplayName("同 (user, subject, predicate) 的第二条 ACTIVE 行被唯一索引拒绝")
    void secondActiveRowWithSameKeyIsRejected() {
        insertActive("a", "事实A", "user", "city", "北京");

        assertThrows(DuplicateKeyException.class,
                () -> insertActive("b", "事实B", "user", "city", "上海"),
                "同键的第二条 ACTIVE 行必须被 uk_user_sp_active 拒绝。"
                        + "若本断言失败，说明索引已被移除——那意味着「写入期消解」被放弃了，"
                        + "请同步复核 computeConsistency 的调用方与评测集，别让它悄悄变活");

        assertEquals(1, countByUser(), "被拒绝的冲突行不得落库");
    }

    @Test
    @DisplayName("唯一键**不含 value**：即便 value 相同，同键第二条 ACTIVE 行同样被拒")
    void keyDoesNotIncludeValue() {
        insertActive("a", "事实A", "user", "city", "北京");

        assertThrows(DuplicateKeyException.class,
                () -> insertActive("b", "事实B", "user", "city", "北京"),
                "uk_user_sp_active 的键是 user_id|subject|predicate，不含 value；"
                        + "所以「值相同」并不能让第二行挤进来——"
                        + "这解释了为什么连 consistencySkipsWhenValuesAgree 构造的状态也是不可达的");

        assertEquals(1, countByUser());
    }

    @Test
    @DisplayName("因此 computeConsistency 恒为空：这就是它「不可达」的运行时证据")
    void computeConsistencyIsEmptyBecauseConflictCannotExist() {
        insertActive("a", "事实A", "user", "city", "北京");
        // 试图制造冲突——写入期就被挡下（这正是「消解」二字的含义）
        assertThrows(DuplicateKeyException.class,
                () -> insertActive("b", "事实B", "user", "city", "上海"));

        assertTrue(computeService.computeConsistency().isEmpty(),
                "当前不变量下 computeConsistency 必然为空。"
                        + "它变非空说明不变量被放开（索引被移除）——此刻设计已变，"
                        + "应把 expired 之外的 consistency 评测 case 也加回去");
    }

    @Test
    @DisplayName("subject/predicate 为 NULL 时生成列取 NULL，不受本索引约束（可多行并存）")
    void nullSubjectOrPredicateEscapesTheIndex() {
        // 生成列定义里带 subject/predicate IS NOT NULL 条件，两者为 NULL 时取 NULL。
        // MySQL 唯一索引允许多个 NULL —— 所以这些行不互相冲突，也不参与一致性巡检
        // （computeConsistency 的 filter 同样要求两者非空）。两处条件必须一致，这条测试盯住它。
        insertActive("a", "无主体事实A", null, null, "北京");
        insertActive("b", "无主体事实B", null, null, "上海");

        assertEquals(2, countByUser(), "subject/predicate 为 NULL 的行应可并存（生成列为 NULL）");
        assertTrue(computeService.computeConsistency().isEmpty(),
                "这些行不满足扫库条件（要求 subject/predicate 非空），不该产出决策");
    }
}
