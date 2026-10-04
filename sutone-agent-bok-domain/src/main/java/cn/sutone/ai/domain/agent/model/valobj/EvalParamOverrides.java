package cn.sutone.ai.domain.agent.model.valobj;

/**
 * 评测参数覆盖：附在 {@code /api/v1/eval/search} 与 {@code /api/v1/eval/retrieve-context}
 * 的请求上，服务端据此替换本次调用的生效参数（{@link RetrieverParams}）。
 *
 * <p><b>字段可空 = 不覆盖</b>，于是「只想试 alpha」不必把其余六个也抄一遍，也就不会
 * 因为抄错而悄悄改变别的维度。全空（{@link #NONE}）等价于「按服务端配置跑」。</p>
 *
 * <p><b>刻意不含 {@code vectorStore}</b>：它选的是 Qdrant 的 collection，属部署期选择，
 * 请求级覆盖没有意义——换个 collection 就是换一套完全不同的数据，那不是调参是换系统。</p>
 *
 * <p><b>刻意不含 reranker 参数（含精排 topN）</b>：评测检索默认 {@code freezeSideEffects=true}，
 * 而 {@code MemoryRetriever.doSearch} 第 7 步是 {@code freeze ? scored : rerankIfNeeded(...)}
 * ——**freeze 会跳过精排**。也就是说请求级传精排 topN 在评测路径上永远不会生效，加进来
 * 只会再造一个「界面上能调、实际上没用」的参数。要让精排成为可评测的变量，得先决定
 * 「评测要不要允许开精排」（代价：每次查询多一次外部 LLM 调用、引入非确定性、破坏可复现），
 * 那是独立的一次决策，不在本类型范围内。</p>
 *
 * @param rrfK            覆盖 RRF 融合常数
 * @param alpha           覆盖 recency 权重
 * @param beta            覆盖 importance 权重
 * @param recencyHalfLifeDays 覆盖半衰期（天）
 * @param profileBoost    覆盖画像 boost
 * @param minConfidence   覆盖置信度下限
 * @param injectMaxTokens 覆盖注入 token 预算
 */
public record EvalParamOverrides(
        Integer rrfK,
        Double alpha,
        Double beta,
        Double recencyHalfLifeDays,
        Double profileBoost,
        Double minConfidence,
        Integer injectMaxTokens) {

    /** 不覆盖任何参数：等价于「按服务端配置跑」。 */
    public static final EvalParamOverrides NONE =
            new EvalParamOverrides(null, null, null, null, null, null, null);

    /** 全字段为空即「不覆盖」。 */
    public boolean isEmpty() {
        return rrfK == null && alpha == null && beta == null && recencyHalfLifeDays == null
                && profileBoost == null && minConfidence == null && injectMaxTokens == null;
    }

    /**
     * 把非空字段盖到 {@code base} 上，得到本次调用真正生效的参数。
     *
     * <p>非空判定逐个字段做，不做整体「有没有传 overrides」的判断——只传 alpha 时其余六个
     * 必须原样来自服务端配置，而不是退化成某个默认值。</p>
     */
    public RetrieverParams resolve(RetrieverParams base) {
        return new RetrieverParams(
                rrfK != null ? rrfK : base.rrfK(),
                alpha != null ? alpha : base.alpha(),
                beta != null ? beta : base.beta(),
                recencyHalfLifeDays != null ? recencyHalfLifeDays : base.recencyHalfLifeDays(),
                profileBoost != null ? profileBoost : base.profileBoost(),
                minConfidence != null ? minConfidence : base.minConfidence(),
                injectMaxTokens != null ? injectMaxTokens : base.injectMaxTokens());
    }
}
