-- =============================================================================
-- bobo 校园二手交易平台 —— 搜索关键词日志（动态热门搜索）
-- 文件：mysql/init/07-search-log.sql
--
-- 作用：
--   记录前台商品搜索第一页的关键词，用「最近 30 天 × 出现次数」统计首页热门词。
--   只存关键词、用户ID、来源与时间，不存搜索结果，也不存 IP/UA。
--
-- 已有部署升级：
--   docker compose exec -T bobo-mysql sh -c 'mysql -ubobo -p"$MYSQL_PASSWORD" \
--     --default-character-set=utf8mb4 bobo' < mysql/init/07-search-log.sql
--
-- 说明：
--   · 没有日志时 HotWordService 回退到静态默认词（考研资料/蓝牙耳机等）；
--   · 表不存在时搜索仍正常，只是热门词走默认值；
--   · 每次搜索第一页写一行，翻页不重复记录，避免刷榜。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `bobo_search_log` (
  `log_id`      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `keyword`     VARCHAR(64) NOT NULL                COMMENT '归一化后的搜索关键词',
  `user_id`     BIGINT               DEFAULT NULL   COMMENT '搜索用户ID，匿名为空',
  `source`      VARCHAR(16) NOT NULL DEFAULT 'search' COMMENT '来源：search 普通搜索 / nl 自然语言搜索',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '搜索时间',
  PRIMARY KEY (`log_id`),
  KEY `idx_search_log_time` (`create_time`),
  KEY `idx_search_log_keyword` (`keyword`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '搜索关键词日志（热门搜索统计）';
