# bobo 校园二手交易平台 —— API 契约

> 本文件是**前后端唯一契约**。前端 `bobo-web` 的所有请求路径、参数名、响应字段必须与此一致；
> 后端 Controller 的路由也必须与此一致。任何一侧要改接口，先改这里。

## 一、通用约定

| 项目 | 约定 |
|---|---|
| 统一前缀 | 前台 `/api/**`，管理端 `/api/admin/**` |
| 响应结构 | `{ "code": 200, "msg": "操作成功", "data": ... }`，前端只判断 `code === 200` |
| 错误码 | `200` 成功 / `400` 参数错误 / `401` 未登录或 token 失效 / `403` 无权限 / `404` 资源不存在 / `500` 业务异常（msg 可直接展示）/ `999` 系统异常 |
| 分页请求 | `pageNum`（默认 1）、`pageSize`（默认 10，最大 100） |
| 分页响应 | `{ records: [], total, pageNum, pageSize, pages }` |
| 鉴权 | 请求头 `Authorization: Bearer <token>`；token 由登录接口返回 |
| 免登录接口 | 见各接口表格「鉴权」列标注 `匿名` 的接口 |
| 管理端鉴权 | `/api/admin/**` 由 JWT 拦截器统一校验管理员身份，前端无需额外传参 |
| 时间格式 | `yyyy-MM-dd HH:mm:ss`（Jackson 全局配置） |
| 金额 | 字符串形式的十进制（如 `"2680.00"`），前端不做浮点运算 |

## 二、鉴权与账号

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册。body: `RegisterDTO`；带 `adminCode` 且与 `BOBO_ADMIN_REGISTER_CODE` 一致时创建管理员 | 匿名 |
| POST | `/api/auth/login` | 登录。body: `{username, password}`，返回 `{token, expiresIn, user}` | 匿名 |
| POST | `/api/auth/logout` | 退出（前端清 token，后端仅记录） | 登录 |
| GET | `/api/auth/me` | 当前登录用户信息 | 登录 |
| PUT | `/api/auth/profile` | 更新资料。body: `{nickname, avatar, phone, email, campus, college, grade, studentNo, bio}` | 登录 |
| PUT | `/api/auth/password` | 修改密码。body: `{oldPassword, newPassword}` | 登录 |
| POST | `/api/auth/verify` | 提交学生认证。body: `{studentNo, college}` → 状态置「认证中」 | 登录 |
| GET | `/api/auth/overview` | 我的概览：`{itemCount, onSaleCount, soldCount, orderBuyCount, orderSellCount, favoriteCount, unreadMessage, unreadNotice}` | 登录 |

## 三、分类

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/api/categories` | 启用中的分类列表（含在售数量 `itemCount`） | 匿名 |

## 四、商品

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/api/items` | 商品分页。query: `keyword, categoryId, status, minPrice, maxPrice, campus, conditionLevel, tradeType, sortBy, onlyFavorite, sellerId, excludeSellerId, pageNum, pageSize`。<br>`sortBy`: `new`(默认) / `priceAsc` / `priceDesc` / `want` / `hot`。<br>前台只传 `status=1`；**后台管理不传 status 表示全部** | 匿名（带 token 时返回 `favorited`） |
| GET | `/api/items/{itemId}` | 商品详情（含图片列表、卖家信息、卖家其他在售商品），同时浏览量 +1 | 匿名 |
| POST | `/api/items` | 发布商品。body: `ItemDTO`，发布后状态为「待审核」 | 登录 |
| PUT | `/api/items/{itemId}` | 编辑商品（仅本人，且状态非「已售」）；编辑后重新进入待审核 | 登录 |
| DELETE | `/api/items/{itemId}` | 删除商品（仅本人或管理员，逻辑删除，同时清理收藏与图片） | 登录 |
| PUT | `/api/items/{itemId}/off-shelf` | 下架（仅本人） | 登录 |
| PUT | `/api/items/{itemId}/on-shelf` | 重新上架（仅本人，回到待审核） | 登录 |
| POST | `/api/upload/image` | 上传单张图片（multipart `file`），返回 `{url, name, size}` | 登录 |
| POST | `/api/upload/images` | 批量上传（multipart `files`），返回 `[{url, name, size}]` | 登录 |

