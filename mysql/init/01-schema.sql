-- =============================================================================
-- bobo 校园二手交易平台 —— 数据库结构初始化
-- 文件：mysql/init/01-schema.sql
-- 说明：容器首次启动时由 MySQL 官方镜像的 /docker-entrypoint-initdb.d 自动执行。
--       字符集统一 utf8mb4 + utf8mb4_unicode_ci（MySQL 8 下不会退化成
--       utf8mb4_0900_ai_ci，避免与其它脚本 JOIN 时出现 1267 排序规则混用）。
--       金额统一 DECIMAL(10,2)，禁用 FLOAT/DOUBLE。
--       逻辑删除统一 del_flag（0 正常 / 2 删除），与 MyBatis-Plus 全局配置对齐。
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 1. bobo_user 用户（买家 / 卖家 / 管理员共用一张表，用 role 区分）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_user`;
CREATE TABLE `bobo_user` (
  `user_id`       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`      VARCHAR(64)  NOT NULL                COMMENT '登录账号（学号/手机号）',
  `password`      VARCHAR(100) NOT NULL                COMMENT '密码散列（PBKDF2-HMAC-SHA256，格式见 PasswordEncoder）',
  `nickname`      VARCHAR(64)  NOT NULL                COMMENT '昵称',
  `avatar`        VARCHAR(255)          DEFAULT NULL   COMMENT '头像URL',
  `phone`         VARCHAR(20)           DEFAULT NULL   COMMENT '手机号',
  `email`         VARCHAR(100)          DEFAULT NULL   COMMENT '邮箱',
  `campus`        VARCHAR(64)           DEFAULT NULL   COMMENT '所在校区',
  `college`       VARCHAR(64)           DEFAULT NULL   COMMENT '学院',
  `grade`         VARCHAR(16)           DEFAULT NULL   COMMENT '年级，如 2023',
  `student_no`    VARCHAR(32)           DEFAULT NULL   COMMENT '学号',
  `bio`           VARCHAR(255)          DEFAULT NULL   COMMENT '个性签名',
  `credit_score`  INT          NOT NULL DEFAULT 100    COMMENT '信用分，初始 100',
  `role`          VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT '角色：user 普通用户 / admin 管理员',
  `verify_status` TINYINT      NOT NULL DEFAULT 0      COMMENT '学生认证：0 未认证 / 1 认证中 / 2 已认证 / 3 已驳回',
  `status`        TINYINT      NOT NULL DEFAULT 0      COMMENT '账号状态：0 正常 / 1 封禁',
  `del_flag`      TINYINT      NOT NULL DEFAULT 0      COMMENT '删除标志：0 存在 / 2 删除',
  `login_ip`      VARCHAR(64)           DEFAULT NULL   COMMENT '最后登录IP',
  `login_time`    DATETIME              DEFAULT NULL   COMMENT '最后登录时间',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_user_username` (`username`),
  KEY `idx_user_role_status` (`role`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表';

-- -----------------------------------------------------------------------------
-- 2. bobo_category 商品分类
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_category`;
CREATE TABLE `bobo_category` (
  `category_id` BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `parent_id`   BIGINT      NOT NULL DEFAULT 0      COMMENT '父分类ID，0 为顶级',
  `name`        VARCHAR(32) NOT NULL                COMMENT '分类名称',
  `icon`        VARCHAR(64)          DEFAULT NULL   COMMENT '图标（emoji 或图标名）',
  `sort`        INT         NOT NULL DEFAULT 0      COMMENT '排序，越小越前',
  `status`      TINYINT     NOT NULL DEFAULT 0      COMMENT '状态：0 启用 / 1 停用',
  `del_flag`    TINYINT     NOT NULL DEFAULT 0      COMMENT '删除标志：0 存在 / 2 删除',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`category_id`),
  KEY `idx_category_parent` (`parent_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品分类表';

-- -----------------------------------------------------------------------------
-- 3. bobo_item 商品（闲置宝贝）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_item`;
CREATE TABLE `bobo_item` (
  `item_id`       BIGINT        NOT NULL AUTO_INCREMENT COMMENT '商品ID',
  `title`         VARCHAR(100)  NOT NULL                COMMENT '标题',
  `description`   TEXT                                  COMMENT '描述',
  `price`         DECIMAL(10,2) NOT NULL                COMMENT '售价',
  `original_price` DECIMAL(10,2)         DEFAULT NULL   COMMENT '原价，用于展示折扣',
  `category_id`   BIGINT        NOT NULL                COMMENT '分类ID',
  `seller_id`     BIGINT        NOT NULL                COMMENT '卖家用户ID',
  `cover`         VARCHAR(255)           DEFAULT NULL   COMMENT '封面图URL',
  `condition_level` VARCHAR(16) NOT NULL DEFAULT '95新'  COMMENT '成色：全新 / 99新 / 95新 / 9成新 / 8成新 / 7成新及以下',
  `trade_type`    TINYINT       NOT NULL DEFAULT 0      COMMENT '交易方式：0 都支持 / 1 仅当面交易 / 2 仅邮寄',
  `campus`        VARCHAR(64)            DEFAULT NULL   COMMENT '交易校区',
  `location`      VARCHAR(128)           DEFAULT NULL   COMMENT '交易地点，如 三食堂门口',
  `status`        TINYINT       NOT NULL DEFAULT 0      COMMENT '状态：0 待审核 / 1 在售 / 2 已售 / 3 已下架 / 4 审核驳回',
  `reject_reason` VARCHAR(255)           DEFAULT NULL   COMMENT '审核驳回原因',
  `view_count`    INT           NOT NULL DEFAULT 0      COMMENT '浏览量',
  `want_count`    INT           NOT NULL DEFAULT 0      COMMENT '想要（收藏）数',
  `audit_time`    DATETIME               DEFAULT NULL   COMMENT '审核时间',
  `audit_by`      BIGINT                 DEFAULT NULL   COMMENT '审核人用户ID',
  `sold_time`     DATETIME               DEFAULT NULL   COMMENT '售出时间',
  `del_flag`      TINYINT       NOT NULL DEFAULT 0      COMMENT '删除标志：0 存在 / 2 删除',
  `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`item_id`),
  KEY `idx_item_status_time` (`status`, `create_time`),
  KEY `idx_item_category` (`category_id`, `status`),
  KEY `idx_item_seller` (`seller_id`, `status`),
  KEY `idx_item_title` (`title`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品表';

-- -----------------------------------------------------------------------------
-- 4. bobo_item_image 商品图片（一个商品多张图）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_item_image`;
CREATE TABLE `bobo_item_image` (
  `image_id`    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '图片ID',
  `item_id`     BIGINT       NOT NULL                COMMENT '商品ID',
  `url`         VARCHAR(255) NOT NULL                COMMENT '图片URL',
  `sort`        INT          NOT NULL DEFAULT 0      COMMENT '排序，0 为封面',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`image_id`),
  KEY `idx_item_image_item` (`item_id`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品图片表';

-- -----------------------------------------------------------------------------
-- 5. bobo_favorite 想要 / 收藏
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_favorite`;
CREATE TABLE `bobo_favorite` (
  `favorite_id` BIGINT   NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
  `user_id`     BIGINT   NOT NULL                COMMENT '用户ID',
  `item_id`     BIGINT   NOT NULL                COMMENT '商品ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`favorite_id`),
  UNIQUE KEY `uk_favorite_user_item` (`user_id`, `item_id`),
  KEY `idx_favorite_item` (`item_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品收藏（想要）表';

-- -----------------------------------------------------------------------------
-- 6. bobo_conversation 会话（买家 ↔ 卖家，围绕某件商品）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_conversation`;
CREATE TABLE `bobo_conversation` (
  `conversation_id` BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `item_id`         BIGINT       NOT NULL                COMMENT '商品ID',
  `buyer_id`        BIGINT       NOT NULL                COMMENT '买家用户ID',
  `seller_id`       BIGINT       NOT NULL                COMMENT '卖家用户ID',
  `last_message`    VARCHAR(255)          DEFAULT NULL   COMMENT '最后一条消息摘要',
  `last_time`       DATETIME              DEFAULT NULL   COMMENT '最后消息时间',
  `buyer_unread`    INT          NOT NULL DEFAULT 0      COMMENT '买家未读数',
  `seller_unread`   INT          NOT NULL DEFAULT 0      COMMENT '卖家未读数',
  `del_flag`        TINYINT      NOT NULL DEFAULT 0      COMMENT '删除标志：0 存在 / 2 删除',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`conversation_id`),
  UNIQUE KEY `uk_conv_item_buyer_seller` (`item_id`, `buyer_id`, `seller_id`),
  KEY `idx_conv_buyer` (`buyer_id`, `last_time`),
  KEY `idx_conv_seller` (`seller_id`, `last_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '私信会话表';

-- -----------------------------------------------------------------------------
-- 7. bobo_message 私信消息
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_message`;
CREATE TABLE `bobo_message` (
  `message_id`      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `conversation_id` BIGINT       NOT NULL                COMMENT '会话ID',
  `from_id`         BIGINT       NOT NULL                COMMENT '发送者用户ID',
  `to_id`           BIGINT       NOT NULL                COMMENT '接收者用户ID',
  `content`         VARCHAR(1000) NOT NULL               COMMENT '消息内容',
  `is_read`         TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读：0 未读 / 1 已读',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`message_id`),
  KEY `idx_msg_conv_time` (`conversation_id`, `create_time`),
  KEY `idx_msg_to_read` (`to_id`, `is_read`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '私信消息表';

-- -----------------------------------------------------------------------------
-- 8. bobo_address 收货地址（邮寄交易用）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_address`;
CREATE TABLE `bobo_address` (
  `address_id`  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '地址ID',
  `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
  `receiver`    VARCHAR(32)  NOT NULL                COMMENT '收货人',
  `phone`       VARCHAR(20)  NOT NULL                COMMENT '联系电话',
  `campus`      VARCHAR(64)           DEFAULT NULL   COMMENT '校区',
  `detail`      VARCHAR(255) NOT NULL                COMMENT '详细地址（宿舍楼/房间号）',
  `is_default`  TINYINT      NOT NULL DEFAULT 0      COMMENT '是否默认：0 否 / 1 是',
  `del_flag`    TINYINT      NOT NULL DEFAULT 0      COMMENT '删除标志：0 存在 / 2 删除',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`address_id`),
  KEY `idx_address_user` (`user_id`, `is_default`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '收货地址表';

-- -----------------------------------------------------------------------------
-- 9. bobo_order 订单
--    状态机：0 待付款 → 1 待发货 → 2 待收货 → 3 待评价 → 4 已完成
--            5 已取消（买家/卖家/超时）；任意未完成态可申请退款 6 退款中 → 7 已退款
--    当面交易跳过 1/2：0 待付款 → 3 待评价 → 4 已完成
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_order`;
CREATE TABLE `bobo_order` (
  `order_id`      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `order_no`      VARCHAR(32)   NOT NULL                COMMENT '订单号（业务唯一）',
  `item_id`       BIGINT        NOT NULL                COMMENT '商品ID',
  `buyer_id`      BIGINT        NOT NULL                COMMENT '买家用户ID',
  `seller_id`     BIGINT        NOT NULL                COMMENT '卖家用户ID',
  `item_title`    VARCHAR(100)  NOT NULL                COMMENT '下单时商品标题快照',
  `item_cover`    VARCHAR(255)           DEFAULT NULL   COMMENT '下单时商品封面快照',
  `price`         DECIMAL(10,2) NOT NULL                COMMENT '成交单价',
  `quantity`      INT           NOT NULL DEFAULT 1      COMMENT '数量',
  `total_amount`  DECIMAL(10,2) NOT NULL                COMMENT '订单总额',
  `trade_type`    TINYINT       NOT NULL DEFAULT 1      COMMENT '交易方式：1 当面交易 / 2 邮寄',
  `address_id`    BIGINT                 DEFAULT NULL   COMMENT '收货地址ID（邮寄时必填）',
  `receiver_info` VARCHAR(255)           DEFAULT NULL   COMMENT '收货信息快照：收货人/电话/地址',
  `trade_place`   VARCHAR(128)           DEFAULT NULL   COMMENT '当面交易地点',
  `trade_time`    DATETIME               DEFAULT NULL   COMMENT '约定交易时间',
  `remark`        VARCHAR(255)           DEFAULT NULL   COMMENT '买家备注',
  `logistics_company` VARCHAR(64)        DEFAULT NULL   COMMENT '快递公司',
  `logistics_no`  VARCHAR(64)            DEFAULT NULL   COMMENT '快递单号',
  `status`        TINYINT       NOT NULL DEFAULT 0      COMMENT '订单状态，见文件头状态机',
  `cancel_reason` VARCHAR(255)           DEFAULT NULL   COMMENT '取消/退款原因',
  `pay_time`      DATETIME               DEFAULT NULL   COMMENT '支付时间',
  `deliver_time`  DATETIME               DEFAULT NULL   COMMENT '发货时间',
  `finish_time`   DATETIME               DEFAULT NULL   COMMENT '完成时间',
  `del_flag`      TINYINT       NOT NULL DEFAULT 0      COMMENT '删除标志：0 存在 / 2 删除',
  `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_order_buyer` (`buyer_id`, `status`),
  KEY `idx_order_seller` (`seller_id`, `status`),
  KEY `idx_order_item` (`item_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '订单表';

-- -----------------------------------------------------------------------------
-- 10. bobo_review 交易评价（双向：买家评卖家、卖家评买家）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_review`;
CREATE TABLE `bobo_review` (
  `review_id`   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评价ID',
  `order_id`    BIGINT       NOT NULL                COMMENT '订单ID',
  `item_id`     BIGINT       NOT NULL                COMMENT '商品ID',
  `from_id`     BIGINT       NOT NULL                COMMENT '评价人用户ID',
  `to_id`       BIGINT       NOT NULL                COMMENT '被评价人用户ID',
  `score`       TINYINT      NOT NULL DEFAULT 5      COMMENT '评分 1-5',
  `content`     VARCHAR(500)          DEFAULT NULL   COMMENT '评价内容',
  `role`        VARCHAR(16)  NOT NULL DEFAULT 'buyer' COMMENT '评价人角色：buyer 买家评卖家 / seller 卖家评买家',
  `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '状态：0 显示 / 1 隐藏（管理员）',
  `del_flag`    TINYINT      NOT NULL DEFAULT 0      COMMENT '删除标志：0 存在 / 2 删除',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`review_id`),
  UNIQUE KEY `uk_review_order_from` (`order_id`, `from_id`),
  KEY `idx_review_to` (`to_id`, `status`),
  KEY `idx_review_item` (`item_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '交易评价表';

-- -----------------------------------------------------------------------------
-- 11. bobo_notice 公告 / 站内通知
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_notice`;
CREATE TABLE `bobo_notice` (
  `notice_id`   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '通知ID',
  `user_id`     BIGINT       NOT NULL DEFAULT 0      COMMENT '接收用户ID，0 表示全站公告',
  `title`       VARCHAR(100) NOT NULL                COMMENT '标题',
  `content`     VARCHAR(500)          DEFAULT NULL   COMMENT '内容',
  `type`        VARCHAR(16)  NOT NULL DEFAULT 'system' COMMENT '类型：system 系统 / order 订单 / audit 审核 / chat 私信',
  `biz_id`      BIGINT                DEFAULT NULL   COMMENT '关联业务ID（订单ID/商品ID）',
  `is_read`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读：0 未读 / 1 已读',
  `del_flag`    TINYINT      NOT NULL DEFAULT 0      COMMENT '删除标志：0 存在 / 2 删除',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`notice_id`),
  KEY `idx_notice_user` (`user_id`, `is_read`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '公告与站内通知表';

-- -----------------------------------------------------------------------------
-- 12. bobo_report 举报
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bobo_report`;
CREATE TABLE `bobo_report` (
  `report_id`    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '举报ID',
  `user_id`      BIGINT       NOT NULL                COMMENT '举报人用户ID',
  `target_type`  VARCHAR(16)  NOT NULL                COMMENT '举报对象类型：item 商品 / user 用户 / review 评价',
  `target_id`    BIGINT       NOT NULL                COMMENT '举报对象ID',
  `reason`       VARCHAR(64)  NOT NULL                COMMENT '举报原因',
  `detail`       VARCHAR(500)          DEFAULT NULL   COMMENT '补充说明',
  `status`       TINYINT      NOT NULL DEFAULT 0      COMMENT '处理状态：0 待处理 / 1 已处理 / 2 已驳回',
  `handle_remark` VARCHAR(255)         DEFAULT NULL   COMMENT '处理说明',
  `handle_by`    BIGINT                DEFAULT NULL   COMMENT '处理人用户ID',
  `handle_time`  DATETIME              DEFAULT NULL   COMMENT '处理时间',
  `del_flag`     TINYINT      NOT NULL DEFAULT 0      COMMENT '删除标志：0 存在 / 2 删除',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`report_id`),
  KEY `idx_report_status` (`status`, `create_time`),
  KEY `idx_report_target` (`target_type`, `target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '举报表';

SET FOREIGN_KEY_CHECKS = 1;
