package cn.sutone.ai.domain.agent.model.valobj;

import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;

/**
 * 一次检索 / 注入所用的**生效参数**（不可变）。
 *
 * <p><b>为什么要有这个类型</b>：这些值原先只有一条来源——{@code MemoryProperties} 这个
 * Spring 单例被各方法直接读取。后果是评测平台**改了快照里的参数也叫不动被测系统**：
 * 七个参数里只有 {@code injectMaxTokens} 真的被读到，其余六个只进 {@code params_hash}
 * 与 {@code config_fingerprint}。于是两次 run 会拿到**不同的指纹、完全相同的行为**——
 * 报告看起来是「A/B 参数对照」，实际是同一套配置跑了两遍。这与假 embedding 那次事故
 * 是同一类错误：**评测因为错误的原因给出结论**。</p>
 *
 * <p>现在把值本身作为参数在调用链上传递：评测路径传入 {@link EvalParamOverrides} 解析出的
 * 覆盖值，生产路径由 {@link #from(MemoryProperties)} 取配置当前值。生产路径的行为与改造前
 * 逐位一致。</p>
 *
 * @param rrfK                 RRF 融合常数
 * @param alpha                重排公式里 recency 因子的权重
 * @param beta                 重排公式里 importance 因子的权重
 * @param recencyHalfLifeDays  recency 指数衰减的半衰期（天）
 * @param profileBoost         命中画像候选的乘法 boost
 * @param minConfidence        回表过滤的置信度下限
 * @param injectMaxTokens      注入时的 token 预算
 */
public record RetrieverParams(
        int rrfK,
        double alpha,
        double beta,
        double recencyHalfLifeDays,
        double profileBoost,
        double minConfidence,
        int injectMaxTokens) {

    /** 取配置当前值。生产路径专用——每次调用现取，配置热更新后立即生效（与改造前一致）。 */
    public static RetrieverParams from(MemoryProperties properties) {
        MemoryProperties.Retrieval r = properties.getRetrieval();
        MemoryProperties.Inject i = properties.getInject();
        return new RetrieverParams(
                r.getRrfK(),
                r.getAlpha(),
                r.getBeta(),
                r.getRecencyHalfLifeDays(),
                r.getProfileBoost(),
                r.getMinConfidence(),
                i.getMaxTokens());
    }
}
