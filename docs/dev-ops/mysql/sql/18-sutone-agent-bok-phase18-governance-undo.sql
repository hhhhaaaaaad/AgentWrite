-- ============================================================
-- Phase18: 记忆治理动作撤销记录（P3，对应计划 §2.4 撤销表）
-- 主表 memory_governance_undo   ：记录一次治理动作（MERGE/ARCHIVE/SUPERSEDE/...）
-- 子表 memory_governance_undo_item：逐行记录被作用记忆及其动作前 status
-- 设计说明：用关联子表替代原 JSON 数组（affected_ids / before_status），
--   消除 JSON 数组顺序不稳定导致的 id↔status 错位，回滚无需按下标对齐。
-- ============================================================

-- 1. 撤销记录主表
CREATE TABLE IF NOT EXISTS `memory_governance_undo` (
  `id`             bigint(20)  NOT NULL AUTO_INCREMENT COMMENT '撤销记录ID',
  `action`         varchar(32) NOT NULL COMMENT '治理动作: MERGE/ARCHIVE/SUPERSEDE/QUARANTINE/...',
  `merged_into_id` bigint(20)  DEFAULT NULL COMMENT '合并目标 id（action=MERGE 时非空）',
  `operator`       varchar(32) NOT NULL DEFAULT 'GOVERNANCE_JOB' COMMENT '操作者: GOVERNANCE_JOB / 人工账号',
  `created_at`     datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `reverted_at`    datetime    DEFAULT NULL COMMENT '已回滚时间, NULL=未回滚',
  PRIMARY KEY (`id`),
  KEY `idx_action` (`action`),
  KEY `idx_reverted` (`reverted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆治理动作撤销记录（主表）';

-- 2. 撤销记录明细子表（逐行存储，替代 JSON 数组）
CREATE TABLE IF NOT EXISTS `memory_governance_undo_item` (
  `id`            bigint(20)  NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  `undo_id`       bigint(20)  NOT NULL COMMENT '关联 memory_governance_undo.id',
  `memory_id`     bigint(20)  NOT NULL COMMENT '被作用的 memory_record.id',
  `before_status` varchar(16) NOT NULL COMMENT '该行动作前的 status（回滚目标值）',
  PRIMARY KEY (`id`),
  KEY `idx_undo_id` (`undo_id`),
  KEY `idx_memory_id` (`memory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆治理动作撤销明细（子表，逐行存储消除 JSON 数组顺序依赖）';

-- ============================================================
-- 回滚路径（撤销一次治理动作）：
--   1) 按 undo_id 取出全部明细行：
--        SELECT memory_id, before_status FROM memory_governance_undo_item WHERE undo_id = ?;
--   2) 逐行还原 status（合并在硬化前不删原始行、不改向量，故回滚只需改 status）：
--        UPDATE memory_record SET status = <before_status> WHERE id = <memory_id>;
--   3) 标记该撤销记录已回滚：
--        UPDATE memory_governance_undo SET reverted_at = NOW() WHERE id = ? AND reverted_at IS NULL;
-- 注：只有「硬化确认」后才物理删除/真正合并，且硬化动作本身也必须先写 undo 记录。
--     本脚本仅建表，不含数据变更。
-- ============================================================
