-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- Phase 14: 修复 nextId 并发竞态 — 将 ai_task.id 和 outbox_event.event_id 改为 AUTO_INCREMENT
-- 问题：MAX(id)+1 在高并发下存在竞态，两个请求可能拿到相同 ID 导致主键冲突
-- 解决：使用数据库自增主键，由 MySQL 保证唯一性

-- 修改 ai_task 主键为自增
ALTER TABLE ai_task MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID（自增）';

-- 修改 outbox_event 主键为自增
ALTER TABLE outbox_event MODIFY COLUMN event_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件ID（自增）';
