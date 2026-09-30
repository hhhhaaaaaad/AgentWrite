-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- Phase 13: MQ 可靠性修复
-- 1. publisher_id: 标识 Outbox Publisher 实例，用于防止重复投递
-- 2. 索引: 加速 SENDING 事件恢复查询

ALTER TABLE outbox_event
    ADD COLUMN publisher_id VARCHAR(64) NULL COMMENT 'Publisher 实例 ID (UUID)';

CREATE INDEX idx_outbox_publisher ON outbox_event (publisher_id);
CREATE INDEX idx_outbox_sending_recovery ON outbox_event (status, update_time);
