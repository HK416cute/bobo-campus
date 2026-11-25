# bobo 技术升级：DeepSeek 多智能体撮合 / 视觉定价 / RAG 问答

> 版本：2026-11 · 适用代码：`bobo-server` / `bobo-web` / `mysql/init/06-ai-upgrade.sql`
>
> 本文是这次升级的**设计说明与验收依据**。原有发布助手、检索意图解析、内容审核、
> 运营 Copilot 的设计保持不变，见 [ai.md](ai.md)。

---

## 一、一页结论

技术规格书原本按 Claude API 设计。本次升级按最新要求**全部改用 DeepSeek**：
同一套 `BOBO_AI_API_KEY` / `BOBO_AI_BASE_URL`，不引入第二家模型厂商，
也不依赖 Anthropic SDK。DeepSeek 在 2026-08 上线原生图像理解，
2026-11 又开放了「全家桶应用 API」（联网搜索、文件输入、阅读链接、模型安全、OCR、ASR、TTS），
因此 Claude 方案里的 Vision / Agent / RAG 三块能力都能在 DeepSeek 上落地。

| 模块 | 入口 | 模型用法 | 无模型时 |
|---|---|---|---|
| 多智能体撮合 | 商品详情「智能撮合」→ `/matches` | DeepSeek 文本，多轮 JSON 协商，最多 3 轮 / 30 秒 | 规则引擎确定性协商，仍能出建议价 |
| 视觉定价与成色 | 发布页「AI 看图估价」 | `deepseek-flash` 图像输入 + `response_format=json_object` | 同类价位分位 × 成色系数 |
| RAG 知识库问答 | 顶栏「智能问答」→ `/qa` | 本地向量召回 + DeepSeek 生成，强制引用 | 返回命中的规则原文摘录 |

三条硬约束贯穿全部模块：

1. **人工确认**：定价建议、撮合建议都只是建议，必须用户点确认；系统不自动改价、不自动成交。
2. **密钥只在服务端**：Key 只从环境变量或后台配置表读取，接口不返回明文，日志不打印。
3. **降级是主路径**：任何模型故障都返回可用结果，并标注 `source=rule` / `degradeNote`。

---

## 二、为什么可以从 Claude 切到 DeepSeek

技术规格书的 Claude 能力点与本项目的 DeepSeek 实现对应关系：

| 规格书能力 | Claude 方案 | 本次 DeepSeek 实现 |
|---|---|---|
| 多轮 Agent 协商 | Claude Agent SDK + 工具 | 服务端状态机 + DeepSeek Chat Completions（JSON 输出）+ 规则引擎 |
| 图片成色/定价 | Claude Vision | `deepseek-flash` 的 `image_url` 内容块（base64） |
| 结构化输出 | 提示词 + JSON | 提示词 + `response_format={"type":"json_object"}` |
| Prompt Caching | 显式 `cache_control` 断点 | DeepSeek 自动上下文硬盘缓存（usage 里有命中 token 数） |
| RAG 生成 | Claude Messages API | 现有 `ChatInvoker`（Spring AI DeepSeek）+ 严格引用提示词 |
| 内容安全 | Claude 安全策略 | DeepSeek `safety_check`（多模态请求开启 high） |

**没有为了「换厂商」而换架构**：原有 `AiExecutor` 仍然是所有 AI 调用的唯一入口，
门控、限流、每日配额、流水、成本估算、降级全部复用；新增的只有
「能发图片的调用器」和三个业务模块。

参考文档：

