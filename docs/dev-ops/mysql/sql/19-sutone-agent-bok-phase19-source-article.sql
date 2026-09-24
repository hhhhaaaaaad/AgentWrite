-- ============================================================
-- Phase19: 记忆来源文章摘要注入（方案 A）
-- 新增：来源文章 id / 标题 / 摘要（快照式冗余）
-- 说明：注入时每条记忆附带「标题 + 一句话摘要」建立跨文章关联；
--       摘要为截取原文前 100 字（去换行），零 LLM 成本；
--       全部字段可空，不影响存量行，可灰度执行。
-- ============================================================

ALTER TABLE memory_record
    ADD COLUMN `source_article_id`      BIGINT       DEFAULT NULL COMMENT '来源文章 id（该记忆从哪篇文章抽取）',
    ADD COLUMN `source_article_title`   VARCHAR(255) DEFAULT NULL COMMENT '来源文章标题（快照）',
    ADD COLUMN `source_article_summary` VARCHAR(512) DEFAULT NULL COMMENT '来源文章一句话摘要（截取原文前 100 字，快照）';
