-- =============================================================================
-- 评测实例专用库：sutone_agent_bok_eval
-- -----------------------------------------------------------------------------
-- 与业务库 sutone_agent_bok 隔离，仅供 eval profile（application-eval.yml）使用。
--
-- 【为什么复制业务库**全部**表，而不是只挑 memory_* 】
--   评测实例与业务实例是**同一个应用**（同一个 jar，只是 profile 不同）。
--   Spring 上下文里注册的 DAO、@Scheduled 任务、MQ 消费者并不会因为换了 profile
--   就不装配，因此评测实例运行时照样会去写 outbox_event、ai_task 等表。
--   实测证据：只建 6 张 memory/评测表时，应用启动成功（MyBatis 不在启动期校验表结构），
--   但日志持续刷 `Table 'sutone_agent_bok_eval.outbox_event' doesn't exist`（36 次）
--   与 `... ai_task ...`（3 次）。
--   结论：评测库必须是与业务库同构的完整 schema。
--
-- 【前置条件（曾经是硬伤）】
--   本脚本依赖业务库已完成初始化。此前这条依赖**从未被满足**：
--   04~20 号脚本都缺 `USE sutone_agent_bok;`，而 initdb 调用 mysql 客户端时不带默认库，
--   于是 04 号第一个报 `ERROR 1046 (3D000): No database selected`，entrypoint 随即中断、
--   容器退出——业务库只建到 03 号为止的 7 张表，21 号脚本从未被执行过。
--   已修复：所有脚本补齐 `USE`，全新环境可完整初始化。
--   实测（一次性容器挂本目录从空库跑一遍）：**21 张业务表 + 21 张评测表**，
--   两库逐表列数完全一致、各 188 列，初始化日志零错误。
--   注意此处曾写作「21 张业务表 + 6 张评测表」——那是本脚本**只复制 6 张 memory 表**
--   的旧版本的残留数字，与现在的行为不符，已按实测更正。
--
-- 执行时机：本脚本编号 21，在 docker-entrypoint-initdb.d 按字母序最后执行。
-- 若源表缺失，本脚本会在**建任何表之前**失败并点名缺失的表；补齐后重跑即可
-- （全部使用 CREATE TABLE IF NOT EXISTS，重复执行安全）。
-- =============================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `sutone_agent_bok_eval`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `sutone_agent_bok_eval`;

-- ---------------------------------------------------------------------------
-- 前置检查：源表必须全部存在，否则在**建任何表之前**就失败。
--
-- 写成「引用全部源表 + WHERE 1 = 0」的探测查询，而不是 information_schema 判断 +
-- SIGNAL：MySQL 的 SIGNAL **不支持预处理语句协议**（ERROR 1295），
-- 动态拼 SIGNAL 的写法只会在真正缺表时抛出一个与病因无关的语法错误。
-- 探测查询则由 MySQL 自己报 `Table 'sutone_agent_bok.<缺失表>' doesn't exist`——
-- 名字精确、零副作用、无需清理（不放视图、不建临时表）。
--
-- 这样做的价值：源表缺失时**不会留下半成品库**。逐张 CREATE ... LIKE 的写法
-- 撞上第一张缺失的源表就中止，但此前已建好的表留在库里，形成「能启动、一跑就崩」
-- 的假象——这正是本脚本重写的原因。
-- ---------------------------------------------------------------------------
SELECT 1 FROM `sutone_agent_bok`.`ai_task`
    , `sutone_agent_bok`.`article`
    , `sutone_agent_bok`.`article_comment`
    , `sutone_agent_bok`.`article_favorite`
    , `sutone_agent_bok`.`article_like`
    , `sutone_agent_bok`.`article_meta`
    , `sutone_agent_bok`.`article_view_daily`
    , `sutone_agent_bok`.`article_view_snapshot`
    , `sutone_agent_bok`.`chat_message`
    , `sutone_agent_bok`.`comment_like`
    , `sutone_agent_bok`.`draft`
    , `sutone_agent_bok`.`eval_fencing`
    , `sutone_agent_bok`.`memory_governance_undo`
    , `sutone_agent_bok`.`memory_governance_undo_item`
    , `sutone_agent_bok`.`memory_history`
    , `sutone_agent_bok`.`memory_record`
    , `sutone_agent_bok`.`notification`
    , `sutone_agent_bok`.`outbox_event`
    , `sutone_agent_bok`.`user`
    , `sutone_agent_bok`.`user_follow`
    , `sutone_agent_bok`.`user_model_config`
