-- =============================================================================
-- bobo 校园二手交易平台 —— 技术升级（智能撮合 / 视觉定价 / RAG 问答）
-- 文件：mysql/init/06-ai-upgrade.sql
--
-- 设计说明：
--   1) 本脚本与 01~05 同批次由 MySQL 官方镜像的 /docker-entrypoint-initdb.d
--      按文件名顺序执行；对**已有部署**可手工重复执行（幂等）：
--        docker compose exec -T bobo-mysql \
--          mysql -ubobo -p"$MYSQL_PASSWORD" bobo < mysql/init/06-ai-upgrade.sql
--   2) MySQL 8 没有 ADD COLUMN IF NOT EXISTS，这里用 information_schema 判断 +
--      动态 SQL 的存储过程实现幂等加列/加索引，避免"第二次执行就报 1060"。
--   3) 金额一律 DECIMAL(10,2)；字符集显式 utf8mb4 + utf8mb4_unicode_ci，
--      与 01/02 的表保持同一排序规则（否则 JOIN 会报 1267）。
--   4) 知识库只预置**文档正文**，切片与向量由服务启动时自动构建
--      （见 KnowledgeIndexService），因此这里不需要也不应该写死 embedding。
--
-- 数据模型对应技术文档第 4 节：
--   Listing.ai_suggested_price / condition_grade  → bobo_item 新增 ai_* 列
--   MatchRequest                                  → bobo_match_request
--   ConversationLog                               → bobo_match_message
--   KnowledgeChunk                                → bobo_knowledge_chunk
--   另加 bobo_price_estimate 保存每次定价评估（可追溯、可统计采纳率）
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 0. 幂等工具：加列 / 加索引
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `bobo_add_col`;
DROP PROCEDURE IF EXISTS `bobo_add_idx`;

DELIMITER $$
CREATE PROCEDURE `bobo_add_col`(IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl VARCHAR(600))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_col
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE `bobo_add_idx`(IN p_table VARCHAR(64), IN p_idx VARCHAR(64), IN p_cols VARCHAR(255))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_idx
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_idx, '` (', p_cols, ')');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

-- -----------------------------------------------------------------------------
-- 1. bobo_item：AI 成色/价格建议（卖家确认后写入；未确认时保持 NULL）
--    ai_price_source 区分 model / rule，前端据此标注来源，不把规则结果说成 AI
-- -----------------------------------------------------------------------------
CALL bobo_add_col('bobo_item', 'ai_condition_grade',
  '`ai_condition_grade` VARCHAR(16) DEFAULT NULL COMMENT ''AI 评估成色（与 condition_level 同枚举）''');
CALL bobo_add_col('bobo_item', 'ai_suggested_price',
  '`ai_suggested_price` DECIMAL(10,2) DEFAULT NULL COMMENT ''AI 建议价（价格区间中值，仅建议）''');
CALL bobo_add_col('bobo_item', 'ai_price_low',
  '`ai_price_low` DECIMAL(10,2) DEFAULT NULL COMMENT ''AI 建议价区间下限''');
CALL bobo_add_col('bobo_item', 'ai_price_high',
  '`ai_price_high` DECIMAL(10,2) DEFAULT NULL COMMENT ''AI 建议价区间上限''');
CALL bobo_add_col('bobo_item', 'ai_price_source',
  '`ai_price_source` VARCHAR(16) DEFAULT NULL COMMENT ''建议来源：model 模型 / rule 规则''');
CALL bobo_add_col('bobo_item', 'ai_price_time',
  '`ai_price_time` DATETIME DEFAULT NULL COMMENT ''AI 估价确认时间''');

-- -----------------------------------------------------------------------------
-- 2. bobo_order：关联撮合单，按撮合价下单时可追溯
-- -----------------------------------------------------------------------------
CALL bobo_add_col('bobo_order', 'match_id',
  '`match_id` BIGINT DEFAULT NULL COMMENT ''关联的智能撮合单ID，普通下单为 NULL''');
CALL bobo_add_idx('bobo_order', 'idx_order_match', '`match_id`');

