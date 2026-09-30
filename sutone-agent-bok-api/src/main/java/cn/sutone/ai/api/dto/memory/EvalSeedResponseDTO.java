package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 评测种子写入结果（/api/v1/eval/seed）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSeedResponseDTO {

    /** 本次新增条数 */
    private long inserted;

    /** 本次命中已存在（幂等复用）条数 */
    private long existed;

    /** content -> createdId 映射（诊断用） */
    private Map<String, Long> contentToId;
}
