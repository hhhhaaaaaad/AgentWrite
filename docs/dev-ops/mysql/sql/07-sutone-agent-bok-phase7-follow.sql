-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- ============================================================
-- Phase7: 关注/订阅系统
-- ============================================================

CREATE TABLE `user_follow` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `follower_id` bigint(20) NOT NULL COMMENT '关注者',
  `followee_id` bigint(20) NOT NULL COMMENT '被关注者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follower_followee` (`follower_id`, `followee_id`),
  KEY `idx_followee_id` (`followee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注关系表';
