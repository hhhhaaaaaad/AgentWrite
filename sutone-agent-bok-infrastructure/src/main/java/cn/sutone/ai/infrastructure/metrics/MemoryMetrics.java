package cn.sutone.ai.infrastructure.metrics;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 记忆系统可观测性指标（P0-8 建立，P3-1 扩展并实现 {@link IMemoryMetricsPort}）。
 *
 * <p>命名规范 {@code memory.<域>.<指标>}，实现风格仿 {@link MqMetrics}（构造注入 MeterRegistry）。</p>
 *
 * <p><b>首月观测面（先埋点后调阈值）</b>：仅告警四个核心指标——
 * {@code memory.qdrant.failures} / {@code memory.vector.sync.pending} /
 * {@code memory.extraction.accepted} / {@code memory.extraction.rejected}。
 * 其余指标（重复率、冲突率、过期召回率等）仅埋点记录，待基线收集后按豆包阈值启用。
 * 本类仅负责「记录 + 派生率 Gauge」，不接真实通知渠道（告警后续由 Prometheus/Grafana + ELK 承载）。</p>
 */
@Component
public class MemoryMetrics implements IMemoryMetricsPort {

    private final MeterRegistry meterRegistry;

    /**
     * 用于在没有补偿任务时查真实的向量积压数（见 {@link #getVectorSyncPendingCount()}）。
     *
     * <p>构造注入而非 {@code @Resource} 字段注入：这个依赖是**必需**的，
     * 用构造参数能让「忘了注入」在编译期就暴露，而不是在运行时变成一个恒为 0 的假指标——
     * 本方法此前正是因为「拿不到真实数据就返回 0」而成了事故的一部分，
     * 不该再留一个同样形态的隐患。</p>
     */
    private final IMemoryRepository memoryRepository;

    /**
     * 向量补偿积压供应器。持有强引用，避免 Micrometer Gauge 对 state 对象弱引用导致 Supplier 被 GC。
     */
    private volatile Supplier<? extends Number> vectorSyncPendingSupplier;

    /** 抽取通过/驳回累计数（用于 @Scheduled 派生驳回率 Gauge） */
    private final AtomicLong extractionAcceptedTotal = new AtomicLong();
    private final AtomicLong extractionRejectedTotal = new AtomicLong();

    /** 派生驳回率（Gauge 实时值，由 {@link #refreshDerivedRates()} 周期刷新） */
    private volatile double extractionRejectRate = 0.0;

    public MemoryMetrics(MeterRegistry meterRegistry, IMemoryRepository memoryRepository) {
        this.meterRegistry = meterRegistry;
        this.memoryRepository = memoryRepository;

        // 派生率 Gauge：驳回率 = rejected / (accepted + rejected)，首月仅记录不告警
        Gauge.builder("memory.extraction.reject_rate", this, m -> m.extractionRejectRate)
                .description("Derived extraction reject rate (rejected / (accepted + rejected))")
                .register(meterRegistry);
    }

