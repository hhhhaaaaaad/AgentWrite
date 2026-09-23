package cn.sutone.ai.domain.agent.service.memory.circuit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记忆注入熔断器（P3，对应计划 §2.6.2 / §2.6.3）。
 *
 * <p>持有 {@link AtomicBoolean} {@code degraded} 作为「注入是否被熔断」的唯一真值源。
 * {@code memory.inject.enabled} 配置<b>仅作启动初始值</b>（{@code degraded} 初始化为 {@code !injectEnabled}），
 * 运行时由本熔断器状态决定，不再依赖静态 {@code @Value} 运行时切换。</p>
 *
 * <p><b>降级分级</b>（状态机语义）：</p>
 * <ol>
 *   <li><b>一级降级</b>：断路器 {@code open} → 调用方（{@code MemoryManager.retrieveContext()}）
 *       直读 {@link #isDegraded()} 返回空上下文，避免把错误 / 陈旧记忆注入误导 LLM；写路径仍保留。</li>
 *   <li><b>二级降级</b>：暂停结构化写入，仅归档原始对话片段（{@code status=OBSERVATION}）。</li>
 *   <li><b>三级</b>：触发人工介入告警。</li>
 * </ol>
 *
 * <p><b>触发信号（可测，方案 b）</b>：Qdrant 写失败率 / {@code vector.sync.pending} 持续积压 /
 * 抽取驳回率 / 检索 P99 延迟，任一超阈值由离线巡检（{@code MemoryGovernanceJob}）调用 {@link #open(String)}。
 * 原「用户纠错率」因检测机制未落地（恒 0）暂不纳入。</p>
 *
 * <p><b>恢复灰度</b>：修复后抽样准确率 &gt; 95%（由离线评测集确认）方 {@link #halfOpen(double)} 通过，
 * 再按 10% → 50% → 100% 灰度放量。</p>
 */
@Slf4j
@Component
public class MemoryCircuitBreaker {

    /** 抽样准确率恢复阈值，> 此值方允许灰度恢复 */
    private static final double RECOVERY_ACCURACY_THRESHOLD = 0.95;

    /** 熔断态唯一真值源：true = 已降级（关闭注入） */
    private final AtomicBoolean degraded;

    /**
     * @param injectEnabled 注入开关配置，仅作启动初始值；{@code false} 时启动即处于降级态
     */
    public MemoryCircuitBreaker(@Value("${memory.inject.enabled:true}") boolean injectEnabled) {
        this.degraded = new AtomicBoolean(!injectEnabled);
        if (!injectEnabled) {
            log.warn("[memory-circuit] 启动即处于降级态（memory.inject.enabled=false）");
        }
    }

    /**
     * 打开断路器（一级降级：关闭注入）。CAS 幂等，重复调用不重复告警。
     *
     * @param reason 触发原因（如 qdrant_upsert_failure_rate / vector_sync_pending / extraction_reject_rate / retrieval_p99）
     */
    public void open(String reason) {
        if (degraded.compareAndSet(false, true)) {
            // 告警：一级降级 → 关闭注入。人工通知由告警链路（Micrometer/Prometheus + ELK）承载。
            log.error("[memory-circuit] 熔断 OPEN，关闭记忆注入。reason={}", reason);
        }
    }

    /**
     * 关闭断路器（恢复注入）。
     *
     * <p>灰度恢复语义下应先经 {@link #halfOpen(double)} 抽样校验，再按比例放量；本方法用于最终全量恢复。</p>
     */
    public void close() {
        if (degraded.compareAndSet(true, false)) {
            log.warn("[memory-circuit] 熔断 CLOSE，恢复记忆注入");
        }
    }

    /**
     * 查询当前是否处于降级态。
     *
     * <p>注入链路唯一判据：{@code if (circuitBreaker.isDegraded()) return "";}。</p>
     *
     * @return true = 已熔断（关闭注入）
     */
    public boolean isDegraded() {
        return degraded.get();
    }

    /**
     * 半开校验（灰度恢复）：抽样准确率 &gt; 0.95 才关闭断路器（恢复注入），否则保持降级态。
     *
     * <p>准确率由离线评测集（{@code MemoryEvaluationTest}）确认；灰度恢复先 10% 流量观察，
     * 无告警再 50% → 100%。</p>
     *
     * @param sampledAccuracy 抽样准确率，取值 [0, 1]
     */
    public void halfOpen(double sampledAccuracy) {
        if (sampledAccuracy > RECOVERY_ACCURACY_THRESHOLD) {
            log.warn("[memory-circuit] 半开校验通过 sampledAccuracy={}，开始灰度恢复", sampledAccuracy);
            close();
        } else {
            // 未达阈值，保持降级态（不放开注入）
            log.warn("[memory-circuit] 半开校验未通过 sampledAccuracy={}（需 > {}），保持降级态",
                    sampledAccuracy, RECOVERY_ACCURACY_THRESHOLD);
        }
    }
}
