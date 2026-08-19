-- 本地提示词备份表 — Langfuse 不可用时的数据库兜底
CREATE TABLE IF NOT EXISTS `local_prompt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `prompt_key` varchar(200) NOT NULL COMMENT '提示词键名（如 agent.tool.queryShop）',
  `content` text NOT NULL COMMENT '提示词内容',
  `label` varchar(50) DEFAULT 'production' COMMENT '标签（如 production）',
  `source` varchar(50) DEFAULT 'langfuse' COMMENT '来源（langfuse/manual）',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_label` (`prompt_key`, `label`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='本地提示词备份';
