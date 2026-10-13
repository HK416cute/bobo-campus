# bobo 校园二手交易平台 —— 架构设计

> 分层、鉴权、订单状态机、一致性与治理流程的**设计理由**。接口契约见 [api.md](api.md)，部署见 [deploy.md](deploy.md)，上手见 [README.md](../README.md)。

## 一、总体架构

```
   浏览器（PC / 手机）
        │ :80
        ▼
 ┌──────────────────────────── bobo-net（bridge 网络）─────────────────────────────┐
 │ 容器 bobo-web（nginx 1.27）                容器 bobo-mysql（MySQL 8.0 :3306）     │
 │  · /usr/share/nginx/html ← SPA 静态产物              ▲                          │
 │  · /           try_files → index.html（SPA 回落）    │ JDBC                     │
 │  · /api/     ─proxy_pass─▶ 容器 bobo-server（Spring Boot :9080）                 │
 │  · /upload/  ─alias─▶ /data/upload（卷 bobo-upload，ro）                         │
 │                          │ 写文件 ／ 日志              │                         │
 │                          ▼                            │                         │
 │              /app/data/upload（rw）、/app/data/logs    │                         │
 │              卷 bobo-mysql-data ──────────────▶ /var/lib/mysql                   │
 └─────────────────────────────────────────────────────────────────────────────────┘
```

三个容器由 `docker-compose.yml` 编排，容器名 `bobo-mysql` / `bobo-server` / `bobo-web`；`bobo-server` 用
`depends_on: {bobo-mysql: {condition: service_healthy}}` 等 MySQL 的 healthcheck（`mysqladmin ping`）。

| 请求路径 | 处理者 | 走向 |
|---|---|---|
| `/`、`/item/12`、`/admin/items` | `bobo-web` 内 nginx | `location /` → `try_files $uri $uri/ /index.html`，history 路由可直达刷新。静态资源命中 `location ~* \.(js\|css\|…)$`，30 天 immutable 缓存；`index.html` 单独 `no-store` 防止发版串版本 |
| `/api/**` | `bobo-web` → `bobo-server:9080` | `proxy_pass http://bobo_server`（upstream 内 `keepalive 32`），转发 `Host`/`X-Real-IP`/`X-Forwarded-For`/`X-Forwarded-Proto`，`proxy_read_timeout 60s` |
| `/upload/**` | `bobo-web` 内 nginx | `alias /data/upload/` 直读共享卷，**不经过后端**，不占 Tomcat 线程、不走 JWT 拦截器 |

图片有两条通道：生产由 nginx `alias` 返回；本机 `mvn spring-boot:run` 时没有 nginx，由 `UploadResourceConfig`
把 `bobo.upload.url-prefix + "/**"` 映射到本地目录，两种方式下前端拿到的 URL 一致。

## 二、后端分层

```
com.bobo
├── common/      Result、PageResult、分页基类、BizException、GlobalExceptionHandler
├── config/      MybatisPlusConfig、WebMvcConfig（拦截器+跨域）、UploadResourceConfig、
│                JacksonConfig、OpenApiConfig、props/BoboProperties
├── controller/  前台接口；controller/admin/ 管理端（统一 /api/admin/**）
├── domain/      entity（12 表）/ dto（Dtos，record+校验）/ query（列表条件）/ vo（视图对象）
├── mapper/      MyBatis-Plus BaseMapper + 注解 SQL + XML 关联与聚合查询
├── security/    JwtUtil、JwtInterceptor、AuthContext、PasswordEncoder、@Anonymous
└── service/     事务边界与业务逻辑：订单状态机、审核流转、通知、信用分
```

| 层 | 职责 | 依赖方向 |
|---|---|---|
| controller | 路由、参数绑定校验、包 `Result` | → service、domain.dto/query/vo、security（只读上下文） |
| service | 事务边界、状态机、跨表校验、发通知 | → mapper、domain、security |
| mapper | 只做 SQL 与映射，不写业务判断 | → domain.entity / vo |
| entity / dto / query / vo | 表镜像+状态常量 / 入参形状 / 筛选条件 / 出参形状 | 无（叶子，不反向依赖 service） |

依赖严格单向 `controller → service → mapper → DB`。于是改表只动 entity+mapper，改接口只动 controller+dto/vo，业务规则集中在 service 便于定位。
**DTO 为什么用 record**：请求体只有字段与约束、没有行为，record 天然不可变、`equals/hashCode/toString` 自动生成，
Jackson 与 `jakarta.validation` 都支持在 record 组件上标注（如 `@NotBlank String title`），比 20 行 getter/setter 更短，
且「哪些字段可被客户端传」一眼可见；列表查询条件仍用可变类 `XxxQuery`，因为要承载 MyBatis-Plus 的默认值与 setter。
**为什么单模块**：只有 12 张表、十几个 service，拆成 `common/system/web` 后每次改动都要过模块依赖与编译顺序，收益（编译隔离、复用）
在「部署一次构建一次」的节奏下几乎为零；包已按层分好，真要拆按 `com.bobo.<模块>` 切一刀即可（见第十节）。

