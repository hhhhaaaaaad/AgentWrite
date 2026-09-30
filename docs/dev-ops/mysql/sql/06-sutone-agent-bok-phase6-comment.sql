-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- ============================================================
-- Phase6: 评论系统
-- ============================================================

CREATE TABLE `article_comment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  `article_id` bigint(20) NOT NULL COMMENT '文章ID',
  `user_id` bigint(20) NOT NULL COMMENT '评论用户ID',
  `parent_id` bigint(20) DEFAULT NULL COMMENT '父评论ID(NULL=一级评论)',
  `content` text NOT NULL COMMENT '评论内容',
  `like_count` int(11) NOT NULL DEFAULT '0' COMMENT '评论点赞数(Redis为准，DB备份)',
  `is_deleted` tinyint(4) NOT NULL DEFAULT '0' COMMENT '逻辑删除 0-否 1-是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_article_time` (`article_id`, `create_time`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章评论表';

CREATE TABLE `comment_like` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `comment_id` bigint(20) NOT NULL COMMENT '评论ID',
  `user_id` bigint(20) NOT NULL COMMENT '点赞用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_user` (`comment_id`, `user_id`),
  KEY `idx_comment_id` (`comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论点赞记录';
