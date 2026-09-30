package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 治理样本导出结果（/api/v1/eval/governance/samples）。
 *
 * <p>四类治理任务的输入候选快照，供评测平台与 ground truth 对账。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalGovernanceSamplesResponseDTO {

    /** 重复聚类输入（全部 ACTIVE 记忆） */
    private Bucket duplicates;

    /** 事实一致性输入（带 subject+predicate 的 ACTIVE 记忆） */
    private Bucket consistency;

    /** 过期清理输入（过期待归档记忆） */
    private Bucket expired;

    /** 幻觉抽检输入（confidence 灰色地带抽样） */
    private Bucket hallucination;

    /** 单类样本桶 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bucket {
        /** 真实候选总数（可能大于 items.size()，导出有条数上限） */
        private long total;
        /** 导出的样本明细 */
        private List<Item> items;
    }

    /** 单条治理样本（只读视图） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long memoryId;
        private Long userId;
        private String type;
        private String content;
        private String status;
        private String subject;
        private String predicate;
        private String value;
        private Double confidence;
    }
}
