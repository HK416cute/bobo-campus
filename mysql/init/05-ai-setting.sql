-- =============================================================================
-- bobo 校园二手交易平台 —— AI 运行配置表
-- 文件：mysql/init/05-ai-setting.sql
--
-- 作用：让 API Key / 模型 / 限流 / 单价等配置可以在**管理后台**修改并立即生效，
--       而不是写死在环境变量里（环境变量只在容器启动时读一次，改完必须重启）。
--
-- 设计要点：
--   1) 单行表：setting_id 恒为 1，用主键约束避免出现"两份配置哪个生效"的歧义。
--   2) 本表是**运行期覆盖**，不是替代：字段为 NULL 或本表无行时，
--      回落到 application.yml / 环境变量（bobo.ai.*）。因此没进过后台配置页的
--      部署行为与以前完全一致，升级零成本。
--   3) 不写字面量 api_key：预置行为空串表示"未配置"，密钥一律由管理员在后台填写。
--      加密说明见 AiSetting.java 的类注释（本表存明文，防线做在接口与日志侧）。
--   4) temperature 用整数存百分之一（100 = 1.00），避免浮点在库里产生精度噪音。
--
-- 已有部署升级方式（初始化脚本只在数据目录为空时执行一次）：
--   docker compose exec -T bobo-mysql mysql -ubobo -p"$PW" bobo < mysql/init/05-ai-setting.sql
-- 或直接执行文件末尾的 INSERT（建表语句是幂等的）。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `bobo_ai_setting` (
  `setting_id`         BIGINT       NOT NULL                       COMMENT '固定为 1（单行配置表）',
  `enabled`            TINYINT      NOT NULL DEFAULT 1             COMMENT '总开关：1 开 / 0 关（关掉后全部场景走规则实现）',
  `api_key`            VARCHAR(255)          DEFAULT ''           COMMENT '模型 API Key，空 = 未配置（走规则降级）',
  `base_url`           VARCHAR(255)          DEFAULT NULL         COMMENT '服务地址，空则用 bobo.ai.base-url',
  `model`              VARCHAR(100)          DEFAULT NULL         COMMENT '模型名，空则用 bobo.ai.model',
  `timeout_ms`         BIGINT                DEFAULT NULL         COMMENT '单次调用超时（毫秒），空则用 bobo.ai.timeout-ms',
  `max_attempts`       INT                   DEFAULT NULL         COMMENT '失败重试次数',
  `temperature_x100`   INT                   DEFAULT NULL         COMMENT '采样温度×100（100 = 1.00）',
  `global_qps`         INT                   DEFAULT NULL         COMMENT '全局限流 QPS',
  `user_qps`           INT                   DEFAULT NULL         COMMENT '单用户限流 QPS',
  `daily_quota`        INT                   DEFAULT NULL         COMMENT '单用户每日调用配额',
  `price_input`        DECIMAL(10,4)         DEFAULT NULL         COMMENT '输入 token 单价（元/百万）',
  `price_output`       DECIMAL(10,4)         DEFAULT NULL         COMMENT '输出 token 单价（元/百万）',
  `update_by`          BIGINT                DEFAULT NULL         COMMENT '最后修改人 user_id',
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`setting_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 运行配置（单行）';

-- 预置一行：api_key 留空，其余留 NULL 表示"沿用环境变量/yml 的默认值"。
-- 用 INSERT IGNORE（而非 DELETE + INSERT）刻意保留管理员已保存过的配置：
-- 这个脚本可能被运维在已有库上重复执行。
INSERT IGNORE INTO `bobo_ai_setting`
  (`setting_id`, `enabled`, `api_key`)
VALUES
  (1, 1, '');
