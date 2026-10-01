package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评测种子写入请求（/api/v1/eval/seed）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalSeedRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;

    /** 待写入的种子记忆 */
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** 记忆类型：fact / preference / knowledge / event */
        private String type;
        /** 记忆内容 */
        private String content;

        // —— 以下为治理维度三类任务（consistency / expired / hallucination）可达所需的可选字段。
        // 全部可空：不传时行为与旧版逐位一致（subject/predicate/value/confidence 落 NULL，
        // expireTime 走 MemoryRecordEntity.defaultExpireTime(type) 推导），保证向后兼容。

        /** 主体，如 user（consistency 扫描要求 subject 非空） */
        private String subject;
        /** 稳定属性，如 tech_stack（consistency 扫描要求 predicate 非空） */
        private String predicate;
        /** 属性值（consistency 按同 subject+predicate 异 value 判冲突） */
        private String value;
        /** 抽取置信度 0-1（hallucination 扫描取 [0.8, 0.9] 灰色地带） */
        private Double confidence;
        /**
         * 显式有效期，ISO-8601 LocalDateTime 字符串（如 {@code "2020-01-01T00:00:00"}）。
         * 为 null / 空白时走默认推导；非空时以显式值覆写（可传过去时刻，使 expired 任务可达）。
         * 语义细节见 {@code MemoryEvalGuardService#applyGovernanceFields}。
         */
        private String expireTime;
    }
}
