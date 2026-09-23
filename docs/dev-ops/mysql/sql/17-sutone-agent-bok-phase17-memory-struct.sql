-- ============================================================
-- Phase17: 记忆系统结构化升级（P1）
-- 新增：来源 / 置信度 / 有效期 / 三元组 / 证据 / trace_id / 版本 / 生命周期状态
-- 说明：全部字段可空或有默认值，不影响存量行；可灰度执行。
-- ============================================================

-- 1. 结构化字段
ALTER TABLE memory_record
    ADD COLUMN attributed_to  VARCHAR(16)  DEFAULT NULL COMMENT '来源 user/agent/system',
    ADD COLUMN confidence     DOUBLE       DEFAULT NULL COMMENT '抽取置信度 0-1',
    ADD COLUMN expire_time    DATETIME     DEFAULT NULL COMMENT '记忆有效期',
    ADD COLUMN subject        VARCHAR(64)  DEFAULT NULL COMMENT '主体，如 user',
    ADD COLUMN predicate      VARCHAR(64)  DEFAULT NULL COMMENT '稳定属性，如 tech_stack',
    ADD COLUMN `value`        VARCHAR(512) DEFAULT NULL COMMENT '属性值（value 为 MySQL 关键字，需反引号）',
    ADD COLUMN evidence       TEXT         DEFAULT NULL COMMENT '证据片段',
    ADD COLUMN trace_id       VARCHAR(64)  DEFAULT NULL COMMENT 'memory_trace_id',
    ADD COLUMN operation      VARCHAR(16)  DEFAULT 'ADD' COMMENT 'ADD / UPDATE',
    ADD COLUMN version        INT          DEFAULT 1 COMMENT '版本号',
    ADD COLUMN status         VARCHAR(16)  DEFAULT 'ACTIVE' COMMENT '生命周期状态: ACTIVE/PROBATION/DISPUTED/SUPERSEDED/ARCHIVED/QUARANTINED/MERGE_PENDING/OBSERVATION',
    ADD COLUMN valid_from     DATETIME     DEFAULT NULL COMMENT '生效时间',
    ADD COLUMN valid_to       DATETIME     DEFAULT NULL COMMENT '失效时间',
    ADD COLUMN next_retry_at  DATETIME     DEFAULT NULL COMMENT '向量同步下次重试时间',
    ADD COLUMN last_error     VARCHAR(512) DEFAULT NULL COMMENT '向量同步最后错误';

-- 2. 普通索引（查询过滤用）
ALTER TABLE memory_record
    ADD INDEX idx_user_subject_predicate (user_id, subject, predicate, status),
    ADD INDEX idx_status (status),
    ADD INDEX idx_expire (expire_time),
    ADD INDEX idx_trace (trace_id);

-- 3. 唯一约束（generated-column 部分唯一索引）
-- 只约束 status='ACTIVE' 且 subject/predicate 非空的行：每 (user_id, subject, predicate) 最多一个 ACTIVE 版本。
-- DISPUTED / SUPERSEDED / ARCHIVED 等状态可任意多行（MySQL 唯一索引对 NULL 不互斥）。
-- 存量行 subject/predicate 为 NULL，不受影响，可安全立即加。
-- 前置条件：Java 层 predicate 归一化必须先落地，否则 tech_stack / 技术栈 会被当作不同属性、无法去重。
ALTER TABLE memory_record
    ADD COLUMN active_sp_uk VARCHAR(192) AS (
        IF(status = 'ACTIVE' AND subject IS NOT NULL AND predicate IS NOT NULL,
           CONCAT_WS('|', user_id, subject, predicate), NULL)
    ) STORED COMMENT 'ACTIVE 三元组唯一键（NULL 不参与唯一约束）',
    ADD UNIQUE KEY uk_user_sp_active (active_sp_uk);
