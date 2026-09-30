-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- Phase 12: RocketMQ 消息队列改造 — ai_task 扩展 + outbox_event 新建
-- 执行前请确认数据库为 sutone_agent_bok

-- ============================
-- 1. 扩展 ai_task 表
-- ============================
ALTER TABLE ai_task
    ADD COLUMN started_at DATETIME NULL COMMENT '本次执行开始时间',
    ADD COLUMN heartbeat_at DATETIME NULL COMMENT 'Worker 执行心跳',
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    ADD COLUMN next_retry_at DATETIME NULL COMMENT '下次允许重试时间',
    ADD COLUMN worker_id VARCHAR(128) NULL COMMENT '执行 Worker 标识';

CREATE INDEX idx_ai_task_recovery
    ON ai_task (status, heartbeat_at);

CREATE INDEX idx_ai_task_retry
    ON ai_task (status, next_retry_at);

-- ============================
-- 2. 新建 outbox_event 表
-- ============================
CREATE TABLE outbox_event (
    event_id BIGINT PRIMARY KEY COMMENT '事件ID (雪花算法或业务生成)',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型: AI_WRITING_TASK_CREATED',
    aggregate_id VARCHAR(64) NOT NULL COMMENT '关联业务ID (taskId)',
    topic VARCHAR(128) NOT NULL COMMENT '目标 RocketMQ Topic',
    payload JSON NOT NULL COMMENT '消息体',
    status VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT '状态: NEW, RETRYING, PUBLISHED, FAILED',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_at DATETIME NULL COMMENT '下次重试时间',
    published_at DATETIME NULL COMMENT '发布时间',
    last_error VARCHAR(1024) NULL COMMENT '最后一次错误信息',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_outbox_publish (status, next_retry_at, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表 (Transactional Outbox)';
