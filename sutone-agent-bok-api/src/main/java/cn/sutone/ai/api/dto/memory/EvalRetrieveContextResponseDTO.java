package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评测注入上下文结果（/api/v1/eval/retrieve-context）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRetrieveContextResponseDTO {

    /** 格式化后的注入文本 */
    private String formatted;

    /** 注入 token 数（供 token 超预算率评测） */
    private int tokenCount;

    /** 预算内记忆 id 列表（供无关注入率评测） */
    private List<Long> budgetedIds;
}
