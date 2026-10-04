package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测参数覆盖（附在 {@code /eval/search}、{@code /eval/retrieve-context} 的请求体上）。
 *
 * <p><b>字段为 null = 不覆盖该参数</b>，于是「只想试 alpha」不必把其余六个抄一遍，
 * 也就不会因抄错而悄悄改变别的维度。</p>
 *
 * <p>不含 {@code vectorStore}（部署期选择 collection，请求级覆盖无意义），也不含
 * reranker 参数——评测检索默认 {@code freeze=true}，而 freeze 会跳过精排，
 * 传了也不会生效。理由详见领域层 {@code EvalParamOverrides} 的文档。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalParamOverridesDTO {

    /** 覆盖 RRF 融合常数 */
    private Integer rrfK;

    /** 覆盖 recency 因子权重 */
    private Double alpha;

    /** 覆盖 importance 因子权重 */
    private Double beta;

    /** 覆盖 recency 半衰期（天） */
    private Double recencyHalfLifeDays;

    /** 覆盖画像 boost */
    private Double profileBoost;

    /** 覆盖置信度下限 */
    private Double minConfidence;

    /** 覆盖注入 token 预算 */
    private Integer injectMaxTokens;
}
