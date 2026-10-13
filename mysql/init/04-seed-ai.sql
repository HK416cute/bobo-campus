-- =============================================================================
-- bobo 校园二手交易平台 —— AI 演示数据
-- 文件：mysql/init/04-seed-ai.sql
-- 说明：为演示与联调准备一份可解释的基线数据，使后台"AI 效果"面板一进去就有数，
--       而不是一片空白（空面板最容易被误判成功能没做）。
--       全部使用 DELETE + INSERT 而非 INSERT IGNORE：
--       无唯一键的表用 INSERT IGNORE 重复执行会产生重复行（本工程 sys_dict 类问题已踩过），
--       DELETE 后 INSERT 才能保证脚本可重复执行且结果唯一。
-- =============================================================================

SET NAMES utf8mb4;

-- 注意：演示数据的 user_id / target_id 引用 02-seed.sql 中已存在的用户与商品，
-- 但不加外键约束（保持与 01/02 一致的风格：约束靠应用保证）。
-- 若 02-seed.sql 的演示用户ID发生变化，这里的引用需要同步调整。

DELETE FROM `bobo_ai_suggestion_feedback` WHERE `suggested_value` LIKE '[DEMO]%';

-- -----------------------------------------------------------------------------
-- 1. 发布助手建议反馈（演示采纳率基线）
--    accepted=1 表示用户直接采用了模型建议，=0 表示用户改写后才提交。
--    采纳率 = accepted=1 的条数 / 总条数，直接由 bobo_ai_suggestion_feedback 算出。
-- -----------------------------------------------------------------------------
INSERT INTO `bobo_ai_suggestion_feedback`
  (`suggestion_type`, `target_id`, `user_id`, `accepted`, `suggested_value`, `final_value`, `log_id`, `create_time`)
VALUES
  ('publish_draft', NULL, 2, 1, '[DEMO]九成新 iPad Air 4 64G 深空灰 含原装充电器', '[DEMO]九成新 iPad Air 4 64G 深空灰 含原装充电器', NULL, NOW() - INTERVAL 9 DAY),
  ('publish_draft', NULL, 3, 1, '[DEMO]考研数学 张宇1000题 全新未拆', '[DEMO]考研数学 张宇1000题 全新未拆', NULL, NOW() - INTERVAL 8 DAY),
  ('publish_draft', NULL, 5, 0, '[DEMO]二手山地车 捷安特 ATX 660 骑行一年', '[DEMO]捷安特ATX660 山地车 27速 可小刀', NULL, NOW() - INTERVAL 7 DAY),
  ('publish_draft', NULL, 2, 1, '[DEMO]宿舍小冰箱 海尔 50L 自提', '[DEMO]宿舍小冰箱 海尔 50L 自提', NULL, NOW() - INTERVAL 6 DAY),
  ('publish_draft', NULL, 4, 1, '[DEMO]大学英语四级真题 近五年 无笔记', '[DEMO]大学英语四级真题 近五年 无笔记', NULL, NOW() - INTERVAL 5 DAY),
  ('publish_draft', NULL, 3, 0, '[DEMO]罗技鼠标 G102 电竞', '[DEMO]罗技G102 有线鼠标 九成新 带包装', NULL, NOW() - INTERVAL 4 DAY),
  ('publish_draft', NULL, 5, 1, '[DEMO]秋季外套 男 L码 只穿过两次', '[DEMO]秋季外套 男 L码 只穿过两次', NULL, NOW() - INTERVAL 3 DAY),
  ('publish_draft', NULL, 2, 1, '[DEMO]机械键盘 87键 红轴 静音', '[DEMO]机械键盘 87键 红轴 静音', NULL, NOW() - INTERVAL 2 DAY),

  ('search_query', NULL, 2, 1, '[DEMO]500元以内 山地车 当面交易', '[DEMO]500元以内 山地车 当面交易', NULL, NOW() - INTERVAL 6 DAY),
  ('search_query', NULL, 3, 1, '[DEMO]九成新以上 平板电脑 1000-2000元', '[DEMO]九成新以上 平板电脑 1000-2000元', NULL, NOW() - INTERVAL 5 DAY),
  ('search_query', NULL, 4, 0, '[DEMO]便宜的考研书', '[DEMO]考研 数学 教材 30元以内', NULL, NOW() - INTERVAL 4 DAY),
  ('search_query', NULL, 5, 1, '[DEMO]可以邮寄的 宿舍电器', '[DEMO]可以邮寄的 宿舍电器', NULL, NOW() - INTERVAL 3 DAY),
  ('search_query', NULL, 2, 1, '[DEMO]100元以下 四六级 资料', '[DEMO]100元以下 四六级 资料', NULL, NOW() - INTERVAL 2 DAY);