WHERE 1 = 0;

-- ---------------------------------------------------------------------------
-- 建表（只复制表结构：不复制数据、不复制外键）
--   - 不复制数据：评测实例从空库开始，语料由评测平台 seed 灌入。
--   - 不复制外键：MySQL 的 CREATE TABLE ... LIKE 只复制表结构 + 索引，不复制外键。
--     对评测场景可接受——评测只读写这些表，不依赖业务库的外键链路。
--
-- 新增业务表时需同步维护此清单与上面的探测查询，两处必须一致
-- （探测少了 → 建表时才失败；探测多了 → 源表不存在则永远失败）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_task`                      LIKE `sutone_agent_bok`.`ai_task`;
CREATE TABLE IF NOT EXISTS `article`                      LIKE `sutone_agent_bok`.`article`;
CREATE TABLE IF NOT EXISTS `article_comment`              LIKE `sutone_agent_bok`.`article_comment`;
CREATE TABLE IF NOT EXISTS `article_favorite`             LIKE `sutone_agent_bok`.`article_favorite`;
CREATE TABLE IF NOT EXISTS `article_like`                 LIKE `sutone_agent_bok`.`article_like`;
CREATE TABLE IF NOT EXISTS `article_meta`                 LIKE `sutone_agent_bok`.`article_meta`;
CREATE TABLE IF NOT EXISTS `article_view_daily`           LIKE `sutone_agent_bok`.`article_view_daily`;
CREATE TABLE IF NOT EXISTS `article_view_snapshot`        LIKE `sutone_agent_bok`.`article_view_snapshot`;
CREATE TABLE IF NOT EXISTS `chat_message`                 LIKE `sutone_agent_bok`.`chat_message`;
CREATE TABLE IF NOT EXISTS `comment_like`                 LIKE `sutone_agent_bok`.`comment_like`;
CREATE TABLE IF NOT EXISTS `draft`                        LIKE `sutone_agent_bok`.`draft`;
CREATE TABLE IF NOT EXISTS `eval_fencing`                 LIKE `sutone_agent_bok`.`eval_fencing`;
CREATE TABLE IF NOT EXISTS `memory_governance_undo`       LIKE `sutone_agent_bok`.`memory_governance_undo`;
CREATE TABLE IF NOT EXISTS `memory_governance_undo_item`  LIKE `sutone_agent_bok`.`memory_governance_undo_item`;
CREATE TABLE IF NOT EXISTS `memory_history`               LIKE `sutone_agent_bok`.`memory_history`;
CREATE TABLE IF NOT EXISTS `memory_record`                LIKE `sutone_agent_bok`.`memory_record`;
CREATE TABLE IF NOT EXISTS `notification`                 LIKE `sutone_agent_bok`.`notification`;
CREATE TABLE IF NOT EXISTS `outbox_event`                 LIKE `sutone_agent_bok`.`outbox_event`;
CREATE TABLE IF NOT EXISTS `user`                         LIKE `sutone_agent_bok`.`user`;
CREATE TABLE IF NOT EXISTS `user_follow`                  LIKE `sutone_agent_bok`.`user_follow`;
CREATE TABLE IF NOT EXISTS `user_model_config`            LIKE `sutone_agent_bok`.`user_model_config`;
