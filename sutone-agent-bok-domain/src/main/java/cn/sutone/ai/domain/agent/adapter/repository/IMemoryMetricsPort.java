package cn.sutone.ai.domain.agent.adapter.repository;

import java.util.function.Supplier;

/**
 * 记忆系统可观测性指标端口（P3-1）。
 *
 * <p><b>架构定位</b>：{@code MemoryMetrics} 落在 infrastructure 层，而
 * {@code MemoryExtractor} / {@code MemoryRetriever} / {@code MemoryManager} /
 * {@code MemoryVectorSyncJob} 落在 domain 层。domain 不能反向依赖 infrastructure
 * （会构成循环依赖），故 domain 只依赖本接口，infra 的 {@code MemoryMetrics} 实现本接口
 * 并注册为 {@code @Component}，由 Spring 按类型注入（标准端口-适配器模式）。</p>
 *
 * <p><b>指标命名</b>：{@code memory.<域>.<指标>}，tag 维度遵循实施计划 §2.2。
 * 首月仅埋点不告警，先收集基线再按阈值启用（「先埋点后调阈值」）。</p>
 */
public interface IMemoryMetricsPort {

    /**
     * 记录一次 Qdrant 操作失败。
     *
     * @param op 操作类型，如 upsert / delete / search
     */
    void incrementQdrantFailure(String op);

    /**
     * 注册「向量同步积压」Gauge，值由 pending 计数 Supplier 实时提供。
     *
     * @param pendingSupplier 返回当前 pending 数量的 Supplier
     */
    void registerVectorSyncPendingGauge(Supplier<? extends Number> pendingSupplier);

    /**
     * 记录一条通过校验的抽取候选。
     *
     * @param type 记忆类型，如 fact / preference / knowledge / event
     */
    void incrementExtractionAccepted(String type);

    /**
     * 记录一条被驳回的抽取候选。
     *
     * @param reason 驳回原因，如 too_long / invalid_type / low_confidence / no_evidence
     */
    void incrementExtractionRejected(String reason);

    /** 记录一次向量同步成功。 */
    void incrementVectorSyncSuccess();

    /** 记录一次向量同步失败（重试超限标记 FAILED 时）。 */
    void incrementVectorSyncFailed();

    /**
     * 记录各召回通道召回数。
     *
     * @param channel 召回通道，如 semantic / lexical / profile
     * @param count   召回条数
     */
    void incrementRetrievalRecalled(String channel, long count);

    /**
     * 记录一条被过滤掉的召回结果。
     *
     * @param reason 过滤原因，如 inactive / expired / low_conf / type_mismatch
     */
    void incrementRetrievalFiltered(String reason);

    /**
     * 记录一次端到端检索耗时。
     *
     * @param millis 耗时（毫秒）
     */
    void recordRetrievalDuration(long millis);

    /**
     * 记录一次端到端抽取存储（pipeline）耗时。
     *
     * @param millis 耗时（毫秒）
     */
    void recordPipelineDuration(long millis);

    /**
     * 记录一次精排（rerank）耗时。
     *
     * @param millis 耗时（毫秒）
     */
    void recordRerankDuration(long millis);

    /**
     * 记录一次新增写入。
     *
     * @param type 记忆类型
     */
    void incrementWriteInsert(String type);

    /**
     * 记录一次版本化更新写入。
     *
     * @param type 记忆类型
     */
    void incrementWriteUpdate(String type);

    /** 记录一次 hash 去重跳过（重复记忆）。 */
    void incrementWriteDuplicate();

    /** 记录一次语义冲突/争议标记。 */
    void incrementWriteConflict();
}