- [DeepSeek 图像理解](https://api-docs.deepseek.com/zh-cn/guides/vision)
- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion)
- [DeepSeek 全家桶应用 API 发布](https://api-docs.deepseek.com/zh-cn/news/news261113/)

---

## 三、总体架构

```
业务层
  ├── PricingService      （发布页估价 / 采用建议）
  ├── MatchService        （发起、多轮协商、确认、下单）
  └── RagService          （检索、约束生成、引用校验）
        │
        ▼
AiExecutor（门控 → 限流 → 配额 → 调用 → 解析 → 流水；文本与视觉共用）
        │
        ├── ChatInvoker（Spring AI DeepSeek，纯文本：发布助手 / 检索 / 审核 / 撮合 / RAG）
        └── DeepSeekVisionInvoker（OpenAI 兼容 /chat/completions，图片 + JSON + safety_check）
        │
        ▼
规则兜底：AiHeuristicParser / MatchRuleEngine / 知识库原文摘录
```

- **文本与视觉共用同一套 Key、Base URL、限流与配额**：不会出现「视觉调用不计配额」的口子。
- **Spring AI 只用于纯文本**：`deepseek-flash` 的图像块在 spring-ai 1.0.0 里支持不完整，
  因此视觉走手写 HTTP；Spring AI 类型仍只出现在 `AiChatConfig` 一个文件里。
- **RAG 不依赖外部向量库**：本地 512 维哈希向量存 MySQL，单 Jar 部署即可跑。

---

## 四、模块一：多智能体自动撮合

### 4.1 流程

```
买家在商品详情点「智能撮合」
   ↓ 填预算 / 诉求 / 时间 / 地点
MatchService.create()
   ↓ 创建 bobo_match_request（status=0 协商中）
MatchContextService.collect()  ← 确定性工具：
   · 商品详情与卖家信用
   · 同类在售 Top5
   · 近 90 天同类成交分位
   ↓
买家 Agent 首轮出价（DeepSeek，JSON）
   ↓
卖家 Agent 接受 / 还价 / 拒绝
   ↓
买家 Agent 接受 / 再还价 / 拒绝
   ↓ 最多 3 轮，或总预算 30 秒用完
达成 → status=1 待确认；未达成 → status=3
   ↓ 买卖双方各自确认
双方都确认 → status=2 已达成
   ↓ 买家点「按撮合价下单」
下单 → status=6 已下单，订单使用撮合价
```

状态机：

| 值 | 状态 | 说明 |
|---|---|---|
| 0 | 协商中 | 正在跑 Agent 协商（同步请求） |
| 1 | 待确认 | 已达成建议价，等待双方确认；24 小时过期 |
| 2 | 已达成 | 双方都确认，可下单 |
| 3 | 未达成 | 价格差距过大 / 超时 / 任一方拒绝 |
| 4 | 已取消 | 参与者主动取消 |
| 5 | 已过期 | 待确认超过 24 小时 |
| 6 | 已下单 | 已按撮合价创建订单，撮合单不可复用 |

### 4.2 工具为什么由服务端确定性调用

技术规格书要求「买家 Agent 工具：查询在售商品、获取详情、发起出价」。本项目没有把
`tool_choice` 交给模型，而是由 `MatchContextService` 一次性查好事实再注入提示词。原因：

1. **轮次可控**：模型自由选工具可能反复查同一件商品，把 30 秒预算烧光；
2. **可审计**：每一步用了哪些行情数据，服务端能复现；
3. **本场景候选集很小且固定**，没有「让模型决定查什么」的必要。

模型仍然负责它最擅长的部分：**根据事实决定这一轮出价/还价多少、怎么措辞**。
DeepSeek 的 function calling 能力保留给后续「买家想找别的商品」这类开放式查询。

### 4.3 价格边界与降级

- 买家出价不得高于商品标价；卖家还价必须高于买家出价且不高于标价。
- 买家预算上限是硬约束：超出 5% 以上的「接受」会被拒绝，交回规则引擎处理。
- 模型某一步输出不可解析 / 超时 / 无 Key 时，**只降级那一步**：
  `MatchRuleEngine` 按预算、标价、轮次给出确定性的出价/还价。
- 总预算 30 秒用完则停止发起新调用，摘要明确写「协商超时，建议私信或浏览其他商品」。
- 部分轮次模型、部分轮次规则时，`source=mixed`，前端能看出不是全模型协商。

规则引擎（`MatchRuleEngine`）的默认策略：

- 买家首轮：`min(预算上限, 标价×0.85)`，不低于预算下限与标价 60%；
- 卖家接受线：标价 90% 起，每轮降 2 个百分点，最低 84%；
- 卖家底价：标价 78%，低于直接拒绝；
- 买家可上浮：预算上限的 105%，超出则还价或拒绝。

### 4.4 人工确认与下单

- 达成只是 `status=1`，**不会生成订单**。
- 买卖双方各自调用 `POST /api/ai/matches/{id}/confirm`；任一方拒绝 → `status=3`。
- 双方都确认后，买家才能带 `matchId` 下单；`OrderService` 从撮合单读取价格，
  **绝不接受前端传价格**，且用条件更新把撮合单从 `2` 推到 `6`，防止一单多用。
- 订单表新增 `match_id`，可回溯「这一单是撮合来的」。

### 4.5 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/matches` | 发起撮合。body: `{itemId, budgetMin?, budgetMax?, demandText, preferredTime?, preferredPlace?, tradeType?}`；同步返回完整协商记录 |
| GET | `/api/ai/matches?role=buyer\|seller` | 我的撮合列表（最近 50 条） |
| GET | `/api/ai/matches/{id}` | 详情 + 完整消息时间线 |
| POST | `/api/ai/matches/{id}/confirm` | body: `{accepted, note?}`；双方各自确认 |
| POST | `/api/ai/matches/{id}/cancel` | 取消协商 |
| POST | `/api/orders` | 增加可选 `matchId`，按撮合价下单 |

---

## 五、模块二：视觉定价与成色评估

### 5.1 流程

```
卖家上传图片（最多 9 张，第一张封面）
   ↓ 发布页点「AI 看图估价」
PricingService.estimate()
   ↓ ImagePreprocessor：路径校验 + 长边压到 1568px + 最多 3 张
   ↓ 取同类在售分位 + 近 90 天成交分位
DeepSeekVisionInvoker（deepseek-flash）
   · messages: system + user(text + image_url×N)
   · response_format=json_object
   · safety_check=high
   ↓ 解析 {conditionGrade, priceLow, priceHigh, confidence, reasons}
   ↓ 区间收敛（同类中位价 0.55~1.8 倍）
返回 {estimateId, conditionGrade, priceLow/mid/high, reasons, source}
   ↓ 卖家点「采用建议」
发布/编辑时带 aiEstimateId → 写入商品 ai_* 字段
```

### 5.2 图片处理

- 只允许上传目录内的图片，URL 解析后必须落在 `bobo.upload.path` 内，防目录穿越与 SSRF。
- 按文件头识别格式（JPEG/PNG/GIF/WebP），不信任扩展名。
- 长边超过 1568px 或文件过大时等比缩放并转 JPEG；透明 PNG 先铺白底，避免黑底被误判成磕碰。
- 最多送 3 张：DeepSeek 每张图最多约 1024 token，3 张已足够判断成色，再多只是增加成本。

### 5.3 提示词与结构化输出

系统提示词约束：

- 成色只能从 `全新 / 99新 / 95新 / 9成新 / 8成新 / 7成新及以下` 选一个；
- 必须参考给出的同类在售与成交数据，不得凭空给价；
- 图片能看出磨损/缺配件时更保守；
- 输出 `conditionGrade / priceLow / priceHigh / confidence / reasons / riskFlags`。

服务端还有三道校验：

1. 成色不在枚举内 → 用卖家自述成色；仍没有 → 降级规则。
2. `priceLow > priceHigh` → 交换；价格 ≤ 0 → 降级。
3. 区间越界 → 按同类中位价的 0.55~1.8 倍收敛，并在理由里注明「已按平台同类价位校正」。

### 5.4 规则兜底

无 Key、关闭多模态、图片不可读、模型超时或 JSON 不合法时：

- 成色：卖家自述 → 标题/描述关键词推断 → 默认 95新；
- 价格：近 90 天成交中位价（优先）→ 同类在售中位价 → 卖家心理价；
- 乘成色系数：全新 1.10 / 99新 1.05 / 95新 1.00 / 9成新 0.92 / 8成新 0.82 / 7成新及以下 0.70；
- 连样本和心理价都没有时明确返回「无法给出可靠区间」，不编数字。

### 5.5 卖家确认与采纳率

- 估价只落 `bobo_price_estimate`，**不会自动改商品**。
- 卖家在发布页点「采用建议」才把 `aiEstimateId` 带进发布/编辑请求；
  `ItemService` 校验估价归属与是否已被其他商品使用，然后写入 `bobo_item.ai_*` 字段。
- `adopted` 字段支撑采纳率统计；后台「技术升级」卡片展示估价次数/采纳次数。

### 5.6 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/pricing/estimate` | body: `{title?, description?, categoryId?, conditionLevel?, images[], askingPrice?}`；返回成色与价格区间 |
| GET | `/api/ai/features` | 多模态是否可用、模型名、知识库状态等（匿名可读） |

---

## 六、模块三：RAG 知识库问答

### 6.1 知识库内容

`mysql/init/06-ai-upgrade.sql` 预置三篇文档：

1. 《bobo 校园二手交易规则（2026 版）》——发布、交易方式、订单、退款、评价、信用分、隐私；
2. 《校园二手交易违禁品清单》——绝对禁止、限制交易、审核处置、常见误区；
3. 《交易纠纷处理流程》——当面/邮寄纠纷、退款、举报、时限、处罚。

运营改完正文后，在后台「AI 运营 → 技术升级」点「重建索引」即可，不需要手工算向量。

### 6.2 切分与向量化

- `KnowledgeChunker`：按行/句号/分号切成 ≤480 字的片段，相邻片段重叠 80 字；
  超长句硬切，避免一块撑爆。
- `LocalHashEmbeddingProvider`：512 维，中文字符 unigram + bigram、英文单词与 3-gram，
  feature hashing + sign，TF 用 `1+log(tf)`，最后 L2 归一化。
- 向量以 Base64 float32 存 `bobo_knowledge_chunk.embedding`，单条约 2.7KB。

**为什么不用 DeepSeek embedding**：DeepSeek 当前开放的对话/多模态能力不包含 embedding
端点，硬接需要再引一家厂商或本地模型，运维面扩大。知识库是几十到几百个片段，
本地哈希向量 + 余弦召回足够，且完全确定、可测试、零外部依赖。
如果以后要上语义向量，只需替换 `EmbeddingProvider`，检索与问答链路不变。

### 6.3 检索与回答

```
问题 → 向量化 → 与所有启用文档的切片算余弦
   ↓ 取 Top5，阈值 0.10
无命中 → 直接返回「不在知识库范围」（不调模型，零 token）
   ↓ 命中
提示词：只允许依据片段回答；每个结论标 [R1]；片段不足就拒答
   ↓
DeepSeek 输出 {answer, citations:[1,3]}
   ↓
校验引用编号：越界编号丢弃；一条有效引用都没有 → 判为不可用
   ↓
返回 answer + citations（文档、类目、切片号、原文摘要、相似度）
```

### 6.4 防幻觉的四道约束

1. **召回阈值**：低于 0.10 不调模型，直接拒答——没有上下文时模型最可能编。
2. **提示词**：明确「只能依据片段」「不得编造规则、金额、时限」。
3. **引用校验**：模型给的编号逐条对照真实片段，编造的编号不会进入响应。
4. **规则兜底**：模型不可用时返回 Top3 片段原文摘录，至少是真的。

### 6.5 接口与页面

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/qa` | body: `{question}`；返回 `{answer, citations[], inScope, hitCount, source, degradeNote, latencyMs}` |
| GET | `/qa` | 前端问答页：示例问题、回答、引用卡片 |

---

## 七、DeepSeek「全家桶应用 API」的使用边界

2026-11 的全家桶 API 包含：对话（Chat Completions / Responses / Messages）、
图像理解、JSON Output、Tool Calls、上下文缓存、模型安全、文件输入、联网搜索、
阅读链接、OCR、ASR、TTS、Files API。

| 能力 | 本项目是否使用 | 说明 |
|---|---|---|
| Chat Completions | ✅ | 发布助手、检索、审核、运营、撮合、RAG |
| 图像理解 | ✅ | `deepseek-flash` 的 `image_url` base64，用于成色/定价 |
| JSON Output | ✅ | 视觉定价强制 `json_object`；文本场景靠提示词契约 |
| 上下文缓存 | ✅（自动） | DeepSeek 自动硬盘缓存，usage 里可看命中 token |
| 模型安全 `safety_check` | ✅ | 视觉请求开启 `level=high` |
| Tool Calls | ⚠️ 未用于撮合 | 撮合工具由服务端确定性调用，理由见 4.2；保留给后续开放式查询 |
| 文件输入 / Files API | ❌ | 当前商品图最大 10MB，base64 足够；文件解析没有业务场景 |
| 联网搜索 / 阅读链接 | ❌ | RAG 必须只基于平台规则，接外网会破坏「可核验引用」 |
| OCR | ❌ | 成色判断用视觉理解，OCR 只提取文字，信息不够 |
| ASR / TTS | ❌ | 平台没有语音交互场景；如后续做「朗读回答」可接 TTS |

**结论**：不为了用而用。凡是会破坏「只基于平台知识」「成本可控」「可核验」的接口，
即使属于全家桶也不接；用到的是与业务真正匹配的对话、视觉、JSON、缓存与安全。

---

## 八、数据模型（`mysql/init/06-ai-upgrade.sql`）

| 表 / 字段 | 作用 |
|---|---|
| `bobo_price_estimate` | 每次估价快照：图片、描述摘要、成色、区间、置信度、理由、样本量、来源、采纳标记 |
| `bobo_match_request` | 撮合单：买卖双方、预算、诉求、达成价、轮次、双方确认、状态、来源、过期时间 |
| `bobo_match_message` | 协商消息：轮次、角色、动作、价格、正文、来源（model/rule） |
| `bobo_knowledge_doc` | 知识库文档：标题、类目、正文、状态、切片数 |
| `bobo_knowledge_chunk` | 切片：文档、序号、正文、Base64 向量、维度 |
| `bobo_item.ai_*` | AI 成色/建议价/区间/来源/确认时间（卖家采用后写入） |
| `bobo_order.match_id` | 订单关联撮合单，可回溯撮合价来源 |

脚本是**幂等**的：加列/加索引用 `information_schema` 判断 + 动态 SQL，
表用 `CREATE TABLE IF NOT EXISTS`，知识库文档用主键 `ON DUPLICATE KEY UPDATE`。
已有部署可重复执行。

---

## 九、配置

### 9.1 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `BOBO_AI_ENABLED` | `true` | 总开关；关闭后所有 AI 走规则 |
| `BOBO_AI_API_KEY` | 空 | DeepSeek API Key；为空 = 无模型，规则模式 |
| `BOBO_AI_BASE_URL` | `https://api.deepseek.com` | 换代理/网关只改这里 |
| `BOBO_AI_MODEL` | `deepseek-flash` | 文本模型；可选 `deepseek-v4-pro` |
| `BOBO_AI_TIMEOUT_MS` | `15000` | 文本单次超时 |
| `BOBO_AI_VISION_ENABLED` | `true` | 是否启用图像理解 |
| `BOBO_AI_VISION_MODEL` | `deepseek-flash` | 多模态模型；留空复用 `BOBO_AI_MODEL` |
| `BOBO_AI_VISION_MAX_TOKENS` | `900` | 单次估价最大输出 |
| `BOBO_AI_VISION_TIMEOUT_MS` | `30000` | 看图比纯文本慢，单独给超时 |
| `BOBO_AI_MATCH_ROUNDS` | `3` | 撮合最大轮次 |
| `BOBO_AI_MATCH_TIMEOUT_MS` | `30000` | 一次撮合的总预算 |
| `BOBO_AI_GLOBAL_QPS` / `USER_QPS` / `DAILY_QUOTA` | `10` / `2` / `200` | 限流与单用户每日配额 |
| `BOBO_AI_PRICE_INPUT` / `PRICE_OUTPUT` | `2.0` / `8.0` | 元/百万 token，仅用于成本估算 |

### 9.2 后台配置

管理后台「AI 运营 → 模型配置」仍可改 Key、Base URL、文本模型、超时、重试、温度、
限流、配额、单价，**保存即生效，不用重启**。视觉调用复用同一份 Key / Base URL，
模型名由 `bobo.ai.vision-model` 决定（默认 `deepseek-flash`）。

**清空 Key 会同时关闭总开关**：避免环境变量里的旧 Key 悄悄接管（详见 ai.md 第七节）。

---

## 十、安全与合规

1. **Key 只在服务端**：不进仓库、不写前端、不落日志；接口只返回脱敏值。
2. **身份不由模型决定**：所有接口从 `AuthContext` 取 userId，提示词里不传 userId，
   模型输出不参与鉴权。
3. **价格不由前端决定**：撮合价从数据库读，订单服务校验双方确认状态；
   商品发布价仍由卖家填写，AI 只给建议。
4. **模型输出当不可信输入**：JSON 解析 + 字段校验 + 业务边界收敛，不直接拼 SQL/HTML。
5. **上传图片路径校验**：只允许上传根目录内的文件，拒绝外链、目录穿越。
6. **内容安全**：视觉请求开启 `safety_check=high`；原有内容审核与举报链路不变。
7. **数据最小化**：流水只存 SHA-256 摘要，不存商品描述/聊天原文；撮合消息是业务数据，
   只在撮合单内可见（参与者与管理员）。

---

## 十一、成本与可观测性

`bobo_ai_call_log` 新增四个场景，后台「分场景明细」可直接看：

- `price_estimate`：视觉定价
- `match_buyer` / `match_seller`：撮合双方 Agent
- `rag_qa`：知识库问答

每次调用记录 provider（`deepseek-vision` / `spring-ai-deepseek`）、模型、token、
耗时、状态、估算成本。DeepSeek 的上下文缓存命中数在调试日志里可见。

成本控制：

- 视觉每张图最多约 1024 token，最多 3 张；长边压到 1568px 减少上传体积；
- 撮合最多 3 轮、7 次调用（1 次首轮出价 + 3 轮 × 双方各一次）；30 秒预算到了就停；
- RAG 先检索后生成，无命中零 token；
- 全局限流 + 单用户 QPS + 每日配额，和原有场景共用一套额度。

---

## 十二、测试与验收

```bash
# 后端：编译 + 全部单测（离线，Mock 模型）
cd bobo-server
JAVA_HOME=<jdk17> mvn -o test

# 前端：静态/契约校验 + 构建
cd bobo-web
pnpm install
pnpm check
pnpm build
```

新增测试：

| 测试 | 覆盖 |
|---|---|
| `LocalHashEmbeddingProviderTest` | 确定性、语义区分、Base64 可逆、空文本安全 |
| `KnowledgeChunkerTest` | 长度上限、短文档不切、超长句硬切、空内容 |
| `PriceHeuristicsTest` | 成色归一、系数单调、越界收敛、上下限纠正 |
| `MatchRuleEngineTest` | 预算内达成、低预算失败、首轮出价边界、还价边界 |

手工验收（需要 Key）：

```bash
# 1) 能力状态
curl -s http://127.0.0.1:9080/api/ai/features

# 2) 登录拿 token（干净种子的管理员账号；上线后先改密码）
TOKEN=$(curl -s -X POST http://127.0.0.1:9080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"password"}' | jq -r .data.token)

# 3) 知识库问答
curl -s -X POST http://127.0.0.1:9080/api/ai/qa \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"买到假货怎么办？"}'

# 4) 发起撮合（itemId 换成真实在售商品）
curl -s -X POST http://127.0.0.1:9080/api/ai/matches \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"itemId":1,"budgetMin":600,"budgetMax":800,"demandText":"周末当面交易，希望含配件"}'
```

视觉估价需要先上传图片拿到 URL，再 POST `/api/ai/pricing/estimate`；
没有 Key 时会返回 `source=rule`，同样能验收降级链路。

---

## 十三、已有部署升级步骤

```bash
# 1) 执行升级脚本（幂等，可重复执行）
docker compose exec -T bobo-mysql \
  mysql -ubobo -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 bobo \
  < mysql/init/06-ai-upgrade.sql

# 2) 确认环境变量（至少 Key；模型默认已是 deepseek-flash）
#    BOBO_AI_API_KEY=sk-...
#    BOBO_AI_MODEL=deepseek-flash
#    BOBO_AI_VISION_MODEL=deepseek-flash

# 3) 重建后端与前端
docker compose up -d --build --no-deps bobo-server bobo-web

# 4) 启动后自动建知识库索引；也可在后台「AI 运营 → 技术升级」点「重建索引」
curl -s http://<host>/api/ai/features
```

**不执行 06 脚本的后果**：商品/订单主流程仍可用，但估价、撮合、问答会返回
「表未初始化」提示；知识库状态接口会显示表不可用。因此升级必须先跑脚本。

---

## 十四、已知限制与后续

1. **本地哈希向量不理解同义词**：「退款」与「退钱」字面不同，召回可能弱于语义向量。
   这是无外部 embedding 依赖的代价；后续可替换 `EmbeddingProvider` 接语义模型。
2. **撮合是同步请求**：最长 30 秒，用户需要等待。并发高时可改为异步任务 + 轮询/推送。
3. **撮合工具是确定性调用**：不是模型自主 tool calling；适合当前「固定商品 + 固定查询」场景，
   开放式「帮我找替代品」需要引入真正的 tool loop。
4. **视觉每张图最多约 1024 token**：3 张图 + 文本的成本高于纯文本场景，已通过张数与
   长边限制控制；高频估价可再降为 2 张。
5. **全家桶的 Search/Fetch/OCR/ASR/TTS 未接入**：原因见第七节，属于有明确业务场景再做的范围。

---

## 十五、参考

- [DeepSeek Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion)
- [DeepSeek 图像理解](https://api-docs.deepseek.com/zh-cn/guides/vision)
- [DeepSeek 上下文硬盘缓存](https://api-docs.deepseek.com/zh-cn/guides/kv_cache)
- [DeepSeek 全家桶应用 API](https://api-docs.deepseek.com/zh-cn/news/news261113/)
- [项目 AI 基础能力说明](ai.md)
- [接口契约](api.md)
