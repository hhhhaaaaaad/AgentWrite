-- Phase 15: 修复 chat_message.content 长度不足导致的 Data too long 报错
-- 背景：AI 写作快捷操作会把 analyst+generator+reviewer 多 Agent 的完整输出拼接后作为
--       一条 assistant 消息持久化，reviewer 改为结构化 JSON 块后体量更大，
--       原 TEXT 类型（上限 65535 字节，utf8mb4 下万字中文文章的多段拼接会超限）不够用。
-- 处理：将 content 提升为 LONGTEXT（上限约 4GB），避免截断插入失败。

ALTER TABLE `chat_message`
    MODIFY COLUMN `content` LONGTEXT NOT NULL COMMENT '消息内容';
