# bobo 校园二手交易平台 —— AI 能力设计与面试要点

> 本文是 AI 模块的**唯一事实来源**：设计理由、降级策略、成本与可观测性、安全边界、验收数据。
> 接口契约见 [api.md](api.md)，分层与鉴权见 [architecture.md](architecture.md)，部署见 [deploy.md](deploy.md)。

## 一、做了什么，以及刻意不做什么

**三个场景，全部咬住已有业务，不做"聊天机器人挂件"**：

| # | 场景 | 解决的原有问题 | 用到的 AI 能力 |
|---|---|---|---|
| ① | **AI 发布助手** | 卖家写标题/描述成本高，描述质量参差导致审核返工 | 结构化抽取（JSON Schema 约束输出）+ 校验失败重试 + 规则回退 |
| ② | **自然语言检索** | `SearchView` 只有 `LIKE` 关键词，"500 以内能当面交易的山地车"搜不出东西 | 检索意图解析（自然语言 → 结构化槽位）+ 对候选集做 LLM 重排 |
| ③ | **内容审核 + 运营 Copilot** | 审核全靠人工看；看板只有数字，没有结论 | 内容风险评分（带幂等缓存）+ 数据转自然语言报告 |

**刻意不做**（这些是主动取舍，面试被追问时就是加分项）：

- ❌ **不做 embedding / 向量库 / RAG**：DeepSeek 不提供 embedding 接口。硬上就得再引一家厂商 + Redis Stack，
  运维面扩大而收益不确定（本场景候选集只有几百条，SQL 过滤已足够）。所以②的措辞是
  **"意图解析 + 重排"**，不是"向量召回"，简历与文档都不吹这一点。
- ❌ **不做以图搜同款 / 图片生成标题**：DeepSeek V3/R1 是纯文本模型，不支持图像输入。
- ❌ **不做多轮对话助手**：私信场景的 Tool Calling 助手工作量大、演示价值不如①③直接，留作后续扩展。

## 二、依赖版本（为什么必须一起升）

| 依赖 | 版本 | 说明 |
|---|---|---|
| `spring-boot-starter-parent` | **3.4.5** | 与 `spring-ai-bom` 1.0.0 对齐：该版本的 starter 内部冻结 `spring-boot-starter:3.4.5`，父版本相同才不会混版 |
| `spring-ai-bom` | **1.0.0** | `dependencyManagement` 以 `import` 方式引入，统一 Spring AI 全家版本 |
| `spring-ai-starter-model-deepseek` | 1.0.0（由 BOM 管） | 只引对话模型，不引 vector-store |
| `mybatis-plus-spring-boot3-starter` | **3.5.12** | 3.5.7 在 Boot 3.4 下启动期报错，3.5.9+ 才兼容 |
| `springdoc-openapi-starter-webmvc-ui` | **2.8.17** | 2.3.0 依赖 Boot 3.2 的 `ControllerAdviceBean` 构造方式，3.4 下必须升 |

> Spring AI 各版本冻结的 Boot 版本是硬事实：1.0.0 → 3.4.5，1.0.9 → 3.5.15。
> 想用 2.0.0 就得 Boot 4.1.0，那是另一个量级的升级，本项目不做。

**一个容易踩的坑**：`spring-ai-starter-model-deepseek` 在本项目里**基本不提供开箱可用的自动配置**，
所以 `AiChatConfig` 手写 `ChatInvoker` Bean，而不是注入一个现成的 `ChatModel`。
手写的好处是边界清晰——**整个工程的 Spring AI 类型只出现在 `com.bobo.ai.AiChatConfig` 这一个文件里**，
其余代码只依赖自定义的 `ChatInvoker` 接口，换厂商时改动面就是一个类。

## 三、架构：三层解耦

```
业务场景（发布助手 / 检索解析 / 内容审核 / 运营 Copilot）
        │  只依赖自定义接口，不 import 任何 Spring AI 类型
        ▼
   AiExecutor  ── 统一横切：门控 → 限流 → 配额 → 调用 → 解析校验 → 落流水
        │
        ▼
   ChatInvoker（接口） ──┬── SpringAiDeepSeekInvoker（真模型）
                        └── NoopChatInvoker（未配置 key 时的显式占位）
```

