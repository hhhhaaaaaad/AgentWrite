package cn.sutone.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 记忆治理动作撤销记录（主表）PO，对应 {@code memory_governance_undo}。
 *
 * <p>一次治理动作（MERGE/ARCHIVE/SUPERSEDE/DISPUTE/QUARANTINE/...）一行；
 * 被作用的逐行记忆及其动作前 status 存子表 {@link MemoryGovernanceUndoItemPO}，
 * 用子表替代 JSON 数组，消除数组顺序不稳定导致的 id↔status 错位。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryGovernanceUndoPO {

    private Long id;
    /** 治理动作: MERGE/ARCHIVE/SUPERSEDE/QUARANTINE/DISPUTE/... */
    private String action;
    /** 合并目标 id（action=MERGE 时非空） */
    private Long mergedIntoId;
    /** 操作者: GOVERNANCE_JOB / 人工账号 */
    private String operator;
    /** 创建时间（DB 默认 CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;
    /** 已回滚时间，NULL=未回滚 */
    private LocalDateTime revertedAt;
}
