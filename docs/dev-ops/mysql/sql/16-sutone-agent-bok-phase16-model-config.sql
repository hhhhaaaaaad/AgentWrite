-- V3: 用户自定义模型配置（多租户架构）
CREATE TABLE `user_model_config` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         BIGINT       NOT NULL                COMMENT '用户ID',
  `config_name`     VARCHAR(64)  NOT NULL                COMMENT '配置名称，如"我的 GPT-4 配置"',
  `provider`        VARCHAR(32)  NOT NULL DEFAULT 'openai' COMMENT '提供商标识：openai/deepseek/custom',
  `base_url`        VARCHAR(512) NOT NULL                COMMENT 'API baseUrl',
  `api_key_cipher`  VARCHAR(1024) NOT NULL               COMMENT 'AES-GCM 加密后的 API Key（Base64）',
  `model_name`      VARCHAR(128) NOT NULL                COMMENT '模型名称，如 gpt-4o / deepseek-chat',
  `completions_path` VARCHAR(256) NOT NULL DEFAULT '/v1/chat/completions' COMMENT '补全路径',
  `is_default`      TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否设为用户默认配置',
  `is_enabled`      TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
  `key_version`     INT          NOT NULL DEFAULT 1      COMMENT '加密密钥版本号（用于密钥轮换）',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_user_default` (`user_id`, `is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户自定义模型配置';

-- ai_task 表追加 model_config_id 字段（提交时快照）
ALTER TABLE `ai_task`
  ADD COLUMN `model_config_id` BIGINT NULL COMMENT '执行时使用的用户模型配置ID，NULL 表示使用系统默认' AFTER `user_id`;
