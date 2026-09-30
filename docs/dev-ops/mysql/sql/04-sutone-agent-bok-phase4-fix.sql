-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- ============================================================
-- Phase4 修复：回填 article_meta.like_count / favorite_count
-- 说明：之前点赞/收藏只写 article_like/article_favorite 表，
--       article_meta.like_count/favorite_count 列为预留状态一直为 0。
--       本次修复后增量写操作会同步更新 article_meta 计数，
--       此脚本用于回填存量数据。
-- ============================================================

-- 回填 article_meta.like_count
UPDATE article_meta m
SET m.like_count = (
    SELECT COUNT(*) FROM article_like l WHERE l.article_id = m.article_id
);

-- 回填 article_meta.favorite_count
UPDATE article_meta m
SET m.favorite_count = (
    SELECT COUNT(*) FROM article_favorite f WHERE f.article_id = m.article_id
);
