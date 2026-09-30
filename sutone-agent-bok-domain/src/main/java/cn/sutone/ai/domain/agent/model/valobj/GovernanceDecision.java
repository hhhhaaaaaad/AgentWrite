package cn.sutone.ai.domain.agent.model.valobj;

import java.util.List;

/**
 * 治理决策（compute 层产出，apply 层消费）。
 *
 * <p>一次决策对应一次治理动作的<b>批次</b>语义：{@code action} + 可选 {@code mergedIntoId}
 * 定义动作，{@code items} 是该动作作用的所有记忆。此粒度与撤销记录
 * （{@code memory_governance_undo} 一条主记录 + N 条明细）一一对应。</p>
 *
 * <p>评测 replay 只需要 compute 层产出本对象，<b>不做任何落库</b>。</p>
 *
 * @param action       治理动作：MERGE / ARCHIVE / DISPUTE / QUARANTINE
 * @param mergedIntoId 合并目标记忆 id（仅 action=MERGE 时非空）
 * @param reason       判定原因（供 replay 结果解释性）
 * @param items        被作用的记忆明细
 */
public record GovernanceDecision(
        String action,
        Long mergedIntoId,
        String reason,
        List<Item> items
) {

    /**
     * 单条被作用记忆。
     *
     * @param memoryId     记忆 id
     * @param beforeStatus 动作前 status（回滚目标值）
     * @param afterStatus  动作后 status
     */
    public record Item(Long memoryId, String beforeStatus, String afterStatus) {
    }
}
