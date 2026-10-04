package cn.sutone.ai.domain.agent.adapter.repository;

import java.util.List;
import java.util.Map;
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
     * 抽取驳回原因常量。
     *
     * <p><b>为什么集中定义而不是散落字面量</b>：驳回明细要按原因上报给评测平台，而
     * 「某个原因计数为 0」与「这个原因根本不存在」在输出里必须能区分——前者是
     * 「跑了很多次都没出现这种情况」，后者是「这个原因压根没实现」。要能区分，
     * 端点就得知道**原因全集**，也就必须有一个集中定义处。顺带也挡住
     * 「把 too_long 拼成 toolong」这类错——它只会让某一类计数静静地少统计，
     * 表面看一切正常。</p>
     */
    String REJECT_TOO_LONG = "too_long";

    /** 抽出的候选类型不在 {@code MemoryTypeVO} 白名单内。 */
    String REJECT_INVALID_TYPE = "invalid_type";

    /**
     * 抽取驳回原因全集。
     *
     * <p><b>新增驳回分支时必须加进来</b>，否则它不会出现在
     * {@link #getExtractionRejectedByReason()} 的返回里，评测平台看这个原因就像
     * 「从未发生过」。</p>
     */
    List<String> REJECT_REASONS = List.of(REJECT_TOO_LONG, REJECT_INVALID_TYPE);

    /**
     * 按原因统计的抽取驳回次数。
     *
     * <p>返回 {@link #REJECT_REASONS} 的**全集**（从未出现过的原因计 0），
     * 理由见该常量的说明。</p>
     *
     * <p><b>计数是进程内累计值，不是 per-run 的。</b> 要得到「某一次 run 期间驳回了多少」，
     * 调用方必须取 run 前后两次的差；直接读会把进程启动以来的全部历史算进去，
     * 包括此前每一次评测和排查。</p>
     */
    Map<String, Long> getExtractionRejectedByReason();

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
     * @param reason 驳回原因，取值必须是 {@link #REJECT_REASONS} 之一
     *               （原文档曾列出 low_confidence / no_evidence，但那两个分支从未实现，
     *               列在这里会让人以为存在对应的统计）
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

    /** 当前派生驳回率（评测/熔断读取用） */
    double getExtractionRejectRate();

    /** 当前向量同步积压数（评测向量就绪屏障读取用） */
    long getVectorSyncPendingCount();
}
