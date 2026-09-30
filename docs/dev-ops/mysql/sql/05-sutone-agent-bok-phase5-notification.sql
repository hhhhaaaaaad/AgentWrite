-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- ============================================================
-- Phase5: 通知系统
-- ============================================================

CREATE TABLE `notification` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '接收者用户ID',
  `type` varchar(32) NOT NULL COMMENT '通知类型: NEW_COMMENT|NEW_COMMENT_LIKE|NEW_LIKE|NEW_FOLLOW|NEW_ARTICLE',
  `sender_id` bigint(20) DEFAULT NULL COMMENT '触发者用户ID',
  `ref_id` bigint(20) DEFAULT NULL COMMENT '关联实体ID（文章ID/评论ID）',
  `content` varchar(256) DEFAULT NULL COMMENT '通知摘要（可直接展示）',
  `is_read` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否已读 0-否 1-是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_read_time` (`user_id`, `is_read`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知表';
