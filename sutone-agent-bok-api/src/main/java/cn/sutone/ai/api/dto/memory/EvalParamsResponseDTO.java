package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测参数快照（/api/v1/eval/params），供平台生成 config_fingerprint。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalParamsResponseDTO {

    /** 向量存储模式：memory / qdrant */
    private String vectorStore;

    private int rrfK;
    private double alpha;
    private double beta;
    private double recencyHalfLifeDays;
    private double profileBoost;
    private double minConfidence;
    private int injectMaxTokens;
}
