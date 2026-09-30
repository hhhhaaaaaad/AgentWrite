-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- ============================================================
-- Phase9: Agent 记忆系统
-- ============================================================

-- 1. 记忆主表
CREATE TABLE `memory_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记忆ID',
  `user_id` bigint(20) NOT NULL COMMENT '所属用户',
  `type` varchar(32) NOT NULL COMMENT '类型: fact/preference/knowledge/event',
  `content` varchar(512) NOT NULL COMMENT '记忆内容',
  `content_hash` varchar(64) NOT NULL COMMENT 'MD5(content)，去重',
  `content_tokenized` varchar(1024) DEFAULT NULL COMMENT '分词后内容（供 BM25 FULLTEXT 检索）',
  `source_session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
  `importance` double NOT NULL DEFAULT '0.5' COMMENT '重要性权重 0-1',
  `access_count` int(11) NOT NULL DEFAULT '0' COMMENT '被检索命中次数',
  `last_accessed_at` datetime DEFAULT NULL COMMENT '最近被检索时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(4) NOT NULL DEFAULT '0' COMMENT '逻辑删除 0=正常 1=删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_hash` (`user_id`, `content_hash`),
  KEY `idx_user_type` (`user_id`, `type`),
  FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent长期记忆表';

-- 2. 记忆变更历史表
CREATE TABLE `memory_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `memory_id` bigint(20) NOT NULL COMMENT '关联 memory_record.id',
  `session_id` varchar(64) NOT NULL COMMENT '来源会话',
  `old_content` text COMMENT '变更前内容',
  `new_content` text COMMENT '变更后内容',
  `event` varchar(16) NOT NULL COMMENT 'ADD / UPDATE / DELETE',
  `role` varchar(16) DEFAULT NULL COMMENT 'user / agent',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_memory_id` (`memory_id`),
  KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆变更历史表';

-- 3. 对话消息持久化表
CREATE TABLE `chat_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `agent_id` varchar(32) NOT NULL COMMENT '智能体ID',
  `role` varchar(16) NOT NULL COMMENT 'user / assistant',
  `content` text NOT NULL COMMENT '消息内容',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_time` (`session_id`, `create_time`),
  KEY `idx_user_agent` (`user_id`, `agent_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息持久化表';