    @Override
    public void incrementQdrantFailure(String op) {
        Counter.builder("memory.qdrant.failures")
                .description("Qdrant operation failures")
                .tag("op", op)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void registerVectorSyncPendingGauge(Supplier<? extends Number> pendingSupplier) {
        this.vectorSyncPendingSupplier = pendingSupplier;
        Gauge.builder("memory.vector.sync.pending", this, m -> {
            Supplier<? extends Number> supplier = m.vectorSyncPendingSupplier;
            return supplier == null ? 0d : supplier.get().doubleValue();
        }).description("Number of memory records pending vector sync")
                .register(meterRegistry);
    }

    @Override
    public void incrementExtractionAccepted(String type) {
        Counter.builder("memory.extraction.accepted")
                .description("Accepted memory extraction candidates")
                .tag("type", type)
                .register(meterRegistry)
                .increment();
        extractionAcceptedTotal.incrementAndGet();
    }

    @Override
    public void incrementExtractionRejected(String reason) {
        Counter.builder("memory.extraction.rejected")
                .description("Rejected memory extraction candidates")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
        extractionRejectedTotal.incrementAndGet();
    }

    @Override
    public void incrementVectorSyncSuccess() {
        Counter.builder("memory.vector.sync.success")
                .description("Successful vector sync operations")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementVectorSyncFailed() {
        Counter.builder("memory.vector.sync.failed")
                .description("Vector sync operations exhausted retries (FAILED)")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementRetrievalRecalled(String channel, long count) {
        Counter.builder("memory.retrieval.recalled")
                .description("Recalled memories per channel")
                .tag("channel", channel)
                .register(meterRegistry)
                .increment(count);
    }

    @Override
    public void incrementRetrievalFiltered(String reason) {
        Counter.builder("memory.retrieval.filtered")
                .description("Filtered recall results")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordRetrievalDuration(long millis) {
        Timer.builder("memory.retrieval.duration")
                .description("End-to-end retrieval latency")
                .register(meterRegistry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordPipelineDuration(long millis) {
        Timer.builder("memory.pipeline.duration")
                .description("End-to-end extraction and storage pipeline latency")
                .register(meterRegistry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordRerankDuration(long millis) {
        Timer.builder("memory.rerank.duration")
                .description("Rerank latency")
                .register(meterRegistry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void incrementWriteInsert(String type) {
        Counter.builder("memory.write.insert")
                .description("Inserted memory records")
                .tag("type", type)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementWriteUpdate(String type) {
        Counter.builder("memory.write.update")
                .description("Updated (versioned) memory records")
                .tag("type", type)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementWriteDuplicate() {
        Counter.builder("memory.write.duplicate")
                .description("Hash-dedup skipped duplicate memories")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementWriteConflict() {
        Counter.builder("memory.write.conflict")
                .description("Semantic conflict / disputed memories")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 周期刷新派生率 Gauge（驳回率 = rejected / (accepted + rejected)）。
     *
     * <p>评估间隔可配 {@code memory.alert.eval-interval-ms}，默认 60s。首月仅计算并暴露，
     * 不触发告警（告警规则见 {@code MemoryProperties.Alert}，后续由 Prometheus/Grafana 消费）。</p>
     */
    @Scheduled(fixedDelayString = "${memory.alert.eval-interval-ms:60000}")
    void refreshDerivedRates() {
        long accepted = extractionAcceptedTotal.get();
        long rejected = extractionRejectedTotal.get();
        long total = accepted + rejected;
        this.extractionRejectRate = total == 0 ? 0.0 : (double) rejected / total;
    }

    /** 当前派生驳回率（熔断巡检读取用） */
    @Override
    public double getExtractionRejectRate() {
        return extractionRejectRate;
    }

    /**
     * 当前向量同步积压数。
     *
     * <p><b>没有 Supplier 时回退到查库，而不是返回 0。</b>此前是返回 0 的，注释写的是
     * 「未注册 Supplier 时返回 0」——但那把「<b>无人告诉我</b>」和「<b>没有积压</b>」
     * 当成了同一件事，而这两者的后果天差地别。</p>
     *
     * <p>具体事故链（实测复现过）：评测 profile 下 {@code MemoryVectorSyncJob} 被
     * {@code @ConditionalOnProperty} 关掉（它只在 {@code memory.eval.enabled=false} 时存在），
     * 于是没有任何人注册 Supplier → 本方法恒返回 0。而 seed 时若向量化失败，
     * 记忆会被标成 {@code PENDING} 且**永远没有任务来重试**。
     * 结果是真实积压 1 条、指标报 0 条。</p>
     *
     * <p>对评测的破坏是间接但确定的：平台侧 {@code _stage_vector_ready} 的判据就是
     * 「这个数归零」，它于是立刻放行，hnsw 检索在那条记忆上静默漏召回——
     * 表现为 Recall 偏低，看起来像检索算法退步。**那个屏障存在的全部意义就是拦住
     * 这种情况，却因为指标说谎而被绕过去了。**</p>
     */
    @Override
    public long getVectorSyncPendingCount() {
        Supplier<? extends Number> s = vectorSyncPendingSupplier;
        if (s != null) {
            return s.get().longValue();
        }
        // 查库有成本，但它只在「补偿任务未运行」时发生（评测实例），
        // 而那时正需要真实的数字——省这次查询等于报一个假指标。
        return memoryRepository.countVectorSyncPending();
    }
}
