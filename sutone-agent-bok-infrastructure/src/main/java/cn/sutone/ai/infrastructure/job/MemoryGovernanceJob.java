package cn.sutone.ai.infrastructure.job;

import cn.sutone.ai.domain.agent.model.valobj.GovernanceDecision;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import cn.sutone.ai.domain.agent.service.memory.circuit.MemoryCircuitBreaker;
import cn.sutone.ai.domain.agent.service.memory.governance.MemoryGovernanceApplyService;
import cn.sutone.ai.domain.agent.service.memory.governance.MemoryGovernanceComputeService;
import cn.sutone.ai.infrastructure.metrics.MemoryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 记忆离线巡检治理任务（P3，对应计划 §2.5 / §2.4 错误分级决策表）。
 *
 * <p><b>可逆性契约</b>：所有治理操作一律「先软标记、后硬化」两段式——先把受影响行置为
 * {@code MERGE_PENDING / ARCHIVED / DISPUTED / QUARANTINED}（保留原始行与向量），
 * 并写 {@code memory_governance_undo} + {@code memory_governance_undo_item} 撤销记录；
 * 人工 / 评测确认后才物理删除或真正合并。</p>
 *
 * <p><b>compute / apply 分离（AW-6）</b>：本类只做调度——「调用
 * {@link MemoryGovernanceComputeService} 算决策 → 交给 {@link MemoryGovernanceApplyService} 落库」。
 * 决策计算是纯只读的，因此评测平台可通过 {@code /eval/governance/replay} 在不改变语料的前提下重放治理判定。</p>
 *
 * <p><b>eval 模式禁用</b>：{@code memory.eval.enabled=true} 时不装配本任务，避免评测期间被后台任务改写语料。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "memory.eval.enabled", havingValue = "false", matchIfMissing = true)
public class MemoryGovernanceJob {

    /** 熔断巡检：向量同步积压告警阈值（条），超过则触发一级降级 */
    private static final long VECTOR_SYNC_PENDING_THRESHOLD = 500;

    @Resource
    private MemoryGovernanceComputeService computeService;

    @Resource
    private MemoryGovernanceApplyService applyService;

    @Resource
    private MemoryCircuitBreaker circuitBreaker;

    @Resource
    private MemoryMetrics memoryMetrics;

    @Resource
    private MemoryProperties memoryProperties;

    /** 重复聚类（每日凌晨 3:30）：compute 判重复簇 → apply 软标记 MERGE_PENDING */
    @Scheduled(cron = "0 30 3 * * *")
    public void clusterDuplicates() {
        long begin = System.currentTimeMillis();
        log.info("[governance:cluster-duplicates] 开始每日重复聚类巡检");
        try {
            List<GovernanceDecision> decisions = computeService.computeDuplicates();
            int marked = applyService.apply(decisions);
            log.info("[governance:cluster-duplicates] 完成，软标记 MERGE_PENDING {} 条（{} 簇），耗时 {}ms",
                    marked, decisions.size(), System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.error("[governance:cluster-duplicates] 失败", e);
        }
    }

    /** 事实一致性巡检（每周一 4:00）：compute 找同 predicate 异 value → apply 软标记 DISPUTED */
    @Scheduled(cron = "0 0 4 ? * MON")
    public void checkFactConsistency() {
        long begin = System.currentTimeMillis();
        log.info("[governance:fact-consistency] 开始每周事实一致性巡检");
        try {
            List<GovernanceDecision> decisions = computeService.computeConsistency();
            int marked = applyService.apply(decisions);
            log.info("[governance:fact-consistency] 完成，软标记 DISPUTED {} 条（{} 组），耗时 {}ms",
                    marked, decisions.size(), System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.error("[governance:fact-consistency] 失败", e);
        }
    }

    /** 过期清理（每日 5:00）：compute 扫过期待归档 → apply 软标记 ARCHIVED */
    @Scheduled(cron = "0 0 5 * * *")
    public void cleanExpired() {
        long begin = System.currentTimeMillis();
        log.info("[governance:clean-expired] 开始每日过期清理");
        try {
            List<GovernanceDecision> decisions = computeService.computeExpired();
            int marked = applyService.apply(decisions);
            log.info("[governance:clean-expired] 完成，软归档 ARCHIVED {} 条，耗时 {}ms",
                    marked, System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.error("[governance:clean-expired] 失败", e);
        }
    }

    /** 幻觉抽检（每周日 6:00）：compute 回溯 evidence 校验 → apply 软隔离 QUARANTINED */
    @Scheduled(cron = "0 0 6 ? * SUN")
    public void spotCheckHallucination() {
        long begin = System.currentTimeMillis();
        log.info("[governance:hallucination-spot-check] 开始每周幻觉抽检");
        try {
            List<GovernanceDecision> decisions = computeService.computeHallucination();
            int marked = applyService.apply(decisions);
            log.info("[governance:hallucination-spot-check] 完成，软隔离疑似幻觉 {} 条，耗时 {}ms",
                    marked, System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.error("[governance:hallucination-spot-check] 失败", e);
        }
    }

    /**
     * 纠错熔断巡检（每分钟）。
     *
     * <p>统计可测信号——抽取驳回率（{@code memory.extraction.reject_rate}）、
     * {@code memory.vector.sync.pending} 积压，任一超阈值则调用
     * {@link MemoryCircuitBreaker#open(String)} 触发一级降级（关闭注入）。</p>
     *
     * <p>注意：{@code memory.user.correction} 在纠错信号检测机制落地前恒 0，不参与熔断。</p>
     */
    @Scheduled(cron = "0 * * * * *")
    public void inspectCorrectionCircuit() {
        try {
            MemoryProperties.Alert alert = memoryProperties.getAlert();
            if (alert == null || !alert.isEnabled()) {
                return; // 首月不启用熔断（先埋点后调阈值）
            }
            // 信号 1：抽取驳回率持续过高 → 抽取规则异常/幻觉增多
            double rejectRate = memoryMetrics.getExtractionRejectRate();
            if (rejectRate > alert.getExtractionRejectRate()) {
                circuitBreaker.open("抽取驳回率 " + rejectRate + " 超过阈值 " + alert.getExtractionRejectRate());
                return;
            }
            // 信号 2：向量同步积压 → 补偿任务失效 / Qdrant 故障
            long pending = memoryMetrics.getVectorSyncPendingCount();
            if (pending > VECTOR_SYNC_PENDING_THRESHOLD) {
                circuitBreaker.open("向量同步积压 " + pending + " 超过阈值 " + VECTOR_SYNC_PENDING_THRESHOLD);
            }
        } catch (Exception e) {
            log.error("[governance:correction-circuit] 熔断巡检失败", e);
        }
    }
}
