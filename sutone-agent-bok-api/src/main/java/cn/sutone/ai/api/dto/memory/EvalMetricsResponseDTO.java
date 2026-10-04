package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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

    /**
     * 按原因拆分的抽取驳回次数（原因全集，未出现过的计 0）。
     *
     * <p><b>为什么总率不够用</b>：{@code extractionRejectRate} 把「模型抽得太长被丢」与
     * 「模型给了个非法类型被丢」混成一个数。两种原因的处置完全不同——前者可能要调
     * 长度上限，后者是模型没守 schema——只看总率无法区分该改哪一个。</p>
     *
     * <p><b>这是进程内累计值，不是本次 run 的。</b> 要得到某次 run 的增量，调用方必须取
     * run 前后两次读数的差；直接当作 per-run 指标会把进程启动以来的全部历史算进去。</p>
     */
    private Map<String, Long> extractionRejectedByReason;
}