## 三、鉴权设计

**签发与校验流程**：`POST /api/auth/login` → `selectByUsername` → `PasswordEncoder.matches` → 记录 `login_ip`/`login_time`
→ `JwtUtil.createToken(userId, username, admin)`（`subject=userId`，claim `{username, admin}`，
`iat/exp = now … now + BOBO_JWT_EXPIRE_MINUTES` 默认 10080 分钟，HMAC-SHA256 签名，密钥为 `BOBO_JWT_SECRET` 的 UTF-8 字节）
→ 返回 `{token, expiresIn, user}`。后续请求带 `Authorization: Bearer <token>`，
`JwtInterceptor.preHandle` 依次做 `resolveToken` → `JwtUtil.parse`（过期/篡改/签名不符一律返回 `null`）→
`userMapper.selectById(userId)` → 用户存在且未封禁则 `AuthContext.set(LoginUser)` → Controller/Service 用
`AuthContext.userId()` 取当前用户 → `afterCompletion` 里 `AuthContext.clear()`。
`JwtUtil` 构造时校验密钥：**不足 32 字节直接抛 `IllegalStateException` 拒绝启动**，防止生产沿用「本机跑得起来」的默认短密钥。

**`@Anonymous`：免登录 ≠ 不解析 token**。该注解可打在方法或整个 Controller 类上（`isAnonymous()` 两处都判断），
它只做一件事——**无登录态时放行**；但 `preHandle` 里的 token 解析在判断 `anonymous` **之前**就已执行，
因此带该注解的接口依然会把合法 token 写入 `AuthContext`。这正是「登录可选」的实现：`GET /api/items` 匿名可看列表，
带 token 时 XML 查询里的 `currentUserId` 参与计算、每条额外返回 `favorited`；`GET /api/items/{id}`、`/api/reviews`、
`/api/users/{id}` 同理。对应两个取值入口：需登录用 `AuthContext.userId()`（未登录抛 401），
可选登录用 `AuthContext.userIdOrNull()`（返回 `null`），语义明确，无需在方法签名里一路透传 `userId`。

**`AuthContext` 的写入与清理时机**。内部是 `static final ThreadLocal<LoginUser>`：**写入**只在
`JwtInterceptor.preHandle`，且仅在「token 解析成功 且 用户存在 且 未被封禁」时；**读取**由 Controller/Service
通过静态方法完成；**清理**在 `afterCompletion` 里 `ThreadLocal.remove()`。必须清理是因为 Tomcat 线程来自线程池会被复用，
只 set 不 remove 会把上一个请求的身份泄漏给下一个请求，是最典型的越权来源；放在 `afterCompletion` 而非 `postHandle`，
是为了让异常响应（被 `GlobalExceptionHandler` 处理掉的）也走清理。`LoginUser` 是不可变 record
`(userId, username, admin)`。唯一要注意的是**不要在 service 里开新线程后直接用 `AuthContext`**（新线程拿不到 ThreadLocal），见第十节。

**为什么没有引入 Spring Security**。项目只有两种门槛：登录、管理员。引入的代价是过滤器链、`SecurityContext`
与自定义 `ThreadLocal` 两套上下文、`@PreAuthorize` 的 SpEL 成本，以及为绕开默认表单登录/CSRF 写的一堆
`http.xxx().disable()`。现状是 1 个约 100 行的拦截器 + 1 个注解 + 1 个 ThreadLocal，行为全部可读可调试；
代价是少了方法级细粒度权限、OAuth2、CSRF，但本项目是前后端分离 + Bearer token，不依赖 Cookie 会话与 CSRF token。

**`/api/admin/**` 的统一权限闸门**：拦截器注册在 `/api/**` 上，放行前做一次前缀判断——
`if (uri.startsWith("/api/admin/") && !AuthContext.isAdmin())` 就写回 `403 需要管理员权限`
（未登录则 `401 登录状态已失效，请重新登录`）。效果是**没有任何 admin controller 需要单独写角色校验**，
漏标注解也不会漏权限；service 层再用 `AuthContext.requireAdmin()` 兜一层关键操作，避免将来新增的非 `/api/admin`
入口绕过闸门。`LoginUser.admin` 来自**回查到的用户记录**（`user.isAdminUser()`）而非 token 的 claim，所以后台改角色立即生效。

**封禁用户为什么实时生效**：关键在「每个请求回查一次用户」——token 里只有身份标识（谁），**没有**授权结论。
管理员点封禁后，下一个请求的 `userMapper.selectById` 回查失败（`status = 1`，或 `del_flag = 2` 被 MyBatis-Plus
逻辑删除条件排除），非匿名接口直接 401。代价是每请求一次主键单表查询（开销可忽略），
换掉了 token 黑名单/Redis 与「封禁后要等 token 过期」的体验问题。

## 四、密码存储

