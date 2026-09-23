package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.infrastructure.dao.IMemoryGovernanceUndoDao;
import cn.sutone.ai.infrastructure.dao.IMemoryRecordDao;
import cn.sutone.ai.infrastructure.dao.po.MemoryGovernanceUndoItemPO;
import cn.sutone.ai.infrastructure.dao.po.MemoryGovernanceUndoPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * 记忆治理动作撤销仓储（P3-5）。
 *
 * <p>实现「先软标记、后硬化」的可逆性契约：治理任务软标记前先记录各行动作前 status，
 * 硬化 / 人工确认前可随时 {@link #revertUndo(Long)} 回滚（只改 status，不物理删除）。</p>
 */
@Slf4j
@Repository
public class MemoryGovernanceUndoRepository {

    @Resource
    private IMemoryGovernanceUndoDao governanceUndoDao;

    @Resource
    private IMemoryRecordDao memoryRecordDao;

    /**
     * 写撤销记录（主表 + 子表），返回 undoId。
     *
     * @param action       治理动作（MERGE/ARCHIVE/SUPERSEDE/DISPUTE/QUARANTINE/...）
     * @param items        被作用明细（memoryId + beforeStatus），逐行存子表
     * @param mergedIntoId 合并目标 id（action=MERGE 时非空）
     * @return undoId；无明细时返回 null 并跳过
     */
    @Transactional(rollbackFor = Exception.class)
    public Long recordUndo(String action, List<MemoryGovernanceUndoItemPO> items, Long mergedIntoId) {
        if (items == null || items.isEmpty()) {
            log.warn("[governance-undo] recordUndo 跳过：无被作用明细, action={}", action);
            return null;
        }
        MemoryGovernanceUndoPO po = MemoryGovernanceUndoPO.builder()
                .action(action)
                .mergedIntoId(mergedIntoId)
                .operator("GOVERNANCE_JOB")
                .build();
        governanceUndoDao.insert(po);
        Long undoId = po.getId();
        for (MemoryGovernanceUndoItemPO item : items) {
            item.setUndoId(undoId);
            governanceUndoDao.insertItem(item);
        }
        log.info("[governance-undo] 已写撤销记录 undoId={}, action={}, affected={}", undoId, action, items.size());
        return undoId;
    }

    /**
     * 撤销一条治理动作：逐行还原 {@code status=before_status}，再标记 {@code reverted_at=NOW()}。
     *
     * <p>合并 / 归档在硬化前不删原始行、不改向量，因此回滚只需改 status，
     * 无需从 {@code memory_history} 逆推。重复调用幂等（已回滚的直接跳过）。</p>
     *
     * @param undoId 撤销记录 id
     */
    @Transactional(rollbackFor = Exception.class)
    public void revertUndo(Long undoId) {
        if (undoId == null) {
            return;
        }
        MemoryGovernanceUndoPO po = governanceUndoDao.selectById(undoId);
        if (po == null || po.getRevertedAt() != null) {
            log.warn("[governance-undo] revertUndo 跳过：undoId={} 不存在或已回滚", undoId);
            return;
        }
        List<MemoryGovernanceUndoItemPO> items = governanceUndoDao.selectItemsByUndoId(undoId);
        if (items == null || items.isEmpty()) {
            log.warn("[governance-undo] revertUndo 跳过：undoId={} 无明细", undoId);
            return;
        }
        for (MemoryGovernanceUndoItemPO item : items) {
            memoryRecordDao.updateStatus(item.getMemoryId(), item.getBeforeStatus());
        }
        governanceUndoDao.markReverted(undoId);
        log.info("[governance-undo] 已回滚撤销记录 undoId={}, affected={}", undoId, items.size());
    }
}
