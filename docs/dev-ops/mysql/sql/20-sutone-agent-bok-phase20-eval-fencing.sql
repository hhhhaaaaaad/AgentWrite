-- 显式声明目标库：docker-entrypoint-initdb.d 调用 mysql 客户端时不带默认库，
-- 缺此行会以 ERROR 1046 失败，并中断整轮初始化（容器退出，后续脚本全部不执行）。
USE `sutone_agent_bok`;

-- ============================================================
-- Phase20: 评测 fencing 权威态（zombie 防护）
-- 说明：per-config 命名空间（eval_user_id）的权威 fencing 状态。
--       fencing_version 单调递增；active_run_id 标识当前持有 fencing 的 run。
--       acquire 事务内 SELECT ... FOR UPDATE 读改写；release 按 run_id CAS 清空。
-- ============================================================

CREATE TABLE `eval_fencing` (
  `eval_user_id` bigint(20) NOT NULL COMMENT '评测命名空间（per-config 派生）',
  `fencing_version` bigint(20) NOT NULL DEFAULT 0 COMMENT 'fencing 权威版本（单调递增）',
  `active_run_id` varchar(64) DEFAULT NULL COMMENT '当前持有 fencing 的 run id',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`eval_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测 fencing 权威态（zombie 防护）';