实现见 `security/PasswordEncoder.java`（自写，不依赖 Spring Security）：**PBKDF2WithHmacSHA256**，120000 次迭代，
16 字节随机盐（`SecureRandom`），派生 256 位密钥；格式 `pbkdf2$sha256$<迭代次数>$<saltBase64>$<hashBase64>`，
存于 `bobo_user.password VARCHAR(100)`。

**迭代次数为什么写在散列串里**：`matches()` 按串里的迭代数重新派生（`Integer.parseInt(parts[0])`）而不是用代码常量。
以后把 `ITERATIONS` 提到 60 万时，老密码仍按其自身迭代数校验，新注册/改密的用户用新强度，
**无需强制全员重置密码**，也不用做一次性全表迁移。

**时间恒定比较**：`constantTimeEquals` 逐字节 `diff |= a[i] ^ b[i]`，而不是 `Arrays.equals`
（后者在首个不同字节即返回，可被按响应时间逐字节猜测散列）；长度不同直接返回 `false`（长度不是秘密）。
另外，非 `pbkdf2$sha256$` 前缀、段数不对、Base64 非法一律返回 `false` 而不抛异常，避免把「该账号的散列格式」暴露给调用方。

## 五、订单状态机

```
                     ┌──────────────┐  取消(cancel,待付款时买卖双方均可)
                     │ 0 待付款      │───────────────────────▶ ┌────────────┐
                     └───┬──────┬───┘                         │ 5 已取消    │
         支付(pay,买家)  │      └─ 当面交易(tradeType=1) 直接跳到 3 待评价
   邮寄(tradeType=2)     ▼
                  ┌──────────────┐ 发货(deliver,卖家)  ┌──────────────┐
                  │ 1 待发货      │──────────────────▶│ 2 待收货      │
                  └──────┬───────┘                    └──────┬───────┘
        取消(仅卖家)      │           确认收货(confirm,买家)  │
                         │  申请退款(refund,买家)             ▼
                         │        ┌───────────────────────────────────────┐
                         └───────▶│ 3 待评价 ──评价(review,双方各一次)──▶ │ 4 已完成 │
                                  └──────┬────────────────────────────────┘
                         申请退款(refund) │
                                         ▼
                        ┌──────────────┐  同意(agreeRefund,卖家)  ┌──────────────┐
                        │ 6 退款中      │───────────────────────▶│ 7 已退款      │
                        └──────────────┘                        └──────────────┘
```

**两条链路的差异**：当面交易（`trade_type=1`）走 `0 → 3 → 4`——同校当面交付没有物流，`pay()` 判断
`tradeType == TRADE_FACE` 时直接写 `STATUS_WAIT_REVIEW` 跳过 1、2。邮寄（`trade_type=2`）走 `0 → 1 → 2 → 3 → 4`，
`deliver()` 强制 `tradeType == TRADE_MAIL` 且 `logisticsNo` 必填，写 `logistics_company`/`logistics_no`/`deliver_time`。
商品的 `trade_type`（0 都支持 / 1 仅当面 / 2 仅邮寄）**优先于**买家选择：`resolveTradeType()` 先校验买家传值合法，
再看商品限制，冲突时抛「该商品仅支持当面交易/仅支持邮寄」；确认后的方式写入 `bobo_order.trade_type`，之后流转只看订单字段。

| 动作 | 前置状态 | 操作人 | 关键校验 |
|---|---|---|---|
| 下单 | 商品 `1 在售` | 登录买家 | 商品存在、非本人、交易方式兼容、邮寄必须选本人地址 |
| 支付 | `0 待付款` | 买家 | `requireBuyer`；按 `trade_type` 落到 1 或 3 |
| 发货 | `1 待发货` | 卖家 | `requireSeller`；必须邮寄；快递单号必填 |
| 确认收货 | `2 待收货`（也允许 `1`，卖家已线下送达） | 买家 | `requireBuyer`；写 `finish_time` |
| 取消 | `0` 或 `1` | 待付款：买卖双方；已付款后**仅卖家** | 参与者或管理员；已付款买家只能走退款 |
| 申请退款 | `1`/`2`/`3` | 买家 | 原因必填 |
| 同意退款 | `6 退款中` | 卖家 | `requireSeller` |
| 评价 | `3 待评价`（也允许 `4` 用于补评） | 双方各一次 | 必须是参与者；`uk_review_order_from` 唯一索引兜底 |

所有流转先 `requireOrder()` 取最新记录再校验状态；`update` 对象只设置要改的字段（MyBatis-Plus 按非 null 更新），
避免读-改-写覆盖并发修改。

