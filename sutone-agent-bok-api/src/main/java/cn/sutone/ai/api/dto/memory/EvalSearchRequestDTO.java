package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测检索请求（/api/v1/eval/search）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSearchRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;

    /** 查询文本 */
    private String query;

    /** 返回条数，默认 5 */
    private int topK = 5;

    /** 召回阈值，默认 0.1 */
    private Double threshold;

    /** true=冻结副作用（不写 access/缓存/rerank），评测可复现模式 */
    private boolean freezeSideEffects = true;

    /** Qdrant exact 精确检索（null=默认近似 HNSW） */
    private Boolean exact;

    /** Qdrant hnsw_ef 参数（null=Qdrant 默认） */
    private Integer hnswEf;

    /**
     * 本次调用的参数覆盖（null = 全部按服务端配置）。
     * 用于「同一份数据、不同参数」的对照实验——没有它，平台改快照参数只会改变
     * config_fingerprint 而改变不了被测系统的行为。
     */
    private EvalParamOverridesDTO overrides;
}