**为什么必须有 `ChatInvoker` 这层**：

1. **测试不烧钱、不依赖外网**：`MockChatInvoker` 预置回复，解析容错、降级路径、JSON 契约全部可在 CI 里确定性验证；
2. **"没有 key" 是合法状态**：容器注入 `NoopChatInvoker`，业务代码不写 `if (model != null)`；
3. **换厂商只加一个实现类**。

**`AiExecutor` 是唯一入口**，任何场景都不许绕过它直接调模型。它把 AI 的横切关注点收在一处，
业务代码只写两件事：提示词 + 解析函数（外加一条规则兜底实现）：

```java
AiOutcome<ItemDraft> outcome = aiExecutor.execute(
        AiCallLog.SCENE_PUBLISH_DRAFT,
        SYSTEM_PROMPT, userPrompt,
        raw -> jsonParser.parse(raw, this::toDraft),  // 解析 + 校验，非法返回 null 触发降级
        () -> rules.draftFrom(text));                // 规则兜底，保证一定有结果
outcome.value();   // 一定有值
outcome.source();  // "ai" / "rule" —— 前端据此打标签，不假装是 AI 生成的
```

## 四、降级策略：四级，且每一级都可解释

| 级别 | 触发条件 | 行为 | 用户看到 |
|---|---|---|---|
| 1 | `bobo.ai.enabled=false` 或未配置 `api-key` | 不调用模型、**不写流水** | 规则结果 + `source=rule` |
| 2 | 全局限流 / 单用户限流命中 | 不调用模型 | 规则结果 + "请求过于频繁" |
| 3 | 今日配额用尽 | 不调用模型 | 规则结果 + "今日使用次数已达上限" |
| 4 | 调用超时 / 网络失败 / 输出无法解析 | 记 `failed` 流水 | 规则结果 + "模型暂时不可用" |

**关键设计**：降级不是异常分支，而是**主路径的一部分**——`AiExecutor.execute()` 的返回值一定有值。
因此"模型挂了"在业务代码里不存在特殊处理，也就不会出现"AI 一抖，发布页白屏"。

`source` 字段必须透传到前端并在 UI 上标注：**用户有权知道这个结论是模型给的还是规则推的**。
这也是排查线上问题时区分"模型变笨"和"降级生效"的唯一线索。

## 五、可观测性与成本（可写进简历的指标来源）

`bobo_ai_call_log` 一张表回答四个问题：

| 问题 | SQL 依据 |
|---|---|
| 各场景成功率 / 降级率 | `status` 分组计数 |
| 各场景耗时（P50/P95/P99） | `latency_ms` |
| 一次调用花多少钱 | `estimated_cost` 按 `bobo.ai.pricing` 单价折算 |
| 是否重复调用同一内容 | `input_hash` 去重 |

**隐私纪律**：流水表**只存 SHA-256 前 16 位摘要**（`input_hash` / `output_hash`），不存正文。
既能做幂等与缓存判定，又不会把用户发布的正文、私信内容沉进日志表。

**两个必须注意的实现细节**（都是踩过的）：

1. **`@Async` 不能同类自调用**：`@Async` 靠代理生效，同一个类里方法互调会绕过代理，
   写出来是"看起来异步、实际同步"。所以异步写库单独放在 `AiCallLogWriter`。
2. **异步线程读不到 `AuthContext`**：`AuthContext` 是请求级 ThreadLocal，
   在新线程里读只能拿到 `null`，会让流水表 `user_id` 全空，
   进而使"按 user_id 计数的每日配额"永久失效（表现为配额永远用不完）。
   因此 `AiCallRecorder.recordAsync` 必须在**原线程**取下 `userId` 再传给异步方法。

**成本控制三件套**：本地关键词规则前置过滤（命中即不调模型）→ 内容 hash 缓存（同一内容复用结论）→
令牌桶限流 + 每日配额（全局 + 单用户两层）。`AiRateLimiter` 自己实现，不引 Guava/Redisson：
单实例部署令牌桶足够，多实例时换成 Redis 原子计数即可，调用方无需改动。

## 六、安全边界

- **身份从 `AuthContext` 注入，绝不让模型传 `userId`**：模型只负责生成内容，不参与鉴权；
  若让提示词带 `userId`，就等于把越权入口交给了模型输出。
