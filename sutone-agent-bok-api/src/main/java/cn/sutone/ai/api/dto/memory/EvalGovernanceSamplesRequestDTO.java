package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 治理样本导出请求（/api/v1/eval/governance/samples）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalGovernanceSamplesRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;
}
