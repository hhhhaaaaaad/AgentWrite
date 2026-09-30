-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- ============================================================
-- V2 记忆系统升级：向量同步状态追踪
--
-- 【为什么不用 `ADD COLUMN IF NOT EXISTS` 】
--   那是 MariaDB 的扩展语法，MySQL 8 不支持，会直接抛
--   `ERROR 1064 (42000) ... near 'IF NOT EXISTS vector_status'`。
--   更严重的是它在 docker-entrypoint-initdb.d 里会被判为失败并**中断整轮初始化**，
--   于是本脚本之后的 11~21（含记忆表、治理表、评测 fencing、评测库）一个都不会执行——
--   表现为「业务库建了一半、评测库根本不存在」。
--   这正是本项目实际踩过的坑：原本只有 7 张业务表，且没有 memory_record / eval_fencing。
--
-- 【本脚本的两种执行场景都要能用】
--   1. 全新库（docker-initdb）：列不存在 → 执行 ALTER；
--   2. 存量库（补跑）：列已存在 → 跳过，不报错。
--   所以用 information_schema 判断 + 动态 SQL，而不是裸 ALTER。
--   注意：本脚本依赖 memory_record 已存在（由 09 脚本创建）。
-- ============================================================

-- 1. vector_status：向量同步状态（addDirect 向量 upsert 失败时标记 PENDING 供异步重试）
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'memory_record'
       AND column_name = 'vector_status') = 0,
    'ALTER TABLE memory_record ADD COLUMN vector_status VARCHAR(16) DEFAULT ''SYNCED''
        COMMENT ''向量同步状态: SYNCED / PENDING / FAILED''',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. retry_count：向量同步重试次数
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'memory_record'
       AND column_name = 'retry_count') = 0,
    'ALTER TABLE memory_record ADD COLUMN retry_count INT DEFAULT 0
        COMMENT ''向量同步重试次数''',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. 向量状态索引（扫描待同步记录用）
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'memory_record'
       AND index_name = 'idx_vector_status') = 0,
    'ALTER TABLE memory_record ADD INDEX idx_vector_status (vector_status)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