- **运营 Copilot 只读**：不向模型暴露任何写操作（审核通过、封禁、改价都不给），
  避免"模型幻觉触发真实业务动作"。
- **模型输出一律当不可信输入处理**：结构化输出必须过 JSON 解析 + 字段校验，
  不通过就走规则降级，绝不直接把模型文本拼进 SQL 或前端 `v-html`。
- **`api-key` 只从环境变量注入**（`BOBO_AI_API_KEY`），不写进仓库、不写进 `.env.example` 的真实值。

## 七、配置项速查（`bobo.ai.*`）

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | `BOBO_AI_ENABLED` | `true` | 关掉后全层走规则，不发起任何外部调用 |
| `api-key` | `BOBO_AI_API_KEY` | 空 | 空 = 无 AI，自动降级 |
| `base-url` | `BOBO_AI_BASE_URL` | `https://api.deepseek.com` | 换代理/网关只改这里 |
| `model` | `BOBO_AI_MODEL` | `deepseek-chat` | 另可选 `deepseek-reasoner`（更慢更贵） |
| `timeout-ms` | `BOBO_AI_TIMEOUT_MS` | `15000` | 连接与读超时都按此设置 |
| `max-attempts` | `BOBO_AI_MAX_ATTEMPTS` | `1` | 失败重试次数，不含降级那一跳 |
| `temperature` | `BOBO_AI_TEMPERATURE` | `0.2` | 结构化抽取要稳定，故调低 |
| `rate.global-qps` | `BOBO_AI_GLOBAL_QPS` | `10` | 全局限流 |
| `rate.user-qps` | `BOBO_AI_USER_QPS` | `2` | 单用户限流 |
| `rate.daily-quota` | `BOBO_AI_DAILY_QUOTA` | `200` | 单用户每日调用次数上限 |
| `pricing.input-per-million` | `BOBO_AI_PRICE_INPUT` | `2.0` | 元/百万 token，仅用于成本估算 |
| `pricing.output-per-million` | `BOBO_AI_PRICE_OUTPUT` | `8.0` | 同上 |

**接入步骤**：

```bash
# 1) 申请 key：https://platform.deepseek.com/api_keys
# 2) 注入环境变量（不要写进仓库）
export BOBO_AI_API_KEY=sk-xxxxxxxx
# 3) 启动后看日志确认形态；出现下面这行说明已启用
#    AI 能力已启用：model=deepseek-chat, baseUrl=https://api.deepseek.com, ...
#    未配 key 时会打印：AI 能力降级为规则模式：未配置 bobo.ai.api-key，已降级为规则模式
```

## 八、数据库

新增 3 张表，脚本 `mysql/init/03-ai.sql`（结构）与 `04-seed-ai.sql`（演示数据），
由 MySQL 官方镜像按文件名顺序自动执行，**必须在 01/02 之后**。

| 表 | 作用 | 关键列 |
|---|---|---|
| `bobo_ai_call_log` | 调用流水：成功率、耗时、token、成本 | `scene` / `status` / `latency_ms` / `estimated_cost` / `input_hash` |
| `bobo_ai_suggestion_feedback` | 建议与采纳：**采纳率的唯一事实来源** | `suggestion_type` / `accepted` / `suggested_value` / `final_value` |
| `bobo_ai_moderation` | 内容审核结论：事前风险判定 | `content_hash`(幂等键) / `risk_level` / `decision_source` |

**三张表都刻意不加 `del_flag`**：它们属于审计数据，只追加不软删；
要清理按时间归档，避免"逻辑删除"把统计口径搞脏。

## 九、测试与评测

| 测试 | 覆盖内容 | 是否需要真模型 |
|---|---|---|
| `SafeJsonParserTest` | Markdown 围栏、前后夹带解释、字符串内括号、截断、非对象 JSON | ❌ 纯函数 |
| `AiExecutorTest` | 成功 / 失败降级 / 输出不可解析 / 无 key 短路 / noop 短路 / 配额用尽 / 限流 / 成本折算 / 摘要稳定性 | ❌ 用 `MockChatInvoker` |

