-- =============================================================================
-- 初始化业务数据（保留管理员与指定账号，清空商品/订单/交易/互动/通知/AI 流水）
-- 文件：mysql/fix/reset-business-data.sql
--
-- 用法（Docker）：
--   docker compose exec -T bobo-mysql sh -c 'mysql -ubobo -p"$MYSQL_PASSWORD" \
--     --default-character-set=utf8mb4 bobo' < mysql/fix/reset-business-data.sql
--
-- 保留账号：username = admin（昵称 bobo管理员）、username = 2330090307（昵称 HK416）
-- 保留：分类、AI 配置、知识库文档/切片
-- 清空：商品与图片、订单、评价、举报、私信、收藏、地址、通知、
--       撮合单/消息、定价评估、AI 调用流水/反馈/审核结论
--
-- 注意：这是**业务数据重置**，不是数据库重建；执行前请先备份。
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

START TRANSACTION;

-- 1. 智能撮合 / 定价 / AI 审计数据
DELETE FROM `bobo_match_message`;
DELETE FROM `bobo_match_request`;
DELETE FROM `bobo_price_estimate`;
DELETE FROM `bobo_ai_call_log`;
DELETE FROM `bobo_ai_suggestion_feedback`;
DELETE FROM `bobo_ai_moderation`;

-- 2. 交易与互动数据
DELETE FROM `bobo_order`;
DELETE FROM `bobo_review`;
DELETE FROM `bobo_report`;
DELETE FROM `bobo_conversation`;
DELETE FROM `bobo_message`;
DELETE FROM `bobo_favorite`;
DELETE FROM `bobo_address`;
DELETE FROM `bobo_notice`;

-- 3. 商品与图片
DELETE FROM `bobo_item_image`;
DELETE FROM `bobo_item`;

-- 4. 仅保留两个账号（按 username 判断，避免不同环境自增 ID 不同）
DELETE FROM `bobo_user`
 WHERE `username` NOT IN ('admin', '2330090307');

COMMIT;

-- 5. 重置业务表自增（bobo_user 不重置：保留账号的 ID 保持不变）
ALTER TABLE `bobo_match_message` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_match_request` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_price_estimate` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_ai_call_log` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_ai_suggestion_feedback` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_ai_moderation` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_order` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_review` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_report` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_conversation` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_message` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_favorite` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_address` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_notice` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_item_image` AUTO_INCREMENT = 1;
ALTER TABLE `bobo_item` AUTO_INCREMENT = 1;

SET FOREIGN_KEY_CHECKS = 1;
