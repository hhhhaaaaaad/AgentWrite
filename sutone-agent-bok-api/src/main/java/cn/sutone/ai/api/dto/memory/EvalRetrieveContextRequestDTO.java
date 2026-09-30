package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测注入上下文请求（/api/v1/eval/retrieve-context）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalRetrieveContextRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;

    /** 草稿/查询上下文 */
    private String queryContext;

    /** 检索 topK，默认 5 */
    private int topK = 5;
}