**取消 vs 退款与商品释放**：**取消**（`5 已取消`）写 `cancel_reason` 后**立即** `releaseItem(order)`；
未支付时双方都能撤，已付款后卖家取消等价于拒单退款、买家想撤只能走退款流程。**退款**（`6 退款中 → 7 已退款`）
申请时不动商品（交易可能仍在进行），只有卖家同意后才 `releaseItem(order)`。`releaseItem` 统计「这件商品还有没有
**其它**有效订单」——对 `bobo_order` 按 `item_id` 计数、排除当前 `order_id`、状态限定在
`待付款/待发货/待收货/待评价/退款中`（`STATUS_UNPAID`、`STATUS_WAIT_DELIVER`、`STATUS_WAIT_RECEIVE`、
`STATUS_WAIT_REVIEW`、`STATUS_REFUNDING`），计数为 0 才 `itemService.restoreOnSale(itemId)`。
被排除在「占用」之外的是 `4 已完成`（已真成交，商品应保持已售）、`5 已取消`、`7 已退款`（终态，不再占用）。
`restoreOnSale` 内部再判断 `status == SOLD` 才恢复并清空 `sold_time`，防止把管理员手动下架的商品误恢复成在售。

## 六、数据一致性策略

**事务边界**：跨表写路径都带 `@Transactional(rollbackFor = Exception.class)`——`OrderService.create`
（`bobo_order` 插入 + `bobo_item` 置已售 + `bobo_notice`）、`pay/deliver/confirm/cancel/refund/agreeRefund`
（订单 + 商品，取消/退款还含 `releaseItem` 的 count 查询）、`ItemService` 的
`publish/update/delete/audit/offShelf*/onShelf*/batch*/markSold/restoreOnSale`（商品 + 图片 + 收藏）、
`FavoriteService.toggle`（收藏 + `want_count`）、`ReviewService.submit/delete`（评价 + 订单 + 信用分）、
`ReportService.submit/handle`（举报 + 联动对象）。`rollbackFor = Exception.class` 是必要的：Spring 默认只对
`RuntimeException` 回滚，一旦有人把校验改成受检异常，默认行为会静默提交半截数据。刻意不加事务的只有
`ItemService.detail` 里的浏览量自增——它本身就是 `view_count = view_count + 1` 的原子 UPDATE。

**`want_count` 冗余计数按实际行数校准**：唯一事实来源是 `bobo_favorite` 的行，`bobo_item.want_count`
只是给列表排序（`sortBy=want`）用的冗余。每次切换收藏都 `favoriteMapper.countByItem(itemId)` 重新数真实行数，
再用 `wantCount - item.wantCount` 作为 delta 交给 `itemMapper.changeWantCount`
（SQL：`UPDATE bobo_item SET want_count = GREATEST(want_count + #{delta}, 0) WHERE item_id = #{itemId}`），
而不是 `want_count + 1 / -1`：自增自减在并发切换、手工删过 `bobo_favorite` 行之后会永久跑偏，而排序字段一旦跑偏就纠不回来；
按行数校准让每次操作都自动收敛，`GREATEST(...,0)` 再兜住负数。删商品时 `favoriteMapper.deleteByItem(itemId)`
清掉收藏行，因此不存在「商品没了计数还在」的残留。

**一件商品只有一个有效订单**靠三步：① **入口只认在售**——`create()` 中
`if (item.getStatus() != Item.STATUS_ON_SALE) throw`；② **下单即置已售**——`orderMapper.insert(order)` 之后立刻
`itemService.markSold(itemId)`（写 `status=2, sold_time=NOW()`），两步在**同一事务**内，否则会产生「同一商品两张待付款单」；
③ **商品状态是唯一闸门**——第二个买家下单时在第①步就被拒；这里**不依赖**「自动取消别人的待付款单」，而是让商品状态先失效，
从源头杜绝第二单。并发兜底是 MySQL 行锁：`markSold` 的 UPDATE 持有商品行排他锁到事务提交，两个并发请求会在更新同一行时串行化。
若要更严格（例如允许同商品多单竞价），把「校验 + 更新」合并为 `UPDATE … WHERE status = 1` 并检查影响行数。
**订单里的商品信息快照为什么必要**：`bobo_order` 冗余存 `item_title`、`item_cover`、`price`、`total_amount`，
收货信息另存 `receiver_info`（「收货人 / 电话 / 校区 详细地址」拼接串）。原因：① **卖家可随时改商品**，
`ItemService.update` 会改标题/价格/图片，历史订单必须显示**下单当时**的成交信息，否则买家回头看的价格与实际付款不一致，
纠纷时无法自证；② **商品可被逻辑删除**，`del_flag = 2` 后详情查不到，但订单仍要能渲染标题封面，列表 SQL 不 `JOIN bobo_item`
也能展示；③ **收货地址可改可删**，快照保证发货时看到的是下单时的地址。金额一律 `DECIMAL(10,2)`，前端拿到的是字符串十进制。

## 七、内容治理流程

```
发布(publish) ─▶ 0 待审核 ─管理员通过(audit)─▶ 1 在售 ─下单─▶ 2 已售
                    │                            │
                    └─管理员驳回─▶ 4 已驳回        └─本人下架 / 管理员强制下架─▶ 3 已下架
                                                  └─本人重新上架─▶ 0 待审核
```

