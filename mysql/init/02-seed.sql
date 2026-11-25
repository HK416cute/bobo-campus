-- =============================================================================
-- bobo 校园二手交易平台 —— 生产环境初始化数据（干净库）
-- 文件：mysql/init/02-seed.sql
--
-- 与旧版 demo seed 的区别：
--   · 只写入 16 个分类 + 2 个账号（admin / HK416）
--   · 不写商品、订单、评价、举报、公告、私信、地址、AI 演示流水
--   · 需要完整演示数据时，用 mysql/demo/02-seed-demo.sql **替代**本文件（二选一）；
--     mysql/demo/04-seed-ai-demo.sql 可选。它们不在 docker-entrypoint-initdb.d
--     目录内，不会在全新部署时自动执行。
--
-- 账号说明：
--   admin（昵称 bobo管理员）: 管理员，初始密码 password，上线后立即修改
--   HK416（username 2330090307）: 保留账号，初始密码 password（与 admin 相同，上线后立即修改）
--
-- 本文件的数据来自当前已初始化的干净库（mysqldump），因此分类 ID 与账号 ID
-- 与当前环境一致；如手工编辑，请保持 password 散列格式为：
--   pbkdf2$sha256$120000$<saltBase64>$<hashBase64>
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

/*!40000 ALTER TABLE `bobo_category` DISABLE KEYS */;
INSERT INTO `bobo_category` (`category_id`, `parent_id`, `name`, `icon`, `sort`, `status`, `del_flag`, `create_time`, `update_time`) VALUES (1,0,'数码产品','📱',1,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(2,0,'电脑办公','💻',2,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(3,0,'图书教材','📚',3,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(4,0,'服饰鞋包','👕',4,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(5,0,'运动户外','🏀',5,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(6,0,'生活用品','🧺',6,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(7,0,'美妆个护','💄',7,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(8,0,'零食饮品','🍜',8,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(9,0,'乐器器材','🎸',9,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(10,0,'文具手作','✏️',10,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(11,0,'宿舍家电','🔌',11,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(12,0,'代步工具','🛴',12,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(13,0,'票券卡类','🎫',13,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(14,0,'宠物用品','🐱',14,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(15,0,'手办玩具','🧸',15,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48'),(16,0,'其他闲置','📦',16,0,0,'2026-10-12 22:28:48','2026-10-12 22:28:48');
/*!40000 ALTER TABLE `bobo_category` ENABLE KEYS */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

/*!40000 ALTER TABLE `bobo_user` DISABLE KEYS */;
INSERT INTO `bobo_user` (`user_id`, `username`, `password`, `nickname`, `avatar`, `phone`, `email`, `campus`, `college`, `grade`, `student_no`, `bio`, `credit_score`, `role`, `verify_status`, `status`, `del_flag`, `login_ip`, `login_time`, `create_time`, `update_time`) VALUES (6,'2330090307','pbkdf2$sha256$120000$ASNFZ4mrze8BI0VniavN7w==$ejckC1uxnzp3jUuIyTVLK3z+9mPWIhCZNnHnoqreSUs=','HK416',NULL,NULL,NULL,'威海','计算机学院','2023',NULL,NULL,101,'user',0,0,0,'172.19.0.1','2026-11-25 12:42:30','2026-10-12 22:39:32','2026-11-25 12:42:30'),(1,'admin','pbkdf2$sha256$120000$ASNFZ4mrze8BI0VniavN7w==$ejckC1uxnzp3jUuIyTVLK3z+9mPWIhCZNnHnoqreSUs=','bobo管理员',NULL,'13800000001','admin@bobo.edu','中心校区','信息化中心','2020','ADMIN001','维护校园二手交易秩序',100,'admin',2,0,0,'172.19.0.1','2026-11-25 12:37:59','2026-01-01 09:00:00','2026-11-25 12:37:59');
/*!40000 ALTER TABLE `bobo_user` ENABLE KEYS */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

SET FOREIGN_KEY_CHECKS = 1;
