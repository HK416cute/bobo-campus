-- 一次性修复：把订单里遗留的占位图路径（/img/cover-*.svg）归一化为实际存在的
-- /img/img-*.svg。完整映射与说明见 mysql/init/02-seed.sql 的「封面路径归一化」。
-- 用法：docker compose exec -T bobo-mysql mysql -ubobo -p<pwd> bobo < fix-cover-path.sql
SET NAMES utf8mb4;

UPDATE `bobo_order` SET `item_cover` = CASE `item_cover`
    WHEN '/img/cover-tablet.svg'   THEN '/img/img-tablet.svg'
    WHEN '/img/cover-mouse.svg'    THEN '/img/img-computer.svg'
    WHEN '/img/cover-keyboard.svg' THEN '/img/img-computer.svg'
    WHEN '/img/cover-earbuds.svg'  THEN '/img/img-tablet.svg'
    WHEN '/img/cover-book.svg'     THEN '/img/img-book.svg'
    WHEN '/img/cover-book2.svg'    THEN '/img/img-book.svg'
    WHEN '/img/cover-bike.svg'     THEN '/img/img-sports.svg'
    WHEN '/img/cover-shoes.svg'    THEN '/img/img-clothing.svg'
    WHEN '/img/cover-pot.svg'      THEN '/img/img-life.svg'
    WHEN '/img/cover-fridge.svg'   THEN '/img/img-life.svg'
    WHEN '/img/cover-guitar.svg'   THEN '/img/img-other.svg'
    WHEN '/img/cover-felt.svg'     THEN '/img/img-other.svg'
    ELSE `item_cover`
  END
 WHERE `item_cover` LIKE '/img/cover-%';

SELECT CONCAT('剩余 cover-* 快照：', COUNT(*)) AS result FROM `bobo_order` WHERE `item_cover` LIKE '/img/cover-%';
SELECT `order_id`, `item_cover` FROM `bobo_order` ORDER BY `order_id`;