**为什么这些必须离线可测**：AI 功能的失败形态（偶发空白、悄悄一直走规则）极难从现象反推，
只能靠单元测试把每条路径钉死。真实模型只用于**离线评测**——对同一批 query 跑
`LIKE` / `LLM 解析槽位` / `LLM 解析 + 重排` 三种方案并统计前 10 命中率，
产出的是可以写进简历的对比数据，而不是"感觉更准了"。

### 9.1 真实模型对照评测（`DeepSeekLiveEvaluationTest`）

设了 `BOBO_AI_API_KEY` 才会运行，否则整个测试类被跳过（不烧 token、不影响他人构建）：

```bash
export BOBO_AI_API_KEY=sk-xxxx        # 或用进程级环境变量，不要写进任何文件
mvn test -Dtest=DeepSeekLiveEvaluationTest
```

**实测结果（2026-10 首轮，deepseek-chat，8 条校园口语样本）**：

| 指标 | 朴素关键词规则 | 真实模型 |
|---|---|---|
| 平均解析出的条件项 | 2.0 项 | **3.0 项** |
| 分类识别命中 | **0 / 8** | **8 / 8** |
| 价格区间识别 | 4 / 8 | 4 / 8 |
| 成色识别 | 2 / 8 | 2 / 8 |
| 交易方式识别 | 2 / 8 | 2 / 8 |
| 平均耗时 | ~0 ms（纯内存） | **1102 ms / 次** |

**结论与取舍**：
- 差距**全部来自分类识别**——"平板 → 数码""机械键盘 → 数码""山地车 → 运动""小冰箱 → 生活"，
  这些依赖语义理解，关键词匹配做不到，因为用户不会在 query 里写分类名。
  价格/成色/交易方式三项两者持平，说明规则在这些结构化字段上已经够用，
  **模型的价值有明确边界，不是万能**——这一点比笼统说"AI 效果更好"更值得在面试里讲。
- 代价是每次多 1.1 秒且要花 token。因此检索解析加了按 query 文本的解析缓存，
  并对能靠规则确定的场景直接走规则，不无脑上模型。
- 发布助手实测：一句话 → 规范标题 + 分类 + 成色 + 标签，耗时约 1.1 秒，来源 `ai`；
  内容审核实测：违规文案被本地规则直接判为高风险（未消耗 token），正常文案由模型判为低风险。

### 9.2 端到端探针（`mysql/e2e/verify-ai.ps1`）

单测用 Mock 模型，证明不了"真实链路 + 真实数据库"是否可用，所以另有一个可复现的探针：
起一个独立 MySQL（`mysql/e2e/99-verify-seed.sql` 造 12 件在售商品），启动应用后执行

```powershell
pwsh -File mysql/e2e/verify-ai.ps1 -BaseUrl http://127.0.0.1:19099
```

覆盖：AI 状态 → 发布助手（真实模型）→ 检索解析 → 用解析槽位做真实查询 → 采纳反馈闭环 → 未知建议类型被拒绝。
实测 **15 项全绿**，并顺手拿到最有说服力的一组对照：

| 查法 | 命中 |
|---|---|
| `LIKE` 整句「500以内能当面交易的山地车」 | **0 件** |
| 模型解析出 keyword=山地车 / maxPrice=800 / tradeType=仅当面 后结构化查询 | **1 件（捷安特 ATX660 山地车）** |

### 9.3 三个只有真跑才会暴露的缺陷（都已修复）

这几条是本模块最值得复述的工程经验——**单元测试全都发现不了**，
因为缺陷在"真实模型行为 / 真实容器启动 / 真实依赖版本"这一层。

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 应用**完全启动不了**：`BeanDefinitionOverrideException: bean 'aiExecutor' ... already defined` | 线程池 Bean 名 `aiExecutor` 与业务组件 `AiExecutor` 的默认 Bean 名（类名首字母小写）撞名 | 线程池改名 `aiTaskExecutor`；两者本就是不同层次的东西，名字必须能区分 |
| 2 | 未配置 key 时应用**依然启动失败**：`DeepSeek API key must be set` | Spring AI 的 `DeepSeekChatAutoConfiguration` 会无条件创建 `DeepSeekApi`，其对空 key 直接断言抛异常——与"AI 是可选增强"的设计前提相反 | 在启动类 `@SpringBootApplication(exclude = ...)` 排除该自动配置，改为 `AiChatConfig` 按条件手工装配 |
| 3 | 升级 Boot 3.4 后启动失败：`Invalid mapping pattern detected: /swagger-ui/**/*swagger-initializer.js` | springdoc **2.8.x** 会注册含 `**` 且后面还有模式段的映射，而 Spring Framework 6.2 的 `PathPatternParser` 不允许 | 降到 springdoc **2.7.0**（官方声明支持 Boot 3.4.x 的版本线） |

