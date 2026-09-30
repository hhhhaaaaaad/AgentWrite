package cn.sutone.ai.domain.agent.adapter.repository;

import java.util.List;

/**
 * 记忆治理撤销记录端口（端口-适配器，避免 domain 反向依赖 infrastructure）。
 *
 * <p>治理动作执行前先写撤销记录（记录各行动作前 status），硬化 / 人工确认前可回滚。
 * 参照 {@link IMemoryMetricsPort} 的端口-适配器模式：domain 定义接口，infrastructure 实现。</p>
 */
public interface IMemoryGovernanceUndoPort {

    /**
     * 写撤销记录（主表 + 子表）。
     *
     * @param action       治理动作（MERGE / ARCHIVE / DISPUTE / QUARANTINE）
     * @param items        被作用明细（memoryId + 动作前 status），逐行存子表
     * @param mergedIntoId 合并目标 id（action=MERGE 时非空）
     * @return undoId；无明细时返回 null 并跳过
     */
    Long recordUndo(String action, List<UndoItem> items, Long mergedIntoId);

    /** 撤销明细条目：被作用记忆 id + 该行动作前 status（回滚目标值） */
    record UndoItem(Long memoryId, String beforeStatus) {
    }
}