## 五、收藏（想要）

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/favorites/toggle/{itemId}` | 收藏/取消收藏（幂等切换），返回 `{favorited: true/false, wantCount: n}` | 登录 |
| GET | `/api/favorites` | 我的收藏分页（返回 `ItemCardVO` 列表） | 登录 |
| GET | `/api/favorites/check/{itemId}` | 是否已收藏，返回 `{favorited: bool}` | 登录 |

## 六、私信

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/messages/conversations` | 打开/创建会话。body: `{itemId, content}`，返回 `ConversationVO`；`content` 非空时同时发出第一条消息 | 登录 |
| GET | `/api/messages/conversations` | 我的会话列表。query: `role=all|buyer|seller` | 登录 |
| GET | `/api/messages/conversations/{id}` | 进入会话：校验归属、把发给我的消息置为已读、返回 `{conversation, peer, item}` | 登录 |
| GET | `/api/messages/conversations/{id}/messages` | 会话消息分页（**按时间倒序**，第一页是最新消息；前端渲染前反转） | 登录 |
| POST | `/api/messages/send` | 发送消息。body: `{conversationId, content}` | 登录 |
| GET | `/api/messages/unread-count` | 未读消息总数，返回 `{count}` | 登录 |

## 七、订单

订单状态机：

```
0 待付款 ──支付──▶ 1 待发货 ──发货──▶ 2 待收货 ──确认收货──▶ 3 待评价 ──评价──▶ 4 已完成
   │                  │                 │
   └──取消──▶ 5 已取消 └──申请退款──▶ 6 退款中 ──卖家同意──▶ 7 已退款
当面交易（tradeType=1）跳过 1、2：0 待付款 ──支付──▶ 3 待评价 ──评价──▶ 4 已完成
```

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/orders` | 下单。body: `{itemId, tradeType, addressId, tradePlace, tradeTime, remark}`；校验商品在售、非本人商品；下单会把商品从「在售」**原子推进到「已售」**（`compareAndSetStatus`），因此并发下单只有一个人能成功，其余会收到「手慢啦，这件宝贝已被抢先下单」。代价：先下单不付款会占用商品，需卖家取消（或后续加超时自动取消任务）才能重新上架 | 登录 |
| POST | `/api/orders/{orderId}/pay` | 模拟支付（校园内平台不接真实支付），状态 → 待发货/待评价 | 买家 |
| POST | `/api/orders/{orderId}/deliver` | 发货。body: `{logisticsCompany, logisticsNo}`，仅邮寄订单 | 卖家 |
| POST | `/api/orders/{orderId}/confirm` | 确认收货 → 待评价 | 买家 |
| POST | `/api/orders/{orderId}/cancel` | 取消订单。body: `{reason}`；待付款时买卖双方均可，之后仅卖家可 | 买卖双方 |
| POST | `/api/orders/{orderId}/refund` | 申请退款。body: `{reason}`，状态 → 退款中。**允许申请的状态是 1 待发货 / 2 待收货 / 3 待评价**（0 待付款请走取消；4/5/7 是终态） | 买家 |
| POST | `/api/orders/{orderId}/refund/agree` | 同意退款 → 已退款，商品恢复在售 | 卖家 |
| GET | `/api/orders` | 订单分页。query: `role=buy|sell|all, status, keyword` | 登录 |
| GET | `/api/orders/{orderId}` | 订单详情（含买卖双方昵称与电话、`myRole`、`reviewed`） | 买卖双方 |
| GET | `/api/orders/counts` | 我的订单角标：`{unpaid, waitDeliver, waitReceive, waitReview, finished, cancelled, refunding}` | 登录 |

## 八、评价

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/reviews` | 提交评价。body: `{orderId, score, content}`；订单必须属于我且处于「待评价」，双方各评一次；**买家评价后订单即 → 已完成**（`peerReviewed \|\| isBuyer`，不强制等卖家）；被评方信用分按评分加减（4-5 星 +1，1-2 星 -1） | 登录 |
| GET | `/api/reviews` | 评价分页。query: `toId, fromId, itemId, status, keyword` | 匿名 |
| GET | `/api/reviews/my` | 我发出的评价 | 登录 |