**另外两处提示词/契约缺陷**（同属"实测才能发现"）：

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 4 | 发布助手 `categoryId` **恒为空**（5/5 全丢），连带价格建议不可用（它依赖分类） | 系统提示词里**根本没定义 `categoryId` 字段**——模型只输出被要求的字段。解析侧的"越界分类置空"校验没错，它只是拿不到值 | 把 `categoryId` 写成必填项并给映射示例（iPad/键盘→数码、教材→图书、自行车→运动…）。**修后 5/5**，价格建议随之生效（数码中位价 800 元来自库内 8 件真实在售样本） |
| 5 | 检索「500 以内能当面交易的山地车」返回 0 条，看起来像解析失败 | 解析**完全正确**：库里那辆车 700 元，超预算，筛空是正确行为。真问题是**空结果没有解释**，用户分不清"没有这类商品"和"AI 理解错了" | 槽位与 `explanation` 一起回显到搜索栏供用户核对/手改；探针里补"预算 800"的对照样本证明链路可命中 |

**教训**：AI 功能"没效果"有相当比例不是模型能力问题，而是**契约没写清**（第 4 条）
或**反馈没给足**（第 5 条）。所以提示词要当接口契约维护，
每条 AI 能力都要有一个可复现的端到端探针。

## 十、实测数据与简历表述

### 10.1 本地环境（独立 MySQL 容器 + 12 件在售商品）

以下数字全部来自本机实测（2026-10，deepseek-chat），不是估算值。
复现方式：`mvn test`（60 项）+ `mysql/e2e/verify-ai.ps1`（24 项）。

| 指标 | 实测值 |
|---|---|
| 离线单测（Mock 模型，无需 key） | **57 项全绿** |
| 真实模型评测（需 key） | **3 项全绿** |
| 端到端探针（真实模型 + 真实 DB） | **24 项全绿** |
| 发布助手分类识别率 | **5 / 5**（提示词修复前为 **0 / 5**） |
| 检索意图解析：分类识别 | 朴素规则 **0 / 8** → 模型 **8 / 8** |
| 检索意图解析：平均条件项 | 规则 2.0 项 → 模型 3.0 项 |
| 自然语言检索命中 | `LIKE` 整句 **0 件** → 解析后结构化查询 **1 件（精确命中）** |
| 调用成功率 / 降级率 | **96.4% / 3.6%**（28 次真实调用，1 次失败降级） |
| 真实调用耗时 | P50 **950 ms** / P95 **2716 ms** / P99 **2958 ms** |
| 累计成本 | 28 次调用共 **¥0.0425**（约 0.0015 元/次） |
| 发布建议采纳率 | **100%（5 / 5）**，指标由 `bobo_ai_suggestion_feedback` 算出而非估算 |

