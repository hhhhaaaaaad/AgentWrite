package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测观测指标（/api/v1/eval/metrics）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalMetricsResponseDTO {

    /** 抽取驳回率（rejected / (accepted + rejected)） */
    private double extractionRejectRate;

    /** 向量同步积压数（HNSW 向量就绪屏障读取） */
    private long vectorSyncPendingCount;
}