## 九、地址

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/api/addresses` | 我的地址列表（默认地址排最前） | 登录 |
| POST | `/api/addresses` | 新增地址（`isDefault=1` 时自动取消其他默认） | 登录 |
| PUT | `/api/addresses/{addressId}` | 编辑地址 | 登录 |
| DELETE | `/api/addresses/{addressId}` | 删除地址 | 登录 |
| PUT | `/api/addresses/{addressId}/default` | 设为默认地址 | 登录 |

## 十、用户主页与通知

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/api/users/{userId}` | 用户公开主页：资料 + 信用分 + 平均评分 + 在售/已售数量 | 匿名 |
| GET | `/api/users/{userId}/items` | 该用户的商品分页（query 同 `/api/items`） | 匿名 |
| GET | `/api/users/{userId}/reviews` | 该用户收到的评价分页 | 匿名 |
| GET | `/api/notices` | 我的通知 + 全站公告分页（query: `type, isRead`） | 登录 |
| GET | `/api/notices/unread-count` | 未读通知数 | 登录 |
| PUT | `/api/notices/{noticeId}/read` | 标记已读 | 登录 |
| PUT | `/api/notices/read-all` | 全部标记已读 | 登录 |

## 十一、举报

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/reports` | 提交举报。body: `{targetType: item\|user\|review, targetId, reason, detail}` | 登录 |
| GET | `/api/reports/my` | 我提交的举报 | 登录 |

## 十二、管理端 `/api/admin/**`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/dashboard` | 数据概览：`{userTotal, userToday, itemTotal, itemPending, itemOnSale, itemSold, orderTotal, orderToday, gmv, reviewTotal, avgScore, reportPending, messageTotal, favoriteTotal}` |
| GET | `/api/admin/dashboard/trend` | 近 N 天趋势：query `days=7`，返回 `[{day, orderCount, amount}]` |
| GET | `/api/admin/dashboard/category` | 分类商品分布：`[{categoryName, total}]` |
| GET | `/api/admin/dashboard/status` | 商品状态分布：`[{status, total}]` |
| GET | `/api/admin/items` | 商品管理分页（query 同 `/api/items`，可传 `status`，不传为全部） |
| POST | `/api/admin/items/{itemId}/audit` | 审核。body: `{pass: true/false, reason}`；通过 → 在售，驳回 → 已驳回并写入原因 |
| PUT | `/api/admin/items/{itemId}/off-shelf` | 强制下架 |
| PUT | `/api/admin/items/{itemId}/on-shelf` | 恢复上架 |
| DELETE | `/api/admin/items/{itemId}` | 删除商品（逻辑删除） |
| PUT | `/api/admin/items/batch/off-shelf` | 批量下架。body: `{ids: []}` |
| PUT | `/api/admin/items/batch/audit` | 批量通过审核。body: `{ids: []}` |
| GET | `/api/admin/users` | 用户管理分页：query `keyword, role, status, verifyStatus, campus` |
| GET | `/api/admin/users/{userId}` | 用户详情（含统计） |
| PUT | `/api/admin/users/{userId}/status` | 封禁/解封。body: `{status: 0\|1, reason}` |
| PUT | `/api/admin/users/{userId}/verify` | 审核学生认证。body: `{verifyStatus: 2\|3, reason}` |
| PUT | `/api/admin/users/{userId}/credit` | 调整信用分。body: `{creditDelta, reason}` |
| GET | `/api/admin/orders` | 订单管理分页：query `role=all, status, keyword` |
| GET | `/api/admin/reviews` | 评价管理分页：query `keyword, status` |
| PUT | `/api/admin/reviews/{reviewId}/hide` | 隐藏评价 |
| PUT | `/api/admin/reviews/{reviewId}/show` | 恢复显示 |
| DELETE | `/api/admin/reviews/{reviewId}` | 删除评价（逻辑删除） |
| GET | `/api/admin/reports` | 举报管理分页：query `targetType, status` |
| PUT | `/api/admin/reports/{reportId}/handle` | 处理举报。body: `{handled: bool, remark, offShelfItem: bool}` |
| GET | `/api/admin/categories` | 全部分类（含停用，含商品数） |
| POST | `/api/admin/categories` | 新增分类。body: `{name, icon, sort, parentId, status}` |
| PUT | `/api/admin/categories/{categoryId}` | 编辑分类 |
| DELETE | `/api/admin/categories/{categoryId}` | 删除分类（该分类下仍有商品时拒绝） |
| GET | `/api/admin/notices` | 公告列表分页 |
| POST | `/api/admin/notices` | 发布公告/通知。body: `{title, content, userId(0=全站), type}` |
| DELETE | `/api/admin/notices/{noticeId}` | 删除公告 |

## 十三、AI 能力 `/api/ai/**`

> 设计理由、降级策略与配置见 [ai.md](ai.md)。**所有接口都有规则降级**：
> 未配置 `BOBO_AI_API_KEY` 时依然返回 200 与可用结果，响应里的 `source` 字段标明是 `ai` 还是 `rule`。

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/ai/publish/draft` | **发布助手**：把卖家草稿补全为结构化建议。body: `{text, categoryId?}`；返回 `{title, categoryId, categoryName, conditionLevel, tags, priceSuggestion:{low,mid,high}, riskFlags, source, degradeNote}`。只回填表单，**不自动提交** | 登录 |
| POST | `/api/ai/search/parse` | **检索意图解析**：自然语言 → 结构化检索槽位。body: `{query}`；返回 `{keyword, categoryId, priceMin, priceMax, conditionLevel, campus, tradeType, sortBy, source}`，前端回显到搜索栏且用户可改 | 免登录 |
| GET | `/api/items/nl-search` | **自然语言检索**：服务端完成「解析槽位 → 查询 → 重排」。query 同 `/api/items` 且额外支持 `nl=<自然语言>`；返回分页结果，每条带 `matchReason`（重排理由） | 免登录 |
| POST | `/api/ai/feedback` | **采纳反馈**：body: `{suggestionType: publish_draft\|search_query, accepted, suggestedValue, finalValue, targetId?, logId?}`。这是采纳率指标的采集入口 | 登录 |
| GET | `/api/admin/ai/metrics` | AI 效果面板：各场景调用量/成功率/降级率、P50/P95/P99 耗时、累计估算成本、发布建议采纳率 | 管理员 |
| POST | `/api/admin/ai/moderate` | 内容风险预判（发布/评价提交前调用）。body: `{targetType, content, targetId?}`；返回 `{riskLevel, riskCategories, reason, autoReject, source}` | 管理员 |
| GET | `/api/admin/ai/moderation` | 审核结论分页：query `targetType, riskLevel` | 管理员 |
| GET | `/api/admin/ai/report` | **运营 Copilot**：看板数据 → 自然语言周报。query `days=7` | 管理员 |
| POST | `/api/admin/ai/ask` | 只读问答：body: `{question}`。**不向模型暴露任何写操作**（审核/封禁/改价都不给），避免模型幻觉触发真实业务动作 | 管理员 |

## 十四、前端路由 ↔ 接口对照

| 路由 | 页面 | 主要接口 |
|---|---|---|
| `/` | 首页（瀑布流 + 分类 + 公告） | `/api/categories`、`/api/items`、`/api/notices` |
| `/search` | 搜索结果 | `/api/items` |
| `/item/:id` | 商品详情 | `/api/items/{id}`、`/api/favorites/check/{id}`、`/api/messages/conversations`、`/api/orders` |
| `/publish` | 发布闲置 | `/api/upload/images`、`/api/items` |
| `/messages` | 私信列表 | `/api/messages/conversations` |
| `/chat/:id` | 聊天窗口 | `/api/messages/conversations/{id}`、`/api/messages/conversations/{id}/messages`、`/api/messages/send` |
| `/orders` | 我的订单（买到/卖出） | `/api/orders`、`/api/orders/counts` |
| `/order/:id` | 订单详情 | `/api/orders/{id}`、订单动作接口、`/api/reviews` |
| `/favorites` | 我的想要 | `/api/favorites` |
| `/me` | 个人中心 | `/api/auth/me`、`/api/auth/overview`、`/api/addresses`、`/api/auth/profile` |
| `/user/:id` | 他人主页 | `/api/users/{id}`、`/api/users/{id}/items`、`/api/users/{id}/reviews` |
| `/my-items` | 我的发布 | `/api/items?sellerId=me`、`/api/items/{id}/off-shelf` |
| `/login`、`/register` | 登录 / 注册 | `/api/auth/login`、`/api/auth/register` |
| `/admin/*` | 管理后台（独立布局） | `/api/admin/**` |
