package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测 fencing 释放请求（/api/v1/eval/fencing/release）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalFencingReleaseRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;

    /** 待释放的 run id（仅当匹配 active_run_id 时才清空） */
    private String runId;
}
