package cn.sutone.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆治理动作撤销明细（子表）PO，对应 {@code memory_governance_undo_item}。
 *
 * <p>逐行记录被作用记忆 {@code memory_id} 与其动作前 {@code beforeStatus}（回滚目标值），
 * 回滚只需逐行 {@code UPDATE memory_record SET status = before_status WHERE id = memory_id}，
 * 无需按下标对齐，消除 JSON 数组顺序依赖。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryGovernanceUndoItemPO {

    private Long id;
    /** 关联 {@code memory_governance_undo.id} */
    private Long undoId;
    /** 被作用的 {@code memory_record.id} */
    private Long memoryId;
    /** 该行动作前的 status（回滚目标值） */
    private String beforeStatus;
}