**商品审核流转**：`publish()` 强制 `status = STATUS_PENDING`，并限制**同一用户同时最多挂
`MAX_PENDING_ITEMS = 5` 件待审核商品**（`countPendingItems`，防刷屏灌商品）。
这里有两个刻意的语义选择：
①限制的是「**待审核中的数量**」，不是「历史总发布量」——只要审核出结果（通过/驳回）
或卖家下架，名额立即复用，所以正常卖家整理一批闲置连发几件不会被拦；
②额度之外仍允许编辑已发布的商品（编辑只是重新进入待审核，不新增商品）。上限值
`ItemService.MAX_PENDING_ITEMS` 是 `public` 的，由 `AuthService.overview()` 回传给前端做
「还能发几件」提示，保证这个数字只有一个事实来源。
`audit()` 写 `status`、`reject_reason`、`audit_time`、`audit_by`
并发 `type=audit` 通知，**驳回必须填原因**（`reason` 为空直接抛异常）；管理员 `offShelfByAdmin` 把原因写入
`reject_reason` 并通知卖家，`onShelfByAdmin` 直接放回在售（相信管理员判断，不再走审核）；
`batchAudit`/`batchOffShelf` 循环处理、逐条通知、返回处理条数。

**编辑后为什么必须重新审核**：`ItemService.update()` 里，当 `status` 是在售或已驳回时重置为 `STATUS_PENDING`
并清空 `reject_reason`。审核是对**内容**（标题、描述、图片、价格）的判断，内容变了原结论就不成立；否则卖家可先上架
合规内容、通过后再改成违规内容，审核形同虚设；清空驳回原因是避免重新提交的商品还挂着上次的理由。
已售出商品（`STATUS_SOLD`）不允许编辑，因为订单快照已生成，改了会导致双方看到的信息不一致。

**举报处理的联动**：`handle(reportId, {handled, remark, offShelfItem})` 在**认定成立**时按对象类型联动——
举报 `item` 且 `offShelfItem = true` 时调用 `offShelfByAdmin(targetId, "被举报下架：" + reason)`，商品置 `3 已下架` 并通知卖家；
举报 `review` 时 `reviewMapper.updateStatus(targetId, Review.STATUS_HIDDEN)`，评价立即从公开列表消失；
举报 `user` 时 `userMapper.changeCreditScore(targetId, -5)` 扣 5 分，**是否封禁由运营单独决定，处理举报不自动封号**。
无论成立与否都落库结果（`1 已处理`/`2 已驳回`）、`handle_remark`、`handle_by`、`handle_time`，并给举报人发
`type=system` 通知。提交侧三道校验：类型必须是 `item|user|review`、不能举报自己（自己的商品/自己）、
同一用户对同一目标的**待处理**举报只能有一条。

**信用分规则**：初始 100（`bobo_user.credit_score INT NOT NULL DEFAULT 100`），所有加减走同一条 SQL，
**上下限由数据库统一夹逼**：`UPDATE bobo_user SET credit_score = LEAST(GREATEST(credit_score + #{delta}, 0), 150) WHERE user_id = #{userId}`。
收到 4-5 星评价 `+1`、1-2 星 `-1`、3 星 `0`（`ReviewService.submit` 的 `delta = score >= 4 ? 1 : (score <= 2 ? -1 : 0)`）；
举报成立且对象是用户 `-5`（`ReportService.handle`）；管理员可通过 `PUT /api/admin/users/{userId}/credit` 手工调整
`creditDelta`；违规评价被删除时 `ReviewService.delete` 只对 `score >= 4` 的那条回退 1 分（当初加的那 1 分）。
上限 150 让高分用户不必为刷分互评，下限 0 保证分数不会变负导致展示与排序异常；`score <= 2` 只扣 1 分而非按星级线性扣，
避免一次差评把 100 分打得很低——信用分在本场景主要是提示作用。

## 八、上传与图片

**落盘结构与命名**：根目录 `BOBO_UPLOAD_PATH`（容器内 `/app/data/upload`）下按 `LocalDate.now().format("yyyy/MM")`
分年月目录，文件名为 `UUID.randomUUID().toString().replace("-", "")` + 扩展名。按年月分目录避免单目录堆几十万文件拖慢备份与遍历，也便于按月清理；文件名用 UUID 规避中文名、重名覆盖与路径穿越——客户端传 `..\..\evil.jpg` 也先被
`Paths.get(name).getFileName()` 剥掉目录部分，再被 `target.startsWith(root)` 二次确认。扩展名白名单
`bobo.upload.allow-ext = jpg,jpeg,png,webp,gif`（小写比较），非白名单直接拒绝，因此上传目录里不会出现 `.jsp`/`.sh`
这类可执行文件。大小双保险：Spring 的 `max-file-size: 10MB` / `max-request-size: 40MB` 加
`bobo.upload.max-size-mb: 10` 显式判断；批量上传最多 9 张（`files.size() > 9` 抛异常）。

