# bobo 校园二手交易平台

> 闲鱼风格的**校园闲置交易平台**：同校发布、当面验货、站内私信、下单留痕。
> 自包含 MySQL 8，一条 `docker compose up -d --build` 即可部署到云服务器。

![Vue](https://img.shields.io/badge/Vue-3.4-4FC08D)
![Vite](https://img.shields.io/badge/Vite-5-646CFF)
![Element Plus](https://img.shields.io/badge/Element%20Plus-2.7-409EFF)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F)
![Java](https://img.shields.io/badge/Java-17-007396)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED)

---

## 一、这个项目是什么

校园里最真实的二手需求是「同校、当面、快速成交」。bobo 围绕这三点设计：

- **同校优先**：每件闲置都带校区与交易地点，首页按校区/分类筛，买卖双方约在食堂门口就能完成交易。
- **两条交易链路**：当面交易（下单 → 支付 → 待评价 → 完成，跳过物流）与邮寄交易（下单 → 支付 → 发货 → 收货 → 待评价 → 完成），由卖家在发布时选择支持哪种。
- **站内私信留痕**：买家可以从商品详情直接「聊一聊」，会话围绕商品建立，纠纷时有据可查。
- **管理端治理**：商品审核、强制下架、用户封禁、学生认证审核、评价隐藏、举报处理、数据看板。

## 二、功能清单

### 买家 / 卖家（前台）

| 模块 | 能力 |
|---|---|
| 账号 | 注册、登录（JWT）、退出、资料编辑、修改密码、学生认证申请 |
| 浏览 | 首页瀑布流、16 个分类导航、关键词搜索、成色/价格区间/交易方式筛选、5 种排序 |
| 商品 | 发布（最多 9 图，第一张为封面）、编辑（重新审核）、下架、重新上架、删除、浏览量统计 |
| 收藏 | 「想要」一键切换、我的想要列表、想要数校准 |
| 私信 | 从商品发起会话、会话列表（买/卖视角）、聊天（8 秒轮询）、未读角标、站内通知 |
| 订单 | 下单校验（在售/非本人/交易方式）、下单即原子锁定商品（并发下单只有一人成功）、模拟支付、发货（填物流）、确认收货、取消、申请退款、同意退款 |
| 评价 | 订单完成后双向评价（1-5 星），评分影响对方信用分 |
| 个人 | 个人中心概览、我的发布、我的订单（买/卖）、收货地址管理、他人主页、站内通知 |
| 举报 | 举报商品 / 用户 / 评价，查看自己的举报进度 |

### 管理端（`/admin`）

| 模块 | 能力 |
|---|---|
| 数据看板 | 用户/商品/订单/GMV/评分/收藏 概览，近 7-30 天趋势折线（订单量 + 成交额双轴）、商品状态饼图、分类分布柱图、待处理事项 |
| 商品管理 | 按状态/分类/关键词筛选、审核通过/驳回（填写原因）、强制下架、恢复上架、删除、批量通过、批量下架 |
| 分类管理 | 新增/编辑/删除（分类下有商品时拒绝删除）、启停用、在售数量 |
| 订单管理 | 全站订单查询、状态筛选、详情抽屉（含时间轴） |
| 用户管理 | 封禁/解封、学生认证审核、调整信用分、行为统计 |
| 评价管理 | 隐藏/显示/删除违规评价 |
| 举报处理 | 待处理优先排序、认定违规（可同时下架商品、隐藏评价、扣信用分）、驳回 |
| 公告管理 | 发布全站公告或给指定用户发通知、删除 |

## 三、技术选型

| 层次 | 选择 | 说明 |
|---|---|---|
| 前端 | Vue 3.4 + Vite 5 + Element Plus 2.7 + Pinia + Vue Router 4 + ECharts 5 | 全部 `<script setup>`；图表只在管理端加载 |
| 后端 | Spring Boot 3.4.5 + Java 17 + MyBatis-Plus 3.5.12 + springdoc-openapi 2.8 | `jakarta.*` 命名空间；Boot 版本与 Spring AI 对齐（见下） |
| AI | Spring AI 1.0.0 + DeepSeek（`deepseek-chat`） | 只做**结构化抽取 / 检索意图解析 + 重排 / 内容风险评分**；不引 embedding 与向量库。AI 是可选增强：未配置 `BOBO_AI_API_KEY` 时全层降级为规则实现，应用照常启动。详见 [docs/ai.md](docs/ai.md) |
| 鉴权 | jjwt 0.12 + 自定义拦截器（`@Anonymous` 注解标记免登录） | **没有引入 Spring Security**：本项目只需要「认证 + 两种角色」，一个拦截器 + ThreadLocal 上下文比拉起整条过滤器链更轻、更好读 |
| 密码 | PBKDF2-HMAC-SHA256（12 万次迭代，格式含迭代数，便于以后提高强度） | 无第三方依赖 |
| 数据库 | MySQL 8.0（utf8mb4 / utf8mb4_unicode_ci，金额一律 DECIMAL） | 初始化脚本在 `mysql/init` |
| 图片 | 后端落盘到挂载卷，nginx 直接读同一卷 | 不引对象存储，单人/小组部署够用；要上 OSS 只需替换 `UploadService` |
| 部署 | Docker Compose（mysql + server + web/nginx） | 三个容器一条命令起 |

## 四、目录结构

```
bobo/
├── bobo-server/                    # 后端（Spring Boot 单模块）
│   ├── src/main/java/com/bobo/
│   │   ├── BoboApplication.java
│   │   ├── common/                 # Result / PageResult / 分页基类 / 全局异常
│   │   ├── config/                 # MyBatis-Plus、Web MVC、上传静态映射、Jackson、OpenAPI
│   │   ├── controller/             # 前台接口
│   │   │   └── admin/              # 管理端接口
│   │   ├── domain/
│   │   │   ├── entity/             # 12 张表对应实体
│   │   │   ├── dto/                # 请求体（record + 校验注解）
│   │   │   ├── query/              # 列表查询条件
│   │   │   └── vo/                 # 响应视图对象
│   │   ├── mapper/                 # MyBatis-Plus Mapper
│   │   ├── security/               # JWT、密码散列、登录上下文、拦截器
│   │   └── service/                # 业务逻辑（订单状态机、审核流转都在这里）
│   └── src/main/resources/
│       ├── application.yml
│       └── mapper/*.xml            # 关联查询与聚合统计 SQL
├── bobo-web/                       # 前端（Vue 3 + Vite）
│   ├── public/img/                 # 内置占位图（不联网也能看到完整界面）
│   ├── scripts/                    # 资源生成、静态校验、契约校验
│   └── src/
│       ├── api/                    # http.js（统一响应处理）+ index.js（接口清单）
│       ├── components/             # ItemCard / ImageUploader / EmptyState / SkeletonGrid
│       ├── layouts/                # AppLayout（前台）/ AdminLayout（后台）
│       ├── router/                 # 路由与登录/管理员守卫
│       ├── stores/                 # Pinia：登录态与未读角标
│       ├── styles/index.css        # 设计变量（主色 #ffd100）
│       ├── utils/format.js         # 状态字典、金额/时间/头像格式化
│       └── views/                  # 前台页面 + views/admin（管理后台）
├── docker/                         # 部署产物
│   ├── bobo-server/Dockerfile      # Maven 多阶段构建 → JRE 运行
│   └── bobo-web/                   # 前端构建 + nginx 配置
├── mysql/init/                     # 01-schema.sql（建表）、02-seed.sql（演示数据）
├── docs/                           # 架构、数据库、API、部署文档
├── docker-compose.yml
└── .env.example
```

## 五、快速开始

### 5.1 Docker 一键部署（推荐）

前置：一台装了 Docker 与 Docker Compose v2 的机器（本地或云服务器），放行 `80` 端口。

```bash
cd bobo
cp .env.example .env          # 必须修改 MYSQL_PASSWORD 与 BOBO_JWT_SECRET
docker compose up -d --build  # 首次构建约 3-8 分钟（下载依赖）
docker compose ps             # 三个容器都应为 healthy / running
```

访问：

- 前台：`http://<服务器IP>/`
- 管理后台：`http://<服务器IP>/admin`（或用前台登录页底部的「管理员登录入口」）

> 两个常见环境问题：
> - 同一台机器已有别的 `bobo-mysql` / `bobo-server` 容器时，改 `.env` 的
>   `BOBO_CONTAINER_PREFIX` 与 `BOBO_VOLUME_PREFIX`（如都设为 `bobo-campus`）即可共存；
> - 访问 Docker Hub 超时（国内常见）时，给 `.env` 配 `BOBO_REGISTRY=docker.1ms.run/`
>   与 `BOBO_MYSQL_IMAGE=docker.1ms.run/mysql:8.0`。
> 详见 [docs/deploy.md](docs/deploy.md) 的排障手册 7.8。

演示账号（来自 `02-seed.sql`，**上生产前请立即改密码**）：

| 账号 | 密码 | 角色 |
|---|---|---|
| `admin` | `password` | 管理员 |
| `xiaoyu` | `password` | 普通用户（有在售商品） |
| `lulu` | `password` | 普通用户 |
| `tiedan` | `password` | 普通用户 |
| `mianbao` | `password` | 普通用户 |

### 5.2 本地开发

后端（需要 JDK 17 + Maven 3.9+ + MySQL 8）：

```bash
cd bobo-server
# 1) 建库并导入初始化脚本（或直接用 docker compose up -d bobo-mysql）
mysql -h127.0.0.1 -uroot -p < ../mysql/init/01-schema.sql
mysql -h127.0.0.1 -uroot -p bobo < ../mysql/init/02-seed.sql
# 2) 用环境变量覆盖默认数据源（默认值是 localhost:3306/bobo，账号 bobo/bobo123456）
mvn spring-boot:run
# 接口文档：http://localhost:9080/swagger-ui.html
```

前端：

```bash
cd bobo-web
pnpm install       # 或 npm install
pnpm dev           # http://localhost:5173，已配置 /api 与 /upload 代理到 9080
pnpm build         # 产物在 dist/
```

常用校验脚本（都不依赖额外依赖）：

```bash
cd bobo-web
pnpm run check                   # = check-static + check-contract，见下
node scripts/check-static.mjs    # 脚本语法、模板表达式、标签与括号配平
node scripts/check-contract.mjs  # 前端调用的接口是否都能在后端找到（92 条路由逐一比对）
node scripts/gen-assets.mjs      # 重新生成 public/img 下的占位图
python scripts/make-favicon.py   # 生成 favicon.ico / apple-touch-icon.png（需 Pillow）
```

管理后台的端到端验证（真实浏览器，能抓到「接口全 200 但页面点不进去」这类问题）：

```bash
# 先起一个带调试端口的浏览器
"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
  --headless=new --remote-debugging-port=9222 --user-data-dir=%TEMP%\bobo-probe about:blank
# 再跑探针：8 个后台页面 × （打开 / 数据渲染 / 菜单高亮 / 点击跳转 / 无 4xx）+ 4 个前台页面
cd bobo-web && pnpm probe:admin
```

忘记密码 / 需要手工生成密码散列（后端用的是 PBKDF2，不是 BCrypt）：

```bash
cd bobo-server
java -jar target/bobo-server.jar --bobo.password-tool=bobo@2026 --bobo.password-user=admin

# 或容器里（用 run 起临时容器，不影响正在跑的服务，注意参数要放在服务名之后）：
docker compose run --rm --no-deps bobo-server --bobo.password-tool=bobo@2026 --bobo.password-user=admin

# 若本机 9080 已被占用，加上这个参数让工具不启动 Web 容器：
#   --spring.main.web-application-type=none
# 输出会直接给出可执行的 UPDATE 语句
```

## 六、默认配置与端口

| 项 | 默认值 | 覆盖方式 |
|---|---|---|
| 后端端口 | 9080 | `SERVER_PORT` |
| 站点端口 | 80 | `.env` 的 `WEB_PORT` |
| MySQL 端口（宿主） | 3306 | `.env` 的 `MYSQL_PORT`（生产建议在 compose 里关掉对外映射） |
| 数据库名 / 账号 | `bobo` / `bobo` | `.env` 的 `MYSQL_DATABASE` / `MYSQL_USERNAME` |
| JWT 有效期 | 7 天 | `BOBO_JWT_EXPIRE_MINUTES` |
| 上传大小 | 单图 10MB，单请求 40MB | `application.yml` 的 `spring.servlet.multipart` |
| 管理员注册邀请码 | 空（关闭） | `BOBO_ADMIN_REGISTER_CODE` |

## 七、文档索引

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 分层设计、鉴权方案、订单状态机、一致性策略、扩展点 |
| [docs/database.md](docs/database.md) | 12 张表结构、索引设计、状态字段取值、初始化脚本说明 |
| [docs/ai.md](docs/ai.md) | **AI 能力设计与面试要点**：三场景选型、四级降级、成本与可观测性、安全边界、配置速查、离线测试 |
| [docs/api.md](docs/api.md) | 全部接口契约（前后端唯一事实来源） |
| [docs/deploy.md](docs/deploy.md) | 云服务器部署手册：安全组、HTTPS、备份、日志、升级、排障 |

## 八、设计取舍（为什么这么做）

1. **不引入 Spring Security**：只有「登录 + 管理员」两种门槛，拦截器 + `@Anonymous` 注解足够，
   而且 `AuthContext` 让 Service 层拿当前用户不需要一路透传 `userId`。
2. **不做 WebSocket**：聊天用 8 秒轮询。校园并发低，轮询省掉了长连接、心跳、在线状态与扩容复杂度。
3. **模拟支付**：校园场景多为当面转账/线下支付，平台只负责流程留痕，因此 `pay` 接口直接推进状态。
   要接真实支付，替换 `OrderService.pay` 为「创建预支付单 + 回调更新状态」即可。
4. **软删除 + 冗余计数**：`del_flag` 保证订单可回溯；`want_count` 只是排序用的冗余，每次收藏切换都按实际行数校准，
   不用自增自减，避免历史脏数据导致列表排序越来越离谱。
5. **图片用挂载卷 + nginx 直读**：不引对象存储，运维简单；nginx 用 `alias` 直接读文件，不占后端线程。
6. **单模块后端**：多模块（common/system/web）在十几张表的规模下只增加编译与依赖管理成本，收益不明显。

## 九、许可

本项目为教学/内部使用场景编写，代码可自由修改。演示数据中的商品文案与账号均为虚构。
