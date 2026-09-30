package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测重置请求（/api/v1/eval/reset）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalResetRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;
}