-- -----------------------------------------------------------------------------
-- 2. AI 调用流水（演示成本与成功率基线）
--    只写示例数据，不写真实 key 信息；token 数按 deepseek-chat 的真实量级填。
-- -----------------------------------------------------------------------------
DELETE FROM `bobo_ai_call_log` WHERE `provider` = 'demo';

INSERT INTO `bobo_ai_call_log`
  (`scene`, `user_id`, `provider`, `model`, `input_hash`, `output_hash`,
   `prompt_tokens`, `completion_tokens`, `total_tokens`, `latency_ms`,
   `status`, `degrade_reason`, `error_msg`, `estimated_cost`, `create_time`)
VALUES
  ('publish_draft', 2, 'demo', 'deepseek-chat', 'a1b2c3d4e5f60718', '11aa22bb33cc44dd', 420, 160, 580, 2140, 'success', NULL, NULL, 0.002120, NOW() - INTERVAL 9 DAY),
  ('publish_draft', 3, 'demo', 'deepseek-chat', 'b2c3d4e5f6071829', '22bb33cc44dd55ee', 398, 142, 540, 1870, 'success', NULL, NULL, 0.001932, NOW() - INTERVAL 8 DAY),
  ('publish_draft', 5, 'demo', 'deepseek-chat', 'c3d4e5f607182930', '33cc44dd55ee66ff', 455, 178, 633, 2610, 'success', NULL, NULL, 0.002334, NOW() - INTERVAL 7 DAY),
  ('publish_draft', 4, 'demo', 'deepseek-chat', NULL, NULL, NULL, NULL, NULL, 15002, 'degraded', '模型调用超时，已使用规则结果', NULL, 0.000000, NOW() - INTERVAL 6 DAY),
  ('publish_draft', 2, 'demo', 'deepseek-chat', 'd4e5f60718293041', '44dd55ee66ff7700', 402, 151, 553, 1980, 'success', NULL, NULL, 0.002012, NOW() - INTERVAL 5 DAY),
  ('search_parse', 3, 'demo', 'deepseek-chat', 'e5f6071829304152', '55ee66ff77008811', 310, 96, 406, 1420, 'success', NULL, NULL, 0.001388, NOW() - INTERVAL 4 DAY),
  ('search_parse', 4, 'demo', 'deepseek-chat', 'f607182930415263', '66ff770088119922', 288, 84, 372, 1290, 'success', NULL, NULL, 0.001248, NOW() - INTERVAL 3 DAY),
  ('search_rerank', 5, 'demo', 'deepseek-chat', '0718293041526374', '770088119922aa33', 980, 210, 1190, 3260, 'success', NULL, NULL, 0.003640, NOW() - INTERVAL 2 DAY),
  ('moderation', 2, 'demo', 'deepseek-chat', '1829304152637485', '88009922aa33bb44', 260, 72, 332, 1150, 'success', NULL, NULL, 0.001096, NOW() - INTERVAL 2 DAY),
  ('moderation', NULL, 'demo', 'deepseek-chat', NULL, NULL, NULL, NULL, NULL, 0, 'degraded', '命中本地关键词规则，未调用模型', NULL, 0.000000, NOW() - INTERVAL 1 DAY),
  ('admin_report', 1, 'demo', 'deepseek-chat', '2930415263748596', '9900aa33bb44cc55', 1580, 420, 2000, 5240, 'success', NULL, NULL, 0.006520, NOW() - INTERVAL 1 DAY);