**返回 URL 与 nginx 的关系**：`UploadService` 取 `bobo.upload.url-prefix`（容器里 `/upload`），缺前导斜杠时补上，
拼成 `prefix + "/" + yyyy/MM + "/" + 文件名`，即前端可直接访问的绝对路径。落地由 nginx 完成：
`location /upload/ { alias /data/upload/; }` 加上 compose 的 `bobo-upload:/data/upload:ro`，使
`/upload/2026/01/ab12….jpg` → `/data/upload/2026/01/ab12….jpg`，与后端写入的 `/app/data/upload/2026/01/ab12….jpg`
是同一份卷。**URL 前缀、nginx location、容器内挂载点三者必须一致**，改任一个都要同步改另外两个。nginx 在上传目录内
还嵌套了 `location ~* \.(php|jsp|sh|py|pl|html?)$ { deny all; }`，即使白名单被绕过也执行不了脚本。

**为什么不用对象存储，要换 OSS 改哪里**：校园场景图片量小（演示数据直接用前端内置 `/img/*.svg` 占位图，不依赖外部图床），一个 named volume + nginx `alias` 足够，省掉 AccessKey 保管、跨域、回源鉴权、按量计费；取舍是图片与服务器绑定，
删卷即丢，多机部署需自行同步卷。换成 OSS/MinIO 只需改 `UploadService.doUpload()` 的落盘与拼 URL 部分
（`Files.createDirectories`/`file.transferTo` → SDK `putObject`，返回值换成 CDN/OSS 域名），配套：
① `BoboProperties.Upload` 增加 `endpoint/bucket/accessKey/secretKey/publicBaseUrl` 与 `application.yml` 对应项；
② `docker-compose.yml` 给 `bobo-server` 补环境变量，并**可移除** `bobo-upload` 卷与 nginx 的 `/upload/` location；
③ 历史数据迁移——`bobo_item.cover`、`bobo_item_image.url`、`bobo_order.item_cover` 存的是 URL 字符串，
确认老 URL 可访问（或写脚本改写前缀）后再下线本地卷。库里存 URL 而非相对路径，正是为了让这种切换不必改表结构。

## 九、前端设计

**路由守卫**：`router/index.js` 用 `meta.requiresAuth` / `meta.requiresAdmin` / `meta.guestOnly` 三个标记，
在全局 `beforeEach` 集中处理——需要登录或管理员时先 `await userStore.fetchUser()`（有 token 才请求 `/api/auth/me`，
已加载过不重复请求），拿不到用户就按需重定向到 `/admin/login` 或 `/login` 并带 `redirect=to.fullPath`，
拿到但不是管理员则回首页。用 `to.matched.some(r => r.meta?.requiresAuth)` 而非只看 `to.meta`，父级 `/admin`
标一次即覆盖全部子路由（子路由仍显式标注作为二次保险）。守卫是**唯一**的登录判断入口，页面组件不再各自判断；
它只负责体验（避免白屏、少发无效请求），**真正校验在 `JwtInterceptor`**，改 `meta` 绕不过后端。`guestOnly`
用于登录/注册页：已登录再访问会被送回首页，避免「已登录还停在登录页」。

**`http.js` 统一响应处理与 401 只弹一次**：后端**始终返回 HTTP 200**，业务码在 body（`{code, msg, data}`），
于是只在响应拦截器一处判断——`code === 200` 返回 body，`code === 401` 走 `handleUnauthorized` 并 reject，
其它则 `ElMessage.error(msg)` 并 reject。代价是丢掉 HTTP 状态码语义（无法用 404 直接判断资源不存在），
收益是**任何页面都不用写 status 判断**。401 用 `redirecting` 布尔标记去重：页面加载常并发多个请求（概览、角标、列表），
若每个 401 都弹框会叠出多个「去登录」；第一个 401 先 `setToken('')`，再置 `redirecting = true` 并弹
`ElMessageBox.alert`，后续 401 直接 return，用户确认或关闭时复位标记，然后
`window.location.href = '/login?redirect=' + 编码后的当前路径`（用 `location.href` 而非 `router.push`，
顺便清掉内存里的过期状态）。网络层错误走 reject 分支，`404` 被翻译成「接口不存在，请检查后端是否已启动」——
专治「前端起来了后端没起来」。请求侧只做一件事：有 token 就加 `Authorization: Bearer <token>`；
导出的 `request` 快捷方法把响应剥到 `data` 一层，业务代码不必到处 `.data.data`。

**Pinia 存了什么**：`stores/user.js` 只存跨页面共享的少量状态——`token`（初始化从 `localStorage` 的 `bobo_token`
读取，登录/退出同步写回，刷新不掉线）、`user`（**不落 localStorage**，每次进站由 `fetchUser()` 拉 `/api/auth/me`，
保证角色、封禁、信用分是新的）、`unreadMessage`/`unreadNotice`（顶栏与底部导航红点角标）、`loaded`
（是否已拉过用户信息，避免每次路由跳转都打一次 `/api/auth/me`）；getter 提供 `isLogin`/`isAdmin`/`nickname`/`userId`。
`fetchUser()` 失败**不抛错**而是清 token 返回 `null`，与路由守卫配合完成静默掉线；`refreshBadges()` 失败只吞异常
（角标拿不到不该打扰用户）；`patchUser(partial)` 改完昵称/头像就地更新，省一次请求。列表数据、分页游标、表单草稿
留在组件 `ref`，不进 store——避免把 Pinia 当全局缓存。

