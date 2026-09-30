-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- ============================================================
-- Phase8: 统计仪表盘
-- ============================================================

CREATE TABLE `article_view_daily` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `article_id` bigint(20) NOT NULL,
  `date` date NOT NULL,
  `view_count` int(11) NOT NULL DEFAULT '0',
  `like_count` int(11) NOT NULL DEFAULT '0',
  `favorite_count` int(11) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_article_date` (`article_id`, `date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章每日统计表';

CREATE TABLE `article_view_snapshot` (
  `article_id` bigint(20) NOT NULL,
  `snapshot_date` date NOT NULL,
  `view_count` int(11) NOT NULL DEFAULT '0',
  PRIMARY KEY (`article_id`, `snapshot_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章浏览量每日快照';
