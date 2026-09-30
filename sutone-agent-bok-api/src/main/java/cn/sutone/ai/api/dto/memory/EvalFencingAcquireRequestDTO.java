package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测 fencing 抢占请求（/api/v1/eval/fencing/acquire）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalFencingAcquireRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;

    /** 预期版本（来自 GET 哨兵；首次为 0），对账不一致返回 conflict */
    private long expectedVersion;

    /** 本次抢占的新 run id（UUID 小写带连字符） */
    private String runId;
}