**组件复用**：`ItemCard.vue` 被首页瀑布流、搜索结果、我的收藏、卖家主页、我的发布共用，卡片上「想要数、成交价、
校区、状态角标、已售置灰」的展示规则只维护一份；`ImageUploader.vue` 被发布页与编辑页（同一个 `PublishView.vue`）
共用，内部处理多图、最多 9 张、第一张为封面、上传中占位与失败重试，页面只管拿 `url[]`；`EmptyState.vue` /
`SkeletonGrid.vue` 复用空列表与加载骨架；布局层 `AppLayout.vue`（顶栏+搜索+角标+移动端 tabbar）与
`AdminLayout.vue`（侧边菜单）分离，前台用 `<keep-alive :include="['HomeView','SearchView']">` 只缓存首页与搜索页
（最常来回切），详情/订单/聊天必须每次重新取数。

**为什么聊天用轮询而不是 WebSocket**：`ChatView.vue` 用 `timer = setInterval(pollNew, 8000)`，组件卸载时
`clearInterval(timer)`。校园场景并发低、停留时间短，8 秒延迟可接受，轮询只有几行且天然复用同一套 HTTP 客户端
（token、401 处理、超时都是现成的）；WebSocket 要额外做握手鉴权、心跳重连、消息去重与顺序、多实例广播
（粘性会话或 Redis 发布订阅）、nginx 的 `Upgrade` 头与代理超时——当前规模下这些复杂度换来的体验提升有限。
代价明确：最坏延迟 8 秒，且每次轮询打一次 DB；页面不可见时应暂停轮询，这是将来优化的第一个点。

**状态字典为什么集中在 `utils/format.js`**：`ITEM_STATUS`、`ORDER_STATUS`、`TRADE_TYPE`、`VERIFY_STATUS`、
`CONDITION_OPTIONS` 以及 `money`/`priceParts`/`formatTime`/`fromNow`/`coverOf`/`avatarOf`/`maskPhone`/`countdown`
都在这一个文件，文件头明确「只放纯函数 + 常量字典，不放任何请求逻辑」。原因：同一个数字 `2` 在商品里是「已售」、
在订单里是「待收货」，语义由字典决定；若每页各写一份 `statusText`，改文案或加状态必然漏改某处，
出现「首页显示已售、详情显示未知」。集中后与后端 `Item`/`Order` 常量一一对应，配合 `scripts/check-contract.mjs`
这类静态校验，能让契约漂移在提交前被发现；纯函数便于单测，也不引入组件依赖。

## 十、AI 能力（Spring AI + DeepSeek）

AI 在本项目里的定位是**可选增强，不是启动依赖**：`bobo.ai.enabled=false` 或未配置
`BOBO_AI_API_KEY` 时，`com.bobo.ai` 全层自动降级为规则实现，应用照常启动、现有接口行为不变。

三处刻意的架构约束（详细理由与面试要点见 [ai.md](ai.md)）：

1. **业务场景不依赖 Spring AI 类型**，只依赖自定义接口 `com.bobo.ai.spi.ChatInvoker`；
   Spring AI 的全部类型只出现在 `AiChatConfig` 一个文件里，换厂商的改动面就是一个类，
   同时让单元测试能用 `MockChatInvoker` 替代真模型（离线、确定、不烧 token）。
2. **所有 AI 调用必须经过 `AiExecutor`**：它统一处理「门控 → 限流 → 配额 → 调用 → 解析校验 → 落流水」，
   任一环失败都走同一条降级路径，因此"模型挂了"在业务代码里**不存在特殊分支**，
   `execute()` 的返回值一定有值（模型结果或规则结果），并用 `source` 字段标明来源。
3. **异步写流水必须传 `userId` 而不是在新线程读 `AuthContext`**：`AuthContext` 是请求级 ThreadLocal，
   `bobo-ai-*` 线程池里读到的是 `null`，会让流水表 `user_id` 全空，
   进而使"按 user_id 计数的每日配额"永久失效。同理，`@Async` 不能同类自调用（代理失效 → 假异步），
   所以异步写库单独放在 `AiCallLogWriter`。

**为什么不做 embedding / 向量库 / RAG**：DeepSeek 不提供 embedding 接口，
硬上需再引一家厂商 + Redis Stack，而本场景候选集只有几百条，SQL 过滤已足够。
因此自然语言检索的实现是「**LLM 解析检索槽位 → 喂现有 MyBatis-Plus 查询 → LLM 重排 top-N**」，
文档与简历都按这个措辞写，不冒充向量检索。

## 十一、扩展点

