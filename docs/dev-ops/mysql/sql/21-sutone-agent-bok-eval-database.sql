-- =============================================================================
-- 评测实例专用库：sutone_agent_bok_eval
-- -----------------------------------------------------------------------------
-- 与业务库 sutone_agent_bok 隔离，仅供 eval profile（application-eval.yml）使用。
--
-- 建库方式：复制业务库全部表结构（CREATE TABLE ... LIKE）。
--   - 不复制数据：评测实例从空库开始，语料由评测平台 seed 灌入。
--   - 不复制外键：MySQL 的 CREATE TABLE ... LIKE 只复制表结构 + 索引，不复制外键。
--     对评测场景可接受——评测只读写 memory_* 与 user 表，不依赖业务库的外键链路。
--
-- 执行时机：本脚本编号 21，在 docker-entrypoint-initdb.d 按字母序执行，
-- 晚于 01~20（业务库建表），因此 CREATE TABLE ... LIKE 能引用到业务库的表。
-- =============================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `sutone_agent_bok_eval`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `sutone_agent_bok_eval`;

-- 用户/账号
CREATE TABLE IF NOT EXISTS `user` LIKE `sutone_agent_bok`.`user`;

-- AI 写作业务
CREATE TABLE IF NOT EXISTS `ai_task`            LIKE `sutone_agent_bok`.`ai_task`;
CREATE TABLE IF NOT EXISTS `article`            LIKE `sutone_agent_bok`.`article`;
CREATE TABLE IF NOT EXISTS `article_meta`       LIKE `sutone_agent_bok`.`article_meta`;
CREATE TABLE IF NOT EXISTS `article_comment`    LIKE `sutone_agent_bok`.`article_comment`;
CREATE TABLE IF NOT EXISTS `article_favorite`   LIKE `sutone_agent_bok`.`article_favorite`;
CREATE TABLE IF NOT EXISTS `article_like`       LIKE `sutone_agent_bok`.`article_like`;
CREATE TABLE IF NOT EXISTS `article_view_daily`    LIKE `sutone_agent_bok`.`article_view_daily`;
CREATE TABLE IF NOT EXISTS `article_view_snapshot` LIKE `sutone_agent_bok`.`article_view_snapshot`;
CREATE TABLE IF NOT EXISTS `chat_message`       LIKE `sutone_agent_bok`.`chat_message`;
CREATE TABLE IF NOT EXISTS `comment_like`       LIKE `sutone_agent_bok`.`comment_like`;
CREATE TABLE IF NOT EXISTS `draft`              LIKE `sutone_agent_bok`.`draft`;
CREATE TABLE IF NOT EXISTS `notification`       LIKE `sutone_agent_bok`.`notification`;
CREATE TABLE IF NOT EXISTS `outbox_event`       LIKE `sutone_agent_bok`.`outbox_event`;
CREATE TABLE IF NOT EXISTS `user_follow`        LIKE `sutone_agent_bok`.`user_follow`;
CREATE TABLE IF NOT EXISTS `user_model_config`  LIKE `sutone_agent_bok`.`user_model_config`;

-- 记忆系统（评测核心）
CREATE TABLE IF NOT EXISTS `memory_record`             LIKE `sutone_agent_bok`.`memory_record`;
CREATE TABLE IF NOT EXISTS `memory_history`            LIKE `sutone_agent_bok`.`memory_history`;
CREATE TABLE IF NOT EXISTS `memory_governance_undo`     LIKE `sutone_agent_bok`.`memory_governance_undo`;
CREATE TABLE IF NOT EXISTS `memory_governance_undo_item` LIKE `sutone_agent_bok`.`memory_governance_undo_item`;

-- 评测 fencing（权威态）
CREATE TABLE IF NOT EXISTS `eval_fencing` LIKE `sutone_agent_bok`.`eval_fencing`;