-- -----------------------------------------------------------------------------
-- 3. bobo_price_estimate —— 每次视觉定价评估的完整快照
--    · item_id 可为空：发布前就能估价，商品还没落库
--    · 保存 text_hash 便于识别"同一段描述被反复估价"，也方便统计采纳率
--    · reasons 用 JSON 数组文本（MySQL 8 的 JSON 类型在部分旧客户端兼容性差，
--      这里用 VARCHAR 存 JSON 字符串，服务端用 Jackson 解析）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bobo_price_estimate` (
  `estimate_id`     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '评估ID',
  `user_id`         BIGINT        NOT NULL                COMMENT '发起估价的卖家用户ID',
  `item_id`         BIGINT                 DEFAULT NULL   COMMENT '商品ID，发布前估价时为空',
  `title`           VARCHAR(100)           DEFAULT NULL   COMMENT '估价时的标题快照',
  `description`     VARCHAR(500)           DEFAULT NULL   COMMENT '描述摘要（截断，仅用于复现）',
  `category_id`     BIGINT                 DEFAULT NULL   COMMENT '分类ID',
  `category_name`   VARCHAR(32)            DEFAULT NULL   COMMENT '分类名快照',
  `image_url`       VARCHAR(255)           DEFAULT NULL   COMMENT '送审的封面图URL',
  `image_count`     INT           NOT NULL DEFAULT 0      COMMENT '送审图片数',
  `text_hash`       VARCHAR(32)            DEFAULT NULL   COMMENT '标题+描述+分类摘要，用于去重统计',
  `condition_grade` VARCHAR(16)            DEFAULT NULL   COMMENT '评估成色',
  `price_low`       DECIMAL(10,2)          DEFAULT NULL   COMMENT '建议价下限',
  `price_mid`       DECIMAL(10,2)          DEFAULT NULL   COMMENT '建议价中值',
  `price_high`      DECIMAL(10,2)          DEFAULT NULL   COMMENT '建议价上限',
  `confidence`      DECIMAL(4,3)           DEFAULT NULL   COMMENT '置信度 0~1',
  `reasons`         VARCHAR(500)           DEFAULT NULL   COMMENT '评估理由（JSON 数组文本）',
  `sample_count`    INT           NOT NULL DEFAULT 0      COMMENT '参考的历史成交/在售样本数',
  `basis`           VARCHAR(255)           DEFAULT NULL   COMMENT '样本来源说明',
  `source`          VARCHAR(16)   NOT NULL DEFAULT 'rule' COMMENT '结论来源：model / rule',
  `log_id`          BIGINT                 DEFAULT NULL   COMMENT '关联 AI 调用流水',
  `adopted`         TINYINT       NOT NULL DEFAULT 0      COMMENT '卖家是否采纳：1 采纳 / 0 未采纳',
  `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`estimate_id`),
  KEY `idx_price_estimate_user_time` (`user_id`, `create_time`),
  KEY `idx_price_estimate_item` (`item_id`),
  KEY `idx_price_estimate_hash` (`text_hash`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 视觉定价评估表';

-- -----------------------------------------------------------------------------
-- 4. bobo_match_request —— 撮合单（买家 Agent × 卖家 Agent 多轮协商）
--    状态机：
--      0 协商中 → 1 待双方确认 → 2 已达成 → 6 已按撮合价下单
--      0/1 可被双方取消 → 4 已取消；协商无法达成 → 3 未达成；超时 → 5 已过期
--    人工确认原则：两个 confirmed 都置 1 才算达成，系统绝不自动成交
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bobo_match_request` (
  `match_id`        BIGINT        NOT NULL AUTO_INCREMENT COMMENT '撮合单ID',
  `buyer_id`        BIGINT        NOT NULL                COMMENT '买家用户ID',
  `seller_id`       BIGINT        NOT NULL                COMMENT '卖家用户ID',
  `item_id`         BIGINT        NOT NULL                COMMENT '目标商品ID',
  `status`          TINYINT       NOT NULL DEFAULT 0      COMMENT '0 协商中 / 1 待确认 / 2 已达成 / 3 未达成 / 4 已取消 / 5 已过期 / 6 已下单',
  `budget_min`      DECIMAL(10,2)          DEFAULT NULL   COMMENT '买家预算下限',
  `budget_max`      DECIMAL(10,2)          DEFAULT NULL   COMMENT '买家预算上限',
  `demand_text`     VARCHAR(500)           DEFAULT NULL   COMMENT '买家补充诉求（时间/地点/赠品等）',
  `preferred_time`  VARCHAR(64)            DEFAULT NULL   COMMENT '期望交易时间（文本，协商上下文）',
  `preferred_place` VARCHAR(128)           DEFAULT NULL   COMMENT '期望交易地点',
  `trade_type`      TINYINT                DEFAULT NULL   COMMENT '期望交易方式：1 当面 / 2 邮寄',
  `agreed_price`    DECIMAL(10,2)          DEFAULT NULL   COMMENT '协商达成价（仅建议，需双方确认）',
  `rounds`          INT           NOT NULL DEFAULT 0      COMMENT '实际协商轮次',
  `summary`         VARCHAR(500)           DEFAULT NULL   COMMENT '撮合摘要，给用户看的结论',
  `buyer_confirmed` TINYINT       NOT NULL DEFAULT 0      COMMENT '买家确认：1 是 / 0 否',
  `seller_confirmed` TINYINT      NOT NULL DEFAULT 0      COMMENT '卖家确认：1 是 / 0 否',
  `buyer_note`      VARCHAR(255)           DEFAULT NULL   COMMENT '买家确认/拒绝留言',
  `seller_note`     VARCHAR(255)           DEFAULT NULL   COMMENT '卖家确认/拒绝留言',
  `source`          VARCHAR(16)   NOT NULL DEFAULT 'rule' COMMENT '协商来源：model / rule / mixed',
  `log_id`          BIGINT                 DEFAULT NULL   COMMENT '最后一次模型调用流水',
  `expire_time`     DATETIME               DEFAULT NULL   COMMENT '等待确认的过期时间',
  `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`match_id`),
  KEY `idx_match_buyer_status` (`buyer_id`, `status`, `create_time`),
  KEY `idx_match_seller_status` (`seller_id`, `status`, `create_time`),
  KEY `idx_match_item` (`item_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '智能撮合单表';

-- -----------------------------------------------------------------------------
-- 5. bobo_match_message —— 多轮协商对话记录（对应技术文档 ConversationLog）
--    sender：buyer_agent / seller_agent / system；action：offer/counter/accept/reject/note
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bobo_match_message` (
  `message_id`  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `match_id`    BIGINT        NOT NULL                COMMENT '撮合单ID',
  `round_no`    INT           NOT NULL DEFAULT 0      COMMENT '第几轮（0 为开场/系统消息）',
  `sender`      VARCHAR(16)   NOT NULL                COMMENT 'buyer_agent / seller_agent / system',
  `action`      VARCHAR(16)   NOT NULL                COMMENT 'offer 出价 / counter 还价 / accept 接受 / reject 拒绝 / note 说明',
  `price`       DECIMAL(10,2)          DEFAULT NULL   COMMENT '本条消息中的价格',
  `content`     VARCHAR(1000) NOT NULL                COMMENT '消息正文',
  `source`      VARCHAR(16)   NOT NULL DEFAULT 'rule' COMMENT '本条由模型还是规则生成：model / rule',
  `log_id`      BIGINT                 DEFAULT NULL   COMMENT '关联 AI 调用流水',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`message_id`),
  KEY `idx_match_msg_match_time` (`match_id`, `message_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '智能撮合协商消息表';

-- -----------------------------------------------------------------------------
-- 6. bobo_knowledge_doc —— 知识库文档（交易规则 / 违禁品 / 纠纷流程）
--    status：0 启用 / 1 停用。文档正文由运营维护，切片在服务端自动构建。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bobo_knowledge_doc` (
  `doc_id`      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档ID',
  `title`       VARCHAR(100) NOT NULL                COMMENT '文档标题',
  `category`    VARCHAR(32)  NOT NULL                COMMENT '类目：交易规则 / 违禁品 / 纠纷处理',
  `content`     MEDIUMTEXT   NOT NULL                COMMENT '正文（纯文本/Markdown）',
  `source`      VARCHAR(64)           DEFAULT NULL   COMMENT '来源标识，如 seed-2026 / 运营维护',
  `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '0 启用 / 1 停用',
  `chunk_count` INT          NOT NULL DEFAULT 0      COMMENT '当前已索引切片数（重建索引后回写）',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`doc_id`),
  KEY `idx_knowledge_doc_status` (`status`, `category`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'RAG 知识库文档表';

-- -----------------------------------------------------------------------------
-- 7. bobo_knowledge_chunk —— 切片与向量
--    embedding：float32 小端数组的 Base64 文本（本地哈希向量，维度见 EmbeddingProvider）。
--    存文本而不是 BLOB：方便运维直接看长度/排查，MySQL 8 的 TEXT 足够装下几十维~512维。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bobo_knowledge_chunk` (
  `chunk_id`    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '切片ID',
  `doc_id`      BIGINT        NOT NULL                COMMENT '所属文档ID',
  `chunk_no`    INT           NOT NULL                COMMENT '文档内序号，从 1 开始',
  `content`     VARCHAR(1000) NOT NULL                COMMENT '切片正文',
  `embedding`   TEXT                   DEFAULT NULL   COMMENT '向量（float32 Base64）',
  `dim`         INT           NOT NULL DEFAULT 0      COMMENT '向量维度',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`chunk_id`),
  UNIQUE KEY `uk_knowledge_chunk_doc_no` (`doc_id`, `chunk_no`),
  KEY `idx_knowledge_chunk_doc` (`doc_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'RAG 知识库切片表';

-- -----------------------------------------------------------------------------
-- 8. 知识库种子文档（幂等：按主键覆盖正文，保留运营改过的 status? 不保留正文，
--    但 status 不动，避免重建脚本把运营停用的文档重新启用）
--    doc_id 固定为 1/2/3，已有部署重复执行不会产生重复文档。
-- -----------------------------------------------------------------------------
INSERT INTO `bobo_knowledge_doc` (`doc_id`, `title`, `category`, `content`, `source`, `status`)
VALUES
(1, 'bobo 校园二手交易规则（2026 版）', '交易规则', '一、平台定位
bobo 是面向本校学生的闲置交易平台，倡导同校、当面、验货后交易。平台不收取手续费，也不提供担保交易，买卖双方应自行核实对方身份与物品状况。

二、发布规范
1. 卖家发布闲置须上传至少一张实物图，标题与描述应真实、完整，不得使用与实物无关的网络图片。
2. 商品须选择正确分类与成色。成色枚举为：全新、99新、95新、9成新、8成新、7成新及以下。
3. 同一用户最多同时有 5 件商品处于待审核状态；审核通过后名额立即释放。
4. 禁止发布违禁品、盗版内容、代写代考服务、他人隐私信息，详见《违禁品清单》。
5. 审核一般在 24 小时内完成。被驳回的商品会给出原因，修改后可重新提交审核。

三、交易方式
1. 当面交易：买卖双方约定校内公共区域（食堂门口、图书馆一楼、宿舍楼下等）验货后交易。平台建议在白天、人流密集处完成。
2. 邮寄交易：买家下单时选择收货地址，卖家发货并填写快递公司与单号，买家确认收货后订单进入待评价。
3. 卖家发布时可以选择「都支持 / 仅当面 / 仅邮寄」，买家下单时只能在卖家支持的范围内选择。

四、下单、支付与订单流转
1. 下单即锁定商品：同一件商品并发下单时只有一人能成功，其他人会看到「已被抢先下单」。
2. 平台使用模拟支付用于演示，不接入真实资金渠道。订单状态依次为：待付款、待发货、待收货、待评价、已完成；当面交易跳过发货与收货。
3. 买家应在下单前确认商品状态、成色、交易地点与时间。下单后可在待付款阶段取消。

五、取消与退款
1. 待付款订单，买卖双方均可取消；已付款未完成的订单可申请退款，由卖家同意后退款；卖家也可主动取消并退款。
2. 商品被退款或取消后，若没有其他有效订单，会自动恢复为在售状态。
3. 已完成的订单不支持平台强制退款，出现纠纷请走举报与人工处理流程。

六、评价与信用分
1. 订单完成后买卖双方可互相评价，评分 1 至 5 星，评价内容不得包含辱骂、联系方式引流或与交易无关的信息。
2. 评价会计入对方信用分。信用分初始 100，违规会被扣分，信用分过低会影响账号正常使用。
3. 管理员可以隐藏违规评价；恶意差评可举报。

七、平台外交易风险
1. 平台不支持私下转账、微信/支付宝直接付款后不发货等行为。由此产生的损失平台难以追溯。
2. 任何要求提前确认收货、脱离平台付款、点击陌生链接的行为都应高度警惕。
3. 发现诈骗、冒充学生、售卖违禁品，请立即通过商品详情页的举报入口提交证据。

八、账号与隐私
1. 账号仅限本人使用，不得出租、出售或出借。
2. 手机号、宿舍地址等个人信息仅在交易必要时提供，平台不会在商品页公开展示。
3. 平台工作人员不会以任何理由索要密码或验证码。', 'seed-2026', 0),
(2, '校园二手交易违禁品清单', '违禁品', '本清单用于商品发布前的风险识别与人工审核。清单会随监管要求更新，以最新版本为准。

一、绝对禁止交易（发现即驳回/下架，情节严重的封禁账号并移交学校或公安机关）
1. 管制器具：管制刀具、弹簧刀、仿真枪、弓弩、电击器、催泪喷射器、指虎等。
2. 危险物品：易燃易爆品、烟花爆竹、汽油、强腐蚀性化学品、剧毒物品、放射性物品。
3. 毒品与违禁药物：各类毒品、吸毒工具、未经批准的处方药、麻醉药品、精神药品。
4. 烟草与酒精：香烟、电子烟、烟油、酒类，尤其是向未成年人销售。
5. 盗版与作弊：盗版教材/影音、破解软件、代写代考服务、考试作弊器材、论文代写。
6. 个人信息与账号：身份证、学生证、校园卡、银行卡、电话卡、社交账号、简历数据、通讯录、含他人隐私的照片或视频。
7. 偷拍偷录设备：伪装摄像头、针孔摄像头、追踪定位器（用于侵犯他人隐私的用途）。
8. 野生动物及其制品：象牙、犀牛角、玳瑁、活体保护动物等。
9. 活体动物：猫、狗、仓鼠等活体宠物（平台不具备活体运输与检疫条件）。
10. 其他违法物品：伪造证件、假币、开锁工具、赌博用具、传销资料。

二、限制交易（需满足条件，否则驳回）
1. 大功率电器：热得快、电磁炉、电暖器、电饭锅等，须符合学校宿舍用电规定；描述中应注明功率与安全性。
2. 药品与保健品：仅限非处方且未过期的常见药品，须明确有效期；处方药一律禁止。
3. 二手电子设备：须说明是否锁 ID、是否拆修、电池健康度；不得隐瞒赃物或来源不明设备。
4. 教材与资料：不得包含盗版影印、未授权的内部讲义、含答案的考试资料。
5. 化妆品：须在保质期内且未开封或已明确说明使用情况，避免三无产品。

三、审核与处置
1. 发布时系统会做关键词与模型风险预判，命中高风险会提示修改；最终由管理员人工审核。
2. 已上架商品被举报或巡检发现违禁，将强制下架并通知卖家；重复违规会扣信用分。
3. 涉嫌违法犯罪（枪支、毒品、诈骗、个人信息买卖）的，平台保留移交学校保卫处或公安机关的权利。
4. 对审核结果有异议，可在通知中查看驳回原因并修改后重新提交，或联系管理员申诉。

四、常见误区
1. 「只是帮朋友发一下」不免责，发布账号对内容负责。
2. 「二手教材带答案」可能涉及盗版或作弊，禁止发布。
3. 「代购烟酒」属于向校园倒卖，禁止发布。
4. 「送养宠物」也不属于闲置交易范围，请通过正规领养渠道。', 'seed-2026', 0),
(3, '交易纠纷处理流程', '纠纷处理', '一、处理原则
1. 先协商、后举证、再申诉。买卖双方应保留聊天记录、商品照片、发货与开箱视频等证据。
2. 平台只处理站内产生的订单与聊天记录；脱离平台的私下交易无法核实，平台不承担赔付责任。
3. 管理员依据证据与平台规则处理，处理结果会通过站内通知告知双方。

二、当面交易纠纷
1. 买家应在交易现场验货：外观、功能、配件、序列号/IMEI、是否锁 ID。确认无误后再付款或确认收货。
2. 发现货不对板或假货，应当场拒绝交易并保留商品照片与聊天记录，不要先确认收货。
3. 已完成交易后发现隐蔽瑕疵，先与卖家协商；协商不成可提交举报，附上购买前的描述截图与实物照片。
4. 涉及人身安全或诈骗，第一时间联系学校保卫处并报警。

三、邮寄交易纠纷
1. 卖家发货前应拍照/录像记录商品状态与打包过程，填写真实快递单号。
2. 买家收到包裹时建议全程录像开箱；发现少件、破损、货不对板或假货，先拒收或保留包装，再在订单页申请退款。
3. 未收到货：先查快递轨迹并联系卖家；卖家无法提供有效发货凭证的，管理员可支持退款。
4. 已确认收货后申请退款，需要提供充分证据，由卖家同意或管理员介入。

四、退款与举报入口
1. 订单未完成时，买家可在「我的订单 → 订单详情」申请退款；卖家同意后订单关闭并退款。
2. 卖家拒绝退款或联系不上，可在订单/商品详情页提交举报，选择「交易纠纷」并上传证据。
3. 举报后的处理时限：管理员一般在 48 小时内首次响应；复杂纠纷最长不超过 7 个工作日。
4. 举报时请写清订单号、问题描述、诉求（退款/换货/下架/处罚），并上传聊天记录与实物图。

五、处罚措施
1. 描述不符、隐瞒瑕疵：警告、扣信用分、强制下架。
2. 虚假发货、收款不发货：扣信用分、限制发布、封禁账号，涉嫌诈骗移交公安机关。
3. 辱骂威胁、恶意差评、骚扰：隐藏评价、扣信用分，情节严重的封禁。
4. 售卖违禁品：立即下架并封禁，参见《违禁品清单》。
5. 恶意举报、伪造证据：扣信用分，情节严重的封禁。

六、防止纠纷的建议
1. 商品描述写清成色、使用时长、瑕疵位置、配件与保修情况，避免使用「几乎全新」等模糊表述。
2. 交易前在站内私信确认价格、时间、地点与验货方式，保留聊天记录。
3. 优先选择校内公共场所当面交易；邮寄时选择可查询轨迹的快递并保价。
4. 不要提前确认收货，不要脱离平台转账，不要点击陌生人发来的链接。

七、买到假货怎么办
1. 立即停止使用或转卖，保留商品、包装、快递面单、聊天记录与支付凭证；有鉴定渠道的保留鉴定结果。
2. 在订单页申请退款，退款原因选择「假货 / 货不对板」；卖家拒绝的，提交举报并上传证据，举报类目选「交易纠纷」。
3. 平台会核对商品描述、聊天记录与实物证据。确认售假的，支持退款并扣信用分、下架商品，情节严重的封禁账号。
4. 涉及金额较大、卖家失联或涉嫌诈骗的，请同时联系学校保卫处并报警；平台会配合提供站内交易记录。
5. 不要为了「私了」先确认收货或删除证据；也不要在未收到退款前把商品退回给无法核实的地址。', 'seed-2026', 0)
ON DUPLICATE KEY UPDATE
  `title` = VALUES(`title`),
  `category` = VALUES(`category`),
  `content` = VALUES(`content`),
  `source` = VALUES(`source`);

-- 种子文档正文变化后清空切片：服务启动时发现「有文档无切片」会自动重建；
-- 已在运行的服务可在后台「AI 运营 → 技术升级」点「重建索引」。
DELETE FROM `bobo_knowledge_chunk` WHERE `doc_id` IN (1, 2, 3);
UPDATE `bobo_knowledge_doc` SET `chunk_count` = 0 WHERE `doc_id` IN (1, 2, 3);

DROP PROCEDURE IF EXISTS `bobo_add_col`;
DROP PROCEDURE IF EXISTS `bobo_add_idx`;

SET FOREIGN_KEY_CHECKS = 1;