| 方向 | 现状 | 建议改法 |
|---|---|---|
| **真实支付** | `OrderService.pay()` 直接把订单推到下一状态 | 拆两步：`pay` 创建预支付单并返回支付参数，状态保持 `0 待付款`；新增免登录 `POST /api/pay/notify`（验签 + 幂等）在回调里执行现在的状态推进。回调必须用「订单号 + 状态」条件更新保证幂等，并与 `bobo_order.total_amount` 二次核对金额 |
| **对象存储** | 后端写卷、nginx `alias` 直读 | 见第八节：替换 `UploadService.doUpload` 的落盘与 URL 拼装，`BoboProperties.Upload` 加 OSS 配置，移除 `bobo-upload` 卷与 `/upload/` location |
| **加 Redis** | 状态全在 MySQL，封禁靠每请求回查 | ①**会话黑名单**——`logout` 时把 jti 或 token 摘要写入 Redis 并设 TTL = 剩余有效期，`JwtInterceptor` 命中即视为未登录（换来「退出立刻生效」）；②**热词榜**——搜索词 `ZINCRBY`，供 `sortBy=hot`；③**验证码/限流**——注册、登录、发图文用 `INCR`+`EXPIRE`；④**缓存分类与公告**。引入方式：compose 加 `bobo-redis`，`application.yml` 加 `spring.data.redis.*` |
| **加 WebSocket** | 8 秒轮询 | STOMP+SockJS 或原生 `@ServerEndpoint`；鉴权放在握手阶段（`HandshakeInterceptor`，普通 `JwtInterceptor` 不拦升级请求）；消息落库仍走 `ConversationService`，推送只提示「有新消息」；多实例需 Redis 发布订阅；nginx 要给该 location 配 `Upgrade`/`Connection "upgrade"` 与更长 `proxy_read_timeout` |
| **拆分服务** | 单模块单进程 | 切口天然：①**用户/鉴权**（`security` + `AuthService`）最先值得独立（所有请求入口，需独立限流）；②**商品与治理**（`ItemService`/`CategoryService`/`ReportService` + 图片）读写量最大，适合独立扩容与独立存储；③**交易**（`OrderService`/`ReviewService`/`AddressService`）强事务、状态机最复杂，建议保持单库单事务、最后再拆。跨服务先用 HTTP + 现有 `Result` 契约，痛了再上 MQ 做最终一致；拆之前先补日志链路 ID |

## 附：与 `api.md` 表述略有差异的实现细节

以下三处**代码是有意这么做的**，`docs/api.md` 的文字描述偏宽松，以本节为准（已在 api.md 同步）：

1. **下单不会「自动取消其他人的待付款订单」**：`OrderService.create` 没有这段逻辑，而是用
   「下单即把商品从 `1 在售` 原子推进到 `2 已售`」来保证一件商品只有一个有效订单
   （`ItemMapper.compareAndSetStatus`，影响行数为 0 就回滚）。因此第二个人在下单入口
   就会被「商品已下架或已售出」拦住，不会产生第二张待付款单。
   代价是先下单不付款会占用商品，需要卖家取消（或后续加超时自动取消任务）才能重新上架。
2. **订单「已完成」的触发条件是 `peerReviewed || isBuyer`**：买家评价后订单直接进入
   `4 已完成`，不强制等卖家也评完（卖家之后仍可在订单详情补评，`uk_review_order_from` 唯一索引兜底）。
   这样设计是为了避免一个不配合的卖家把买家的订单永远卡在「待评价」。
3. **申请退款允许的状态是 `1 待发货 / 2 待收货 / 3 待评价`**：`0 待付款` 应走取消而不是退款，
   `4 已完成`、`5 已取消`、`7 已退款` 都是终态，不再接受退款申请。

### 已完成的两项修复（如看到旧笔记提到，可忽略）

- **演示账号密码**：`mysql/init/02-seed.sql` 里的散列已改为后端使用的 PBKDF2 格式
  （`pbkdf2$sha256$120000$<salt>$<hash>`），初始化后 `admin / password` 等账号可直接登录。
  需要生成新散列时用内置工具（详见 [deploy.md](deploy.md) 的运维一节）：
  `java -jar bobo-server.jar --bobo.password-tool=<新密码> --bobo.password-user=<账号>`。
- **`bobo_user.password` 列注释**：已从「BCrypt 密码散列」更正为 PBKDF2 说明。

### 仍然存在的取舍

- `docker-compose.yml` 的 `BOBO_UPLOAD_URL_PREFIX=/upload` 与 `application.yml` 默认值等价；
  真正需要保持一致的是三处：后端的 URL 前缀、nginx 的 `location /upload/`、容器内的挂载点
  （`/app/data/upload`）。`UploadService` 会自动补前导斜杠，所以不必依赖两处的写法完全一致。
- `bobo_item.want_count` 是展示用的冗余统计值，允许大于 `bobo_favorite` 的实际行数
  （种子数据就是这样），因此收藏切换用原子增量而不是按行数重算，避免展示值被改小。
