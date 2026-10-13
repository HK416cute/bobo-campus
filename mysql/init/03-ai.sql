-- =============================================================================
-- bobo 校园二手交易平台 —— AI 能力表结构
-- 文件：mysql/init/03-ai.sql
-- 说明：与 01-schema.sql / 02-seed.sql 同批次由 MySQL 官方镜像的
--       /docker-entrypoint-initdb.d 按文件名顺序自动执行（01 → 02 → 03 → 04）。
--       字符集与排序规则显式写成 utf8mb4 + utf8mb4_unicode_ci：
--       不带 COLLATE 会继承服务端字符集默认排序规则（MySQL 8 为 utf8mb4_0900_ai_ci），
--       与 01/02 建立的 bobo_* 表 JOIN 时会报 1267 Illegal mix of collations。
--       本组表刻意不加 del_flag：AI 流水/反馈/审核结论属于审计数据，只追加不软删，
--       要清理就按时间分区归档，避免"逻辑删除"把统计口径搞脏。
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 1. bobo_ai_call_log —— AI 调用流水（可观测性 + 成本统计的唯一来源）
--    · 只存输入/输出摘要（SHA-256 前 16 位），不存正文，避免用户内容沉到日志表
--    · latency_ms 是各场景耗时/分位数的来源；status 区分成功/降级/失败
--    · estimated_cost 由 bobo.ai.pricing 单价折算，回答"AI 花了多少钱"
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_ai_call_log`;
CREATE TABLE `bobo_ai_call_log` (
  `log_id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '流水ID',
  `scene`             VARCHAR(32)   NOT NULL                COMMENT '场景：publish_draft/search_parse/search_rerank/moderation/admin_report/admin_ask',
  `user_id`           BIGINT                 DEFAULT NULL   COMMENT '触发用户ID，匿名或后台任务为空',
  `provider`          VARCHAR(32)            DEFAULT NULL   COMMENT '调用器实现：spring-ai-deepseek/noop',
  `model`             VARCHAR(64)            DEFAULT NULL   COMMENT '模型名：deepseek-chat/deepseek-reasoner',
  `input_hash`        VARCHAR(32)            DEFAULT NULL   COMMENT '输入摘要（SHA-256 前16位），用于去重与缓存判定',
  `output_hash`       VARCHAR(32)            DEFAULT NULL   COMMENT '输出摘要，用于识别重复建议',
  `prompt_tokens`     INT                    DEFAULT NULL   COMMENT '输入token数，供应商未返回则为NULL（不记0，避免成本失真）',
  `completion_tokens` INT                    DEFAULT NULL   COMMENT '输出token数',
  `total_tokens`      INT                    DEFAULT NULL   COMMENT '总token数',
  `latency_ms`        BIGINT                 DEFAULT NULL   COMMENT '端到端耗时（毫秒）',
  `status`            VARCHAR(16)   NOT NULL                COMMENT 'success 成功 / degraded 降级为规则 / failed 失败',
  `degrade_reason`    VARCHAR(255)           DEFAULT NULL   COMMENT '降级或限流原因，可直接展示给运维',
  `error_msg`         VARCHAR(500)           DEFAULT NULL   COMMENT '失败异常摘要',
  `estimated_cost`    DECIMAL(12,6)          DEFAULT NULL   COMMENT '估算花费（元），按 bobo.ai.pricing 折算',
  `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`log_id`),
  KEY `idx_ai_log_scene_time` (`scene`, `create_time`),
  KEY `idx_ai_log_user_time` (`user_id`, `create_time`),
  KEY `idx_ai_log_input_hash` (`input_hash`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 调用流水表';

-- -----------------------------------------------------------------------------
-- 2. bobo_ai_suggestion_feedback —— AI 建议与采纳反馈
--    · 采集"建议了什么 / 用户最终用了什么"，是采纳率与建议偏差的唯一事实来源
--    · 没有这张表，简历上的"采纳率 X%"就只能靠估
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_ai_suggestion_feedback`;
CREATE TABLE `bobo_ai_suggestion_feedback` (
  `feedback_id`     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
  `suggestion_type` VARCHAR(32)  NOT NULL                COMMENT '建议类型：publish_draft 发布草稿 / search_query 检索改写',
  `target_id`       BIGINT                DEFAULT NULL   COMMENT '归属对象ID（商品/订单等），可为空',
  `user_id`         BIGINT                DEFAULT NULL   COMMENT '用户ID',
  `accepted`        TINYINT      NOT NULL DEFAULT 0      COMMENT '是否采纳：1 采纳 / 0 未采纳',
  `suggested_value` VARCHAR(500)          DEFAULT NULL   COMMENT '模型/规则给出的建议值（截断存储）',
  `final_value`     VARCHAR(500)          DEFAULT NULL   COMMENT '用户最终提交的值，与建议值对比即为偏差',
  `log_id`          BIGINT                DEFAULT NULL   COMMENT '关联的调用流水ID，用于回溯是哪次模型输出',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`feedback_id`),
  KEY `idx_ai_feedback_type_accepted` (`suggestion_type`, `accepted`),
  KEY `idx_ai_feedback_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 建议反馈表';

-- -----------------------------------------------------------------------------
-- 3. bobo_ai_moderation —— AI 内容审核结论
--    · 与 bobo_report（人工举报，事后）互补：本表是事前风险判定
--    · content_hash 为幂等键：同一段内容不重复调模型，结论也保持稳定
--    · decision_source 区分 rule/model，用于评估"模型相比关键词规则多抓到了什么"
--    · target_id 不加外键：审核可能在对象落库之前发生（发布前预审）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_ai_moderation`;
CREATE TABLE `bobo_ai_moderation` (
  `moderation_id`   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '审核ID',
  `target_type`     VARCHAR(16)  NOT NULL                COMMENT '对象类型：item 商品 / review 评价 / message 私信',
  `target_id`       BIGINT                DEFAULT NULL   COMMENT '对象ID，发布前预审时为空',
  `content_hash`    VARCHAR(32)  NOT NULL                COMMENT '内容摘要，幂等键与缓存键',
  `risk_level`      TINYINT      NOT NULL DEFAULT 0      COMMENT '风险分级：0 低 / 1 中 / 2 高',
  `risk_categories` VARCHAR(255)          DEFAULT NULL   COMMENT '风险类目，逗号分隔（违禁物品/虚假描述/联系方式引流等）',
  `reason`          VARCHAR(500)          DEFAULT NULL   COMMENT '判定理由，给管理员看的可解释说明',
  `auto_reject`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否建议直接拦截：1 是 / 0 否（最终仍由管理员决定）',
  `decision_source` VARCHAR(16)  NOT NULL DEFAULT 'rule' COMMENT '结论来源：rule 关键词规则 / model 模型判定',
  `log_id`          BIGINT                DEFAULT NULL   COMMENT '关联的模型调用流水ID',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`moderation_id`),
  KEY `idx_ai_moderation_target` (`target_type`, `target_id`),
  KEY `idx_ai_moderation_hash` (`content_hash`),
  KEY `idx_ai_moderation_risk` (`risk_level`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 内容审核表';
