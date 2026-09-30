package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测重置结果（/api/v1/eval/reset）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalResetResponseDTO {

    /** MySQL 物理删除行数 */
    private long mysqlDeleted;

    /** 向量删除是否确认（无异常即 true） */
    private boolean vectorCleared;
}