**可直接写进简历的条目**（数字与上表严格对应，面试可当场复现）：
> - 基于 **Spring AI 1.0 + DeepSeek** 为校园二手平台实现三项 AI 能力：发布助手（结构化抽取）、
>   自然语言检索（意图解析）、内容审核与运营 Copilot；
> - 发布助手以「**提示词即契约**」约束结构化输出，配合 JSON 容错解析与字段校验
>   （不存在的分类ID、非法成色一律拦下），分类识别率达 **5/5**，
>   并给出基于库内真实在售分位的价格建议（非模型猜测）；
> - 自然语言检索用「**LLM 解析检索槽位 → 复用既有 MyBatis-Plus 查询 → 槽位回显可手改**」替代朴素 `LIKE`：
>   整句搜索命中从 **0 件提升到精确命中**，分类识别 **0/8 → 8/8**，平均多解析出 1 个检索条件；
> - 设计 **四级降级**（无 key / 限流 / 配额 / 调用失败）保证 AI 不可用时业务零中断，
>   实测调用成功率 **96.4%**、降级 **3.6%**；未配置 api-key 时应用正常启动、全站 102 个接口行为不变；
> - 建立 AI **可观测性与成本治理**：调用流水记录 token/耗时/状态并折算成本（实测 **0.0015 元/次**），
>   令牌桶限流 + 每日配额防刷，用「规则前置过滤 + 内容哈希缓存」避免无谓调用；
> - 用 **MockChatInvoker** 让 **57 项 AI 单测完全离网、确定性运行**；另建真实模型评测集与端到端探针，
>   实测中定位并修复 **5 处只有真跑才会暴露的缺陷**（Bean 重名导致启动失败、Spring AI 自动配置强制要求 key、
>   springdoc 2.8 与 Boot 3.4 映射不兼容、提示词缺字段导致分类恒空、空结果无解释）。

### 10.2 线上部署实测（Docker，真实业务数据）

部署步骤见 [deploy.md](deploy.md) 第 2.1 节（已有部署升级 AI 版本需手工建表 + 补环境变量 + 重建两个镜像）。
对**线上站点**（`http://localhost`，16 分类 / 8 个有在售商品）跑完两个探针后的实测值：

| 指标 | 线上实测值 |
|---|---|
| AI 能力状态 | `available=true, degraded=false, model=deepseek-chat` |
| 端到端探针 `verify-ai.ps1` | **22 / 23 通过**（唯一失败项是固定数据集断言与线上分类体系不符，见下） |
| 自适应探针 `verify-ai-adaptive.ps1` | **6 / 6 往返命中**（解析出的关键词+价格上限均能查到商品） |
| 对照：LIKE 整句 | **0 命中**（三条样本全部为 0） |
| 发布建议采纳率 | **77.8%（7 / 9）** |
| 检索改写采纳率 | **80.0%（4 / 5）** |
| 调用成功率 / 降级率 | **85.7% / 14.3%**（14 次调用） |
| 耗时（线上真实数据） | P50 **1870 ms** / P95 **3260 ms** / P99 **5240 ms** |
| 累计估算成本 | 14 次调用 **¥0.0272** |

**线上那条"失败"项值得单独说明**：`verify-ai.ps1` 的断言基于固定验证数据集（山地车归「运动」、售价 700），
而线上分类体系不同——「山地车」归**代步工具(id=12)**，该分类下的车超预算，所以"0 命中"其实是**正确行为**，
不是解析错误。这正说明**固定数据集的断言不能直接搬到生产**，因此补了自适应探针：
它先读线上真实分类与价格区间，再构造"必然有解"的自然语言查询，于是 6/6 全部命中。
**这条经验比数字本身更值得讲**：验证脚本的断言必须建立在被测环境的真实数据上，否则会把正确行为误判成缺陷。

### 10.3 浏览器验证（真实 Chromium + CDP）

接口返回 200 不等于页面能用（本工程历史上就出现过"接口全 200 但菜单点不进去"）。所以用仓库自带的
真实浏览器探针对线上站点跑了一遍（`probe-admin.mjs`，本次把新增的 `/admin/ai` 加进菜单断言表，
断言用「指标卡片」而非「表格行」——审核记录表可能为空，用表格行会把好页面判成失败）：

```bash
# 1) 起带调试端口的浏览器
msedge.exe --headless=new --remote-debugging-port=9222 --user-data-dir=%TEMP%\bobo-probe about:blank
# 2) 跑探针（BOBO_SITE 默认 http://127.0.0.1）
node bobo-web/scripts/probe-admin.mjs
```

**结果全绿（后台 9 页 + 9 次真实点击 + 4 个前台页面）**，其中 AI 相关：

| 检查 | 结果 |
|---|---|
| `/admin/ai` 打开与渲染 | PASS（指标卡片 **6 个**，内容长度 22568） |
| `/admin/ai` 菜单高亮 | PASS（高亮第 **8** 项，与菜单声明顺序一致） |
| 真实点击第 8 项菜单 | PASS → 跳转 `/admin/ai` |
| 网络 4xx / JS 异常 | 0 |





