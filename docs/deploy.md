# bobo 校园二手交易平台 —— 云服务器部署手册

> 从装 Docker 到验收、加固、备份、排障的可执行流程。命令默认在**项目根目录**（含 `docker-compose.yml` 那层）执行。
> 设计说明见 [architecture.md](architecture.md)，接口契约见 [api.md](api.md)，本地开发见 [README.md](../README.md)。

## 一、服务器要求

| 项 | 建议 | 说明 |
|---|---|---|
| CPU / 内存 | **2 核 2G**（最低） | MySQL 约 350-500MB、后端默认 `-Xmx512m`、nginx 约 20MB；建议加 1-2G swap |
| 系统盘 | **40G** | 镜像（含 Maven/Node 构建层）约 3-5G，其余给 MySQL 与上传图片 |
| 操作系统 | Ubuntu 22.04/24.04 LTS 或 Alibaba Cloud Linux 3 / CentOS Stream 9 | 不依赖宿主发行版 |
| 架构 | x86_64 / arm64 均可 | `maven:3.9-eclipse-temurin-17`、`node:20-alpine`、`nginx:1.27-alpine`、`mysql:8.0` 均有 arm64 |

`--build` 期间 Maven 与 Vite 同时跑，峰值内存可能到 1.5-2G；2G 机器要么加 swap，要么在本地构建好镜像再推送。
**需开放的端口**：`80`（`WEB_PORT`，前台/后台/`/api`/`/upload` 全部入口，**必须放行**）、`443`（配 HTTPS 时）、
`22`（SSH，建议限来源 IP）；**`3306`（`MYSQL_PORT`）与 `9080` 都不要对公网开放**——9080 仅在 `bobo-net` 内被 nginx 访问。

**Ubuntu / Debian** 装 Docker 与 Compose v2：
`curl -fsSL https://get.docker.com | sh && sudo systemctl enable --now docker && sudo usermod -aG docker "$USER"`
（重新登录后免 sudo）。**CentOS / Alibaba Cloud Linux**：

```bash
sudo dnf install -y dnf-plugins-core && sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker && sudo usermod -aG docker "$USER"
```

校验（需 Docker 20.10+、Compose v2）：`docker version --format '{{.Server.Version}}'; docker compose version`。
2G 内存加 2G swap：

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

> 本项目用 Compose v2 语法（顶层 `name:`、`depends_on.condition`）。若只有老的 `docker-compose` v1 独立二进制，请安装 `docker-compose-plugin`，不要用 v1。

## 二、部署步骤

```bash
# 1) 上传代码（方式一：git clone）
sudo mkdir -p /opt && cd /opt && git clone <你的仓库地址> bobo && cd bobo
# 方式二：本地打包后上传（排除依赖与产物目录）
#   tar --exclude='bobo-web/node_modules' --exclude='bobo-server/target' --exclude='.git' -czf bobo.tar.gz .
#   scp bobo.tar.gz root@<IP>:/opt/ && ssh root@<IP> 'mkdir -p /opt/bobo && tar -xzf /opt/bobo.tar.gz -C /opt/bobo'
#   rsync 增量同步：rsync -avz --delete --exclude node_modules --exclude target ./ root@<IP>:/opt/bobo/
# 2) 生成 .env 并填三个必改项
cp .env.example .env && openssl rand -base64 48 && openssl rand -base64 48 && openssl rand -base64 48
grep -E '^(MYSQL_ROOT_PASSWORD|MYSQL_PASSWORD|BOBO_JWT_SECRET)=' .env | sed 's/=.*/=<已设置>/'   # 复核
# 3) 构建启动（首次 3-8 分钟，需下载 Maven/npm 依赖）
docker compose up -d --build && docker compose logs -f      # Ctrl-C 只退出日志，不停容器
docker compose ps
curl -I http://127.0.0.1/                                                   # 200 + index.html
curl http://127.0.0.1/healthz                                               # ok
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/api/categories     # 200（匿名接口）
```

**必须修改的变量及怎么生成**：

| 变量 | 不改的后果 | 怎么生成 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | 默认占位 `change-me-root-password`；compose 用 `${…:?}` 做非空校验，留空直接拒绝启动 | `openssl rand -base64 48` |
| `MYSQL_PASSWORD` | 同上；这是后端业务账号密码 | `openssl rand -base64 48` |
| `BOBO_JWT_SECRET` | 默认密钥已公开在仓库里，任何人都能伪造 token；不足 32 字节时 `JwtUtil` **拒绝启动** | `openssl rand -base64 48`（base64 的 48 字节 ≈ 64 字符） |
| `BOBO_ADMIN_REGISTER_CODE` | 保持**留空**：留空则不生效 `adminCode` 分支，无法自助注册管理员 | 不填；临时开放时设一次性值，用完清空 |

> ⛔ **数据库初始化后再改 `.env` 里的密码，MySQL 不会自动改已有账号**，必须进库 `ALTER USER`（见 6.3），否则后端连不上库。`.env` 已在 `.gitignore` 中，勿提交。

**`docker compose ps` 期望状态**：`bobo-mysql` 为 `Up (healthy)`（healthcheck 是
`mysqladmin ping -h 127.0.0.1 -u root -p$MYSQL_ROOT_PASSWORD`，`interval 10s / retries 12 / start_period 40s`，
首次约 40-60 秒转 healthy）；`bobo-server` 为 `Up (healthy)`（healthcheck 是
`curl -fs http://127.0.0.1:9080/v3/api-docs`，`start_period 60s`，并用 `depends_on: service_healthy` 等 MySQL）；
`bobo-web` 为 `Up`（部分版本显示 `healthy`，镜像内探测 `/healthz`）。
**首次启动会发生什么**：① `bobo-mysql` 数据目录（卷 `bobo-mysql-data`）为空 → 官方镜像按文件名顺序执行
`/docker-entrypoint-initdb.d`：`01-schema.sql` 建 12 张表，`02-seed.sql` 写入 16 个分类、6 个演示账号与
商品/订单/评价/公告/举报，`03-ai.sql` 建 AI 的 3 张表，`04-seed-ai.sql` 写入 AI 演示数据（采纳反馈与调用流水）；
字符集由 compose `command` 固定 `utf8mb4`/`utf8mb4_unicode_ci`、时区 `+08:00`。
② `bobo-server` 等 MySQL healthy 后启动：建 Hikari 连接池、校验 JWT 密钥长度、准备 `/app/data/upload` 与 `/app/data/logs`。
③ `bobo-web` 启动 nginx，把 `bobo-upload` 卷只读挂到 `/data/upload`，站点配置挂到 `/etc/nginx/conf.d/default.conf`。
**初始化脚本只在数据目录为空时执行一次**，此后重启、`up -d`、`--build` 都不会重跑（见 7.2）。

### 2.1 已有部署升级到「带 AI 版本」（重要，AI 升级必读）

AI 版本新增了 init 脚本与站点子路径，**对已有数据卷不会自动生效**，必须手工做三步：

```bash
# 1) 建 AI 相关表（init 脚本不会重跑，手动导入）
docker compose exec -T bobo-mysql mysql -ubobo -p"$PW" --default-character-set=utf8mb4 bobo \
  < mysql/init/03-ai.sql
# 运行期配置表（后台「AI 运营 → 模型配置」保存到这张表；不建表则只能靠环境变量）
docker compose exec -T bobo-mysql mysql -ubobo -p"$PW" --default-character-set=utf8mb4 bobo \
  < mysql/init/05-ai-setting.sql
# 可选：AI 演示数据（让后台面板一进去就有数）
docker compose exec -T bobo-mysql mysql -ubobo -p"$PW" --default-character-set=utf8mb4 bobo \
  < mysql/init/04-seed-ai.sql

# 2) 重建并重启两个镜像（子路径与配置页都在这一版里）
docker compose up -d --build --no-deps bobo-server bobo-web
```

**AI 配置怎么填**：登录管理后台 →「AI 运营」→「模型配置」→ 填 API Key →
点「测试连通性」确认可用 → 点「保存并生效」（**不重启容器**）。
也可以继续用环境变量 `BOBO_AI_API_KEY`：**配置表里有值以配置表为准，表里为空则回落环境变量**，
所以首次部署只注入环境变量即可用，之后想改再进后台。

### 2.2 站点路径：默认挂在 /bobo-campus/ 下

本版本把整个前端挂在子路径 `/bobo-campus/`，根路径会 302 跳过去：
`http://<服务器IP>/` → `http://<服务器IP>/bobo-campus/`。

- 前端 base 由 `docker-compose.yml` 的 `bobo-web.build.args.VITE_BASE` 决定
  （默认 `/bobo-campus/`，可用 `.env` 的 `BOBO_WEB_BASE` 覆盖）；
- nginx 的 location 前缀写在 `docker/bobo-web/nginx/default.conf`；
- **两处必须一致**，改一处不改另一处的典型表现是「页面能打开但接口全 404」或白屏。

`/api/**` 与 `/upload/**` 的**根路径版本依然保留**且不做重定向，因为：
① 数据库里历史图片存的是根路径 URL（如 `/upload/2026/01/x.jpg`），重定向会让老图 404；
② 容器 HEALTHCHECK 与运维脚本打的是 `/api/categories`。
带前缀的 `/bobo-campus/api/**` 与 `/bobo-campus/upload/**` 同时可用（nginx 会剥掉前缀）。

**改了 nginx 配置不用重建镜像**：该文件以 `:ro` 挂进容器，改完执行
`docker exec <web容器> nginx -t && docker exec <web容器> nginx -s reload` 即可生效。

**验证**（两个探针都在仓库里，可复现）：

```bash
pwsh -File mysql/e2e/verify-ai.ps1 -BaseUrl http://localhost/bobo-campus -AdminToken <管理员token>
pwsh -File mysql/e2e/verify-ai-adaptive.ps1 -BaseUrl http://localhost/bobo-campus
node bobo-web/scripts/probe-admin.mjs   # 需先设 BOBO_SITE=http://localhost/bobo-campus
```

`verify-ai.ps1` 用固定数据集断言；`verify-ai-adaptive.ps1` 先读**线上真实分类与价位**再构造查询，
因此在任何数据集上都能给出有意义结论（线上实测 6/6 命中，对照 LIKE 整句 0 命中；
若在售商品过少它会明确 SKIP 而不是误报失败）。

**不配 key 也可以先上**：`BOBO_AI_API_KEY` 为空且配置表里也没填时，应用正常启动，
`/api/ai/status` 返回 `{"available":false,"degraded":true}`，
发布助手与检索解析仍可用（结果标注「规则推算」）。


## 三、验证清单

首页 `http://<服务器IP>/` 应看到瀑布流、16 个分类、顶栏搜索与移动端底部导航；`http://<服务器IP>/admin`
未登录会被路由守卫送到 `/admin/login`。演示账号 `admin`/`xiaoyu`/`lulu`/`tiedan`/`mianbao`，密码均为 `password`。

> ✅ **演示账号可直接登录**：`02-seed.sql` 里的散列已是后端 `PasswordEncoder` 认的 PBKDF2 格式
> （`pbkdf2$sha256$120000$<salt>$<hash>`，与 `com.bobo.security.PasswordEncoder` 一致），
> 不需要任何修复脚本。真伪一条命令即可验证：
>
> ```bash
> curl -s -X POST http://127.0.0.1/api/auth/login -H 'Content-Type: application/json' \
>   -d '{"username":"admin","password":"password"}' | head -c 200     # 应返回带 token 的 JSON
> ```
>
> 若确实返回「用户名或密码错误」，按顺序查：① 是否连到了别的库（`MYSQL_DATABASE`）；
> ② 是否手工改过 `bobo_user.password`；③ 后端日志里 `PasswordEncoder` 相关异常。
> ⚠️ **生产环境必须立刻改掉 `admin` 的弱密码**（见 4.1）。
>
> 自己造账号还有一个永远可用的办法——走注册接口，密码由后端自己 encode：
>
> ```bash
> curl -s -X POST http://127.0.0.1/api/auth/register -H 'Content-Type: application/json' \
>   -d '{"username":"mymaster","password":"改成你的强密码","nickname":"站长"}'
> ```

**业务链路验收**（建议 2 个浏览器或用无痕窗口），完整走一遍「发布 → 审核 → 下单 → 支付 → 评价」：
**发布**：`xiaoyu` 登录 → 发布闲置，填标题/价格/分类/成色/交易方式/校区/地点，上传 1-9 图 → 提交成功，
「我的发布」显示 `待审核`，图片当场可预览（说明 `/upload/**` 已通）。**审核**：管理员 → 商品管理 → 通过（或驳回并填原因）
→ 状态变 `在售`（或 `已驳回` 且带原因），卖家收到审核通知。**下单**：`lulu` 登录 → 商品详情 → 下单（当面填地点时间；
邮寄先加收货地址）→ 跳订单详情，商品变 `已售`，卖家收到通知。**支付**：订单详情点「去支付」→ 当面交易直接到 `待评价`，
邮寄到 `待发货`；**发货/收货**（仅邮寄）：卖家填快递公司 + 单号发货 → `待收货`，买家确认收货 → `待评价`。
**评价**：双方各评一次（1-5 星 + 内容）→ 评价出现在商品与用户主页，被评方信用分按 4-5 星 +1、1-2 星 -1 变化。
**想要/私信/退款**：点「想要」→ 想要数 +1；从详情「聊一聊」→ 聊天页 8 秒轮询能看到新消息、未读角标 +1；
买家在 `待发货/待收货/待评价` 申请退款 → 卖家同意 → `退款中` → `已退款`，商品恢复 `在售`（若无其它有效订单占用）。

**管理后台各页面**依次验收：数据看板（概览卡片、近 7 天趋势折线、状态饼图、分类柱图、待处理事项）、商品管理（筛选、审核、强制下架、恢复、删除、批量通过/下架）、分类管理（增删改启停，分类下有商品时删除应被拒绝）、订单管理（状态筛选 + 详情抽屉时间轴）、用户管理（封禁/解封、学生认证审核、调整信用分、行为统计）、评价管理（隐藏/显示/删除）、举报处理（待处理优先，认定违规可同时下架商品、隐藏评价、扣信用分）、公告管理（全站公告或指定用户通知、删除）。

## 四、安全加固

**4.1 改掉演示账号密码**：种子账号本身可用（见第三节验证清单），登录后台用「个人中心 → 修改密码」改掉 `admin` 的 `password` 即可；同时停用用不到的演示号（逻辑删除值为 2），并把演示数据里写死的手机号 `1380000000x`、邮箱 `*@bobo.edu` 一并替换：
`docker compose exec -T bobo-mysql mysql -ubobo -p"$(grep '^MYSQL_PASSWORD=' .env|cut -d= -f2-)" bobo -e "UPDATE bobo_user SET del_flag = 2 WHERE username IN ('xiaoyu','lulu','tiedan','mianbao');"`

**4.2 不暴露 MySQL 端口**：把 `docker-compose.yml` 里 `bobo-mysql` 的 `ports` 段注释掉（改 `.env` 的 `MYSQL_PORT` 只换宿主机端口号，仍然对外监听）：

```yaml
  bobo-mysql:
    # ports:
    #   - "${MYSQL_PORT:-3306}:3306"
```

然后 `docker compose up -d bobo-mysql && docker compose ps bobo-mysql`（PORTS 列不应再出现 `0.0.0.0:3306`），
并用 `timeout 3 bash -c 'cat < /dev/null > /dev/tcp/127.0.0.1/3306' && echo '仍在监听！' || echo '已关闭，符合预期'` 验证。
运维连库统一用容器内客户端（第六节命令均为该形式）；云安全组里确认 3306 未放行。

**4.3 `BOBO_ADMIN_REGISTER_CODE` 留空**：`grep -n '^BOBO_ADMIN_REGISTER_CODE=' .env` 应显示等号后为空。
`AuthService.register` 仅在「传了 `adminCode` 且与配置的非空值相等」时创建管理员，留空即永久关闭该入口；
临时开放时设一次性值 → `docker compose up -d bobo-server` → 注册完立刻清空并重建。

**4.4 JWT 密钥必须替换**：默认值 `bobo-campus-second-hand-trading-platform-secret-key-2026` 在 `application.yml`
里公开可见，**不替换等于没有鉴权**（任何人可签出任意 `userId`、`admin=true` 的 token）。换成
`openssl rand -base64 48` 后必须 `docker compose up -d bobo-server`（环境变量变了要重建容器，`restart` 不重读 `.env`），
再用 `docker compose logs --tail=50 bobo-server | grep -i BOBO_JWT_SECRET` 确认没有「长度不足」的拒绝启动报错。
⚠️ 换密钥会让**所有已签发 token 立即失效**（见 7.5），建议低峰期操作。

**4.5 接口文档默认关闭与临时开启**：`docker/bobo-web/nginx/default.conf` 只暴露 `/`、`/api/`、`/upload/`，
**不含** `/swagger-ui/` 与 `/v3/api-docs`，所以公网访问 `/swagger-ui.html` 会落到 SPA 回落而拿不到文档
（`bobo-server` 自身 healthcheck 探测的 `/v3/api-docs` 是容器内回环访问，与对外暴露无关）。临时开启：
`cp docker/bobo-web/nginx/default.with-docs.conf docs-nginx.conf`（该文件已列入 `.gitignore`），
把 `docker-compose.yml` 里 `bobo-web` 的站点配置挂载源改成 `./docs-nginx.conf:/etc/nginx/conf.d/default.conf:ro`，
`docker compose up -d bobo-web`；调试完改回 `./docker/bobo-web/nginx/default.conf:/etc/nginx/conf.d/default.conf:ro` 并再次 `up -d bobo-web`。

**4.6 上传目录不允许脚本执行**：镜像内配置已在上传目录里嵌套了
`location ~* \.(?:php|jsp|sh|py|pl|html?)$ { deny all; }`；后端另有扩展名白名单
`bobo.upload.allow-ext=jpg,jpeg,png,webp,gif`，且 nginx 容器不含 PHP/JSP 运行环境。验证：
`curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/upload/../etc/passwd` 期望 400/404，
`curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/upload/test.php` 期望 403。

**4.7 防火墙规则**：Ubuntu / Debian 用
`sudo ufw allow 22/tcp && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw enable && sudo ufw status verbose`；
CentOS / Alibaba Cloud Linux 用
`sudo firewall-cmd --permanent --add-service=ssh --add-service=http --add-service=https && sudo firewall-cmd --reload`。
云控制台安全组用同样规则（22 限来源 IP、80、443），**不放行 3306/9080**。Docker 会自行写 iptables 发布端口，所以安全组与系统防火墙都要收口，尤其要落实 4.2 的注释。

## 五、HTTPS / 域名

**方案一：云厂商 SLB / 证书直接挂（推荐）**。控制台申请免费 SSL 证书，域名解析到 SLB（或直接绑定 ECS 公网 IP），
SLB 监听 443 → 服务器 80，证书在 SLB 卸载。应用侧**零改动**（容器仍监听 80）；服务器安全组只需允许来自 SLB 的 80；
强制 HTTPS 在 SLB 上配 HTTP→HTTPS 跳转比改 nginx 简单。

**方案二：宿主 nginx 反代到 `127.0.0.1:80`（最常用，改动最小）**。`sudo apt-get install -y nginx`（或 `dnf`），
写 `/etc/nginx/conf.d/bobo.conf`，其中 `listen 80` 的 server 块只做 `return 301 https://$host$request_uri` 全量跳转；
`listen 443 ssl; http2 on;` 的 server 块配置 `ssl_certificate` / `ssl_certificate_key`
（如 `/etc/nginx/ssl/bobo.example.com.pem` 与 `.key`）、`ssl_protocols TLSv1.2 TLSv1.3;`、
`ssl_ciphers HIGH:!aNULL:!MD5;`、`ssl_session_cache shared:SSL:10m;` 与 `client_max_body_size 40m;`
（容器内 nginx 限 40m，宿主层不能更小，否则大图先被这里拒掉），并包含：

```nginx
    location / {
        proxy_pass http://127.0.0.1:80;                    # 指向 bobo-web 容器
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;   # 必须带
        proxy_set_header X-Forwarded-Proto $scheme;                      # 必须带
        proxy_set_header Connection        "";
        proxy_connect_timeout 10s;
        proxy_send_timeout    60s;
        proxy_read_timeout    60s;
    }
```

生效：`sudo nginx -t && sudo systemctl reload nginx`。

**为什么必须带这两个头**：`X-Forwarded-For` 让容器内 nginx 的 `$http_x_forwarded_for` 与后端的
`getRemoteAddr()` 能追溯到真实客户端，否则看到的全是 `127.0.0.1`，登录 IP 记录与排障都失真；
`X-Forwarded-Proto` 让下游知道用户实际走 HTTPS，避免将来做强制跳转或生成绝对链接时判断成 `http` 造成重定向死循环。
`/api/`、`/upload/`、SPA 回落仍由容器内 nginx 处理，宿主层只需一个 `location /`。

**方案三：certbot 证书 + 容器内 nginx 改造**。让证书进容器：compose 增加 `- "${WEB_PORT_SSL:-443}:443"` 与
`- /etc/letsencrypt:/etc/letsencrypt:ro`，容器内 nginx 增加 `listen 443 ssl;` 与证书路径。申请与续期：
`sudo apt-get install -y certbot`、`sudo certbot certonly --standalone -d bobo.example.com`（需先释放 80 端口）、
`sudo certbot renew --dry-run` 验证自动续期。`default.conf` 是挂载文件，改完
`docker compose exec bobo-web nginx -s reload` 即可，不必重建镜像；但 certbot 续期后不会自动重载 nginx，需加
`--deploy-hook "docker compose -f /opt/bobo/docker-compose.yml exec bobo-web nginx -s reload"`。
三种方案中这条维护成本最高，除非不能装宿主 nginx，否则推荐方案一或二。

## 六、运维

**6.1 日志**：`cd /opt/bobo` 后，`docker compose logs -f bobo-server` 看实时 stdout，
`docker compose logs --tail=200 --since=30m bobo-server` 看最近 30 分钟最后 200 行；
容器内文件日志在 `bobo-logs` 卷的 `/app/data/logs`，用
`docker compose exec bobo-server tail -n 200 -f /app/data/logs/bobo-server.log` 跟踪，
`docker compose exec bobo-server ls -lh /app/data/logs` 看滚动历史。
文件日志路径 `${BOBO_LOG_PATH}/bobo-server.log`，滚动策略单文件 20MB、保留 14 个、总量上限 500MB；
级别由 `.env` 的 `BOBO_LOG_LEVEL`（默认 `info`）控制，改后 `docker compose up -d bobo-server` 生效。排障时不要长期开 `debug`。

**6.2 重启与升级**：日常命令如下，升级不会影响 `bobo-mysql-data`/`bobo-upload`/`bobo-logs` 三个卷。

```bash
docker compose restart bobo-server                    # 只重启（改了 .env 需用下面的 up -d 重建容器）
docker compose up -d bobo-server                      # 重建容器，不重新构建镜像
docker compose up -d --no-deps bobo-server            # 只重建该服务，不动它的依赖
git pull && docker compose up -d --build              # 代码更新：重建有变化的镜像
docker compose up -d --build --no-deps bobo-server    # 只改了后端：不重启 MySQL/nginx
docker compose up -d --build --no-deps bobo-web       # 只改了前端源码
docker compose exec bobo-web nginx -t && docker compose exec bobo-web nginx -s reload   # 只改站点配置（挂载文件，无需重建）
docker image prune -f && docker compose ps            # 清理旧构建层；慎用 -a（会删所有未使用镜像）
```

但**不要**为让改动生效而随手 `down -v`（见 6.5）。

**6.3 MySQL 备份与恢复**：

```bash
cd /opt/bobo && PW="$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-)" && mkdir -p /opt/bobo-backup
# 备份（--single-transaction 对 InnoDB 不锁表；--default-character-set 不能省，否则中文可能变 ?）
docker compose exec -T bobo-mysql mysqldump -ubobo -p"$PW" --single-transaction --routines --triggers \
  --default-character-set=utf8mb4 bobo > /opt/bobo-backup/bobo-$(date +%F-%H%M).sql
ls -lh /opt/bobo-backup/ && grep -c 'CREATE TABLE' /opt/bobo-backup/bobo-*.sql | tail -1   # 校验非空且含建表语句
```

定时备份 `/opt/bobo-backup/backup.sh`（`chmod +x` 后加入 crontab：`10 3 * * * /opt/bobo-backup/backup.sh >> /var/log/bobo-backup.log 2>&1`）：

```bash
#!/usr/bin/env bash
set -euo pipefail
cd /opt/bobo
PW="$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-)"; DIR=/opt/bobo-backup; mkdir -p "$DIR"
docker compose exec -T bobo-mysql mysqldump -ubobo -p"$PW" --single-transaction --routines --triggers \
  --default-character-set=utf8mb4 bobo | gzip > "$DIR/bobo-$(date +%F-%H%M).sql.gz"
find "$DIR" -name 'bobo-*.sql.gz' -mtime +14 -delete      # 只保留最近 14 天
echo "$(date '+%F %T') backup ok"
```

恢复（⚠️ 会**覆盖**目标库同名表，先给自己留退路）：`docker compose exec -T bobo-mysql mysqldump -ubobo -p"$PW"
--single-transaction bobo > /opt/bobo-backup/before-restore.sql` 留下现状，然后
`gunzip -c /opt/bobo-backup/bobo-*.sql.gz | docker compose exec -T bobo-mysql mysql -ubobo -p"$PW" --default-character-set=utf8mb4 bobo`
导入，用 `... -e "SELECT COUNT(*) AS users FROM bobo_user;" bobo` 校验，最后 `docker compose restart bobo-server`。
注意事项：备份**不含上传图片**（图片在卷里，见 6.4），换机器恢复时必须两者一起恢复，否则「商品在但图全 404」；
恢复的库若早于当前代码，注意表结构差异（本项目无迁移工具，改表均为手写 SQL）；**换了 `BOBO_JWT_SECRET` 后旧 token
全部失效**，用户需重新登录（密码散列与密钥无关，原密码仍可用）。

**6.4 上传图片卷备份**：用一次性容器打包，避免权限与路径问题。

```bash
docker run --rm -v bobo-upload:/data/upload:ro -v /opt/bobo-backup:/backup \      # 备份
  alpine tar -czf /backup/upload-$(date +%F).tar.gz -C /data/upload .
docker run --rm -v bobo-upload:/data/upload -v /opt/bobo-backup:/backup \         # 恢复（⚠️ 覆盖同名文件，不删多余文件）
  alpine sh -c 'tar -xzf /backup/upload-2026-01-08.tar.gz -C /data/upload && ls -l /data/upload | head'
```

`bobo_item.cover`、`bobo_item_image.url`、`bobo_order.item_cover` 存的是 `/upload/yyyy/MM/<uuid>.<ext>` 这类
**URL 字符串**，所以数据库备份与卷备份必须成对恢复。

**6.5 数据卷位置与清理注意事项**：`docker volume ls | grep bobo` 可列出 `bobo-mysql-data` / `bobo-upload` / `bobo-logs`，
`docker volume inspect bobo_mysql-data --format '{{.Mountpoint}}'` 得到宿主真实路径（前缀为项目名 `bobo`），
`sudo du -sh /var/lib/docker/volumes/bobo_*` 看各卷占用。挂载点：`bobo-mysql-data` 对应 `bobo-mysql:/var/lib/mysql`（全部业务数据，
初始化脚本仅在其为空时执行）；`bobo-upload` 对应 `bobo-server:/app/data/upload`（rw）与 `bobo-web:/data/upload`（ro），存上传图片；
`bobo-logs` 对应 `bobo-server:/app/data/logs`，存 `bobo-server.log` 及其滚动历史。

> ⚠️⚠️ **`docker compose down -v` 会删除上述三个卷，即全部业务数据、全部上传图片与全部日志，不可恢复。**
> 只有确实要用最新 `mysql/init/*.sql` 从零重建时才可用，且必须先执行 `/opt/bobo-backup/backup.sh` 与 6.4 的卷备份
> （用 `alpine tar` 打到 `/opt/bobo-backup/upload-before-reset-$(date +%F-%H%M).tar.gz`），确认两个备份非空后再 `down -v`。
> 其他命令的边界：`docker compose down`（不带 `-v`）只停容器删网络、**数据保留**；`docker volume rm bobo_upload`
> 需先 `down`，删了图就没了；`docker system prune -a --volumes` **会连带删除未使用的卷**，执行前务必核对卷列表。

## 七、排障手册

**7.1 后端启动失败：数据库连不上**
*现象*：`bobo-server` 反复 `Restarting`/`Exit`，日志报 `Communications link failure`、`Unknown database 'bobo'` 或 `Access denied for user 'bobo'`。
*原因*：① MySQL 首次初始化未完成、healthcheck 未过就被依赖启动；② 数据卷已初始化后又改了 `.env` 的 `MYSQL_PASSWORD`
（MySQL 不会自动改已有账号密码）；③ `MYSQL_DATABASE`/`MYSQL_USERNAME` 与库内实际不一致。
*处理*：先 `docker compose ps` 确认 `bobo-mysql` 是否 healthy，再看
`docker compose logs --tail=100 bobo-mysql | grep -Ei 'error|denied|init'`，并用
`docker compose exec -T bobo-mysql mysql -ubobo -p"$(grep '^MYSQL_PASSWORD=' .env|cut -d= -f2-)" -e 'SELECT 1' bobo`
验证容器内能否用应用账号连库。若属于原因②，用 root 执行
`ALTER USER 'bobo'@'%' IDENTIFIED BY '<新密码>'; FLUSH PRIVILEGES;`（或把 `.env` 改回旧密码）；若属于原因③，
执行 `CREATE DATABASE bobo DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;` 再导入 `mysql/init`。
改完 `docker compose up -d --no-deps bobo-server`。

**7.2 改了 `mysql/init` 里的 SQL 但没生效**
*现象*：修改 `01-schema.sql`/`02-seed.sql` 后 `up -d --build`，库结构与数据毫无变化。
*原因*：`/docker-entrypoint-initdb.d` 只在**数据目录为空**时执行，已有数据卷会跳过；重启、重建容器与镜像都不会重跑。
*处理*（⚠️ 会丢数据，先备份）：按顺序执行 `bash /opt/bobo-backup/backup.sh`、`docker compose down`、
`docker volume rm bobo_mysql-data`（只删库卷、不动图片卷，效果等同 `down -v` 但更可控）、`docker compose up -d --build`，
再用 `docker compose logs -f bobo-mysql` 确认出现 `Initializing database` 与脚本执行日志。
只改种子数据、不想动表结构时更安全：直接打进现有库——
`docker compose exec -T bobo-mysql mysql -ubobo -p"$PW" --default-character-set=utf8mb4 bobo < mysql/init/02-seed.sql`
（该文件用 `DELETE FROM` + `INSERT`，会清掉对应表的演示数据）。

**7.3 前端刷新 `/admin/items` 报 404**
*现象*：站内点击能进后台，但刷新或直接粘贴 `http://<IP>/admin/items` 得到 nginx 404。
*原因*：SPA 是 history 路由，`/admin/items` 在磁盘上不存在，需要 nginx 回落到 `index.html`；当前配置的
`location / { try_files $uri $uri/ /index.html; }` 已处理，出现 404 多为 ① 站点配置被换成自改版本丢了这一行；
② 挂载了错误的 `default.conf`；③ 叠加了宿主 nginx（方案二）而它的 `location /` 指向静态目录而非容器。
*处理*：`docker compose exec bobo-web cat /etc/nginx/conf.d/default.conf | grep -A3 'location /'` 确认回落规则存在，
`docker compose exec bobo-web nginx -t` 校验语法，`curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/admin/items`
期望 200。

**7.4 上传的图片 404**
*现象*：上传成功、表单里能预览，保存后列表/详情图片全裂，`/upload/2026/01/xxx.jpg` 返回 404。
*原因*：三层路径不一致——① `bobo-web` 未挂载 `bobo-upload` 卷或挂错路径；② nginx `alias /data/upload/`
与实际挂载点不符；③ `BOBO_UPLOAD_URL_PREFIX` 与 `location /upload/` 前缀不一致；④ 换机器只迁了库没迁图片卷。
*处理*：先比对两侧文件——`docker compose exec bobo-server ls -l /app/data/upload/2026/01/ | head` 与
`docker compose exec bobo-web ls -l /data/upload/2026/01/ | head`，再查
`docker compose exec bobo-server env | grep BOBO_UPLOAD` 与
`docker compose exec bobo-web cat /etc/nginx/conf.d/default.conf | grep -A4 'location /upload'`。
两边文件不一致 → compose 里 `- bobo-upload:/data/upload:ro` 丢失或卷名写错；文件一致但 404 → `alias` 或 URL
前缀不一致；换机器场景 → 按 6.4 恢复图片卷。

**7.5 登录后立刻 401**
*现象*：登录接口返回了 token，但紧接着 `/api/auth/me` 返回 `{"code":401,…}`，前端弹出「去登录」。
*原因*：① **`BOBO_JWT_SECRET` 被改过**（旧 token 验签失败）；② token 已过期（`BOBO_JWT_EXPIRE_MINUTES`
默认 10080 分钟 = 7 天，被调小则很快失效）；③ **服务器系统时间不准**，签发与校验的时间基准偏差超过有效期；
④ 用户被管理员封禁（拦截器回查用户直接不写入上下文，属预期行为）。
*处理*：`docker compose logs --tail=100 bobo-server | grep -i 'token 解析失败'` 确认是验签/过期问题；
`docker compose exec bobo-server date; date` 对比时间与时区（应为 `Asia/Shanghai`）；
`docker compose exec bobo-server env | grep -E 'BOBO_JWT|TZ'` 查密钥与有效期；
`docker compose exec -T bobo-mysql mysql -ubobo -p"$PW" -e "SELECT user_id,username,status,del_flag FROM bobo_user WHERE username='<账号>';" bobo`
确认没被封禁；时间不准就 `sudo timedatectl set-ntp true && timedatectl status` 开 NTP。
密钥变更导致的 401 只能重新登录，属预期，应公告用户。

**7.6 内存不足 OOM**
*现象*：容器随机被杀（`Exited (137)`），`journalctl -k` 出现 `Out of memory: Killed process … java`；构建期被 killed。
*原因*：2C2G 上「MySQL 缓冲池 + JVM 堆 + 构建期 Maven/Node」叠加超限。
*处理*（按收益排序）：① 调小堆——`.env` 的 `JAVA_OPTS` 从 `-Xmx512m` 降到 `-Xmx384m`（4C8G 可提 `-Xmx1024m`），
改后 `docker compose up -d --no-deps bobo-server`（该变量由 Dockerfile 的
`ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]` 展开）；② 调小 MySQL——在 compose 的
`bobo-mysql.command` 追加 `- --innodb-buffer-pool-size=256M`（2G 机器可降到 `128M`）、`- --performance-schema=OFF`、
把 `--max-connections=300` 降为 `100`；③ 按第一节加 swap，并在本地构建好镜像再上传，避免在 2G 机器上 `--build`。
定位元凶：`docker stats --no-stream`、`docker inspect bobo-server --format '{{.State.OOMKilled}}'`（`true` 表示被杀过）、`journalctl -k --since '1 hour ago' | grep -i 'killed process'`。

**7.7 中文乱码**
*现象*：页面或库里中文显示为 `???`，或出现 `æµ‹è¯•`（UTF-8 被按 Latin-1 解释）；日志中文乱码。
*原因*：① 容器 `TZ`/`LANG` 不对（后端镜像已设 `TZ=Asia/Shanghai`、`LANG=C.UTF-8`，重写 `ENTRYPOINT`
或换基础镜像可能丢失）；② JDBC URL 字符集或 MySQL 服务端字符集不是 utf8mb4；③ 备份/恢复漏了
`--default-character-set=utf8mb4`（最常见的「恢复后变 ?」）。
*处理*：`docker compose exec bobo-server sh -c 'date; echo $LANG'` 查容器时区与编码；
`docker compose exec -T bobo-mysql mysql -ubobo -p"$PW" -e "SHOW VARIABLES LIKE 'character_set_%';"` 与
`... -e "SELECT TABLE_NAME,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA='bobo';" | head -20`
查服务端与表字符集。期望服务端 `character_set_server = utf8mb4`、`collation_server = utf8mb4_unicode_ci`
（compose `command` 已固定），表排序规则 `utf8mb4_unicode_ci`；JDBC URL 已带
`useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`，正常无需改。已写坏的数据只能重新导入并显式指定
`--default-character-set=utf8mb4`。另外「时间差 8 小时」属时区而非乱码：检查 `TZ` 与 MySQL 的 `default-time-zone=+08:00`。

**7.8 构建/启动失败的四个高频场景（都在本机 Docker 上实测踩过）**

**(a) `container name "/bobo-mysql" is already in use`**
*现象*：`docker compose up -d` 报容器名冲突，或直接停掉了别人的容器。
*原因*：同一台机器上已经有一套容器叫 `bobo-mysql` / `bobo-server`（例如另一个 bobo 项目）。
*处理*：本仓库的容器名与卷名都做成可覆盖的了，改 `.env` 里的前缀即可两套共存：

```bash
BOBO_CONTAINER_PREFIX=bobo-campus
BOBO_VOLUME_PREFIX=bobo-campus
docker compose up -d
```
注意卷名前缀也要改，否则两套服务会共用同一个 MySQL 数据卷（库名相同的话就是互相写脏数据）。

**(b) 拉不到基础镜像：`dial tcp ... registry-1.docker.io:443: connectex ...`**
*现象*：`docker compose build` 卡在 `load metadata for docker.io/library/nginx:...` 然后超时。
*原因*：服务器访问 Docker Hub 受限（国内常见）。
*处理*：给 `.env` 配镜像站前缀（会被拼到两个 Dockerfile 的 `FROM` 前面，结尾必须带 `/`）：

```bash
BOBO_REGISTRY=docker.1ms.run/
BOBO_MYSQL_IMAGE=docker.1ms.run/mysql:8.0
docker compose build
```
也可以给 Docker daemon 配 `registry-mirrors`（`/etc/docker/daemon.json` 后 `systemctl restart docker`），
那样 `BOBO_REGISTRY` 就可以留空。

**(c) 上传的图片全部 404（但文件确实在卷里）**
*现象*：`ls /data/upload/...` 在 web 容器里能看到文件，`curl` 该 URL 却是 404。
*原因*：nginx 里 `/upload/` 少了 `^~`，被前端静态资源的正则 location 抢先命中，
于是去 `root`（`/usr/share/nginx/html`）找图片。
*处理*：确认配置是 `location ^~ /upload/`（本仓库已修正）。
完整分析与验证命令见 [docker/bobo-web/nginx/README.md](../docker/bobo-web/nginx/README.md)。

**(d) `evil.php` 之类能被直接下载（安全）**
*现象*：`curl http://<站点>/upload/evil.php` 返回 200。
*原因*：`^~` 会跳过所有顶层正则，所以「拒绝脚本扩展名」的规则如果写在外面就完全失效。
*处理*：该规则必须嵌套在 `location ^~ /upload/ { ... }` 内部（本仓库已修正）。
上传接口本身也有扩展名白名单（只允许 jpg/jpeg/png/webp/gif），这是第二道防线。

**7.9 后台左侧菜单点不进去（页面停在原地或一片空白）**
*现象*：管理后台能登录、看板能打开，但点左侧「商品管理 / 订单管理」等没有反应，或地址栏变成
`/admin/admin-items` 这种不存在的路径、内容区空白。
*原因*：`AdminLayout.vue` 里 `el-menu` 的 `index` 写的是**路由 name**（`admin-items`）。
`el-menu` 在 `router` 模式下执行的是 `router.push(index)`，把 index 当**路径**；
`admin-items` 是相对路径，会被拼到当前路由后面 → `/admin/admin-items` → 匹配到 404 兜底路由。
*处理*：`index` 必须写绝对路径（`/admin/items`），`default-active` 也用路径
（本仓库已修正，`activeMenu` 计算属性按 `route.path` 匹配）。
*自查命令*（改完后台路由/菜单后跑一遍，它同时验证「能打开 / 数据已渲染 / 高亮正确 / 点击能跳转 / 无 4xx」）：

```bash
# 1) 启动一个带调试端口的浏览器（Edge 为例，Chrome 同理）
"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" \
  --headless=new --remote-debugging-port=9222 --user-data-dir=%TEMP%\bobo-probe about:blank
# 2) 跑仓库内的端到端探针
cd bobo-web && pnpm probe:admin
```
脚本会逐条打印 PASS/FAIL；`BOBO_SITE` / `BOBO_CDP` / `BOBO_ADMIN_USER` / `BOBO_ADMIN_PASS` 可覆盖默认值
（默认站点 `http://127.0.0.1`、CDP `http://127.0.0.1:9222`、账号 `admin/password`）。

**7.10 后台表格「没有数据」（先别急着当 bug）**
*现象*：订单管理/商品管理等表格是空的。
*原因与排查顺序*：① 页面是否有筛选条件——商品管理默认是「全部状态」，若手动选了「待审核」
而库里没有待审核商品就会空，此时表格会显示「暂无商品数据 / 共 0 条」，属正常；
② 直接查后端接口确认真实数量：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"password"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
curl -s "http://127.0.0.1/api/admin/orders?role=all&pageNum=1&pageSize=10" -H "Authorization: Bearer $TOKEN"
```
③ 如果接口 `total` 也是 0，但 `SELECT COUNT(*) FROM bobo_order` 有数据，那就是查询条件的问题——
本仓库就踩过一次：管理端传了哨兵 `currentUserId = -1`，而 SQL 的 `otherwise` 分支仍按
「买家或卖家 = -1」过滤，导致订单列表恒为空；修法是给 `role=all` 单独一个「不按用户过滤」的分支
（见 `OrderMapper.xml` 的 `selectOrderPage`）。

## 八、端口与变量速查表

| 变量 | `.env.example` 默认值 | 作用 | 生产建议 |
|---|---|---|---|
| `WEB_PORT` | `80` | `bobo-web` 容器 80 映射到宿主机的端口，站点入口 | 保持 80（前置 SLB/宿主 nginx 时也保持） |
| `BOBO_WEB_BASE` | `/bobo-campus/` | 前端挂载的子路径（构建参数 `VITE_BASE`），改后**必须重建 bobo-web 镜像** | 与 nginx location 前缀保持一致；想挂根路径改成 `/` |
| `MYSQL_PORT` | `3306` | `bobo-mysql` 映射到宿主机的端口 | **生产必须收口**：改成 `127.0.0.1:3306` 只绑本机回环（最省事），或把 compose 的 `ports` 段注释掉 |
| `MYSQL_ROOT_PASSWORD` | `change-me-root-password` | MySQL root 密码，仅初始化与运维用；compose 用 `:?` 强制非空 | **必须 `openssl rand -base64 48`** |
| `MYSQL_DATABASE` | `bobo` | 业务库名 | 保持 |
| `MYSQL_USERNAME` | `bobo` | 后端使用的业务账号 | 保持 |
| `MYSQL_PASSWORD` | `change-me-bobo-password` | 业务账号密码；compose 用 `:?` 强制非空 | **必须 `openssl rand -base64 48`** |
| `BOBO_JWT_SECRET` | `change-me-please-use-openssl-rand-base64-48-at-least-32-bytes` | JWT 签名密钥，不足 32 字节后端拒绝启动 | **必须 `openssl rand -base64 48`** |
| `BOBO_JWT_EXPIRE_MINUTES` | `10080` | 登录态有效期（分钟），10080 = 7 天 | 保持或缩短（如 4320 = 3 天） |
| `BOBO_ADMIN_REGISTER_CODE` | 空 | 管理员注册邀请码 | **保持留空** |
| `TZ` | `Asia/Shanghai` | 三个容器与 MySQL 的时区 | 保持 |
| `BOBO_LOG_LEVEL` | `info` | `com.bobo` 包日志级别 | 平时 `info`，排障临时 `debug` 后改回 |
| `JAVA_OPTS` | `-Xms256m -Xmx512m -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai` | JVM 参数（ENTRYPOINT 展开） | 2C2G 保持或降到 `-Xmx384m`；4C8G 可 `-Xmx1024m` |
| `BOBO_CONTAINER_PREFIX` | `bobo` | 容器名前缀 → `bobo-mysql` / `bobo-server` / `bobo-web` | 同机有多套 bobo 服务时改成独立前缀（见 7.8a） |
| `BOBO_VOLUME_PREFIX` | `bobo` | 卷名前缀 → `bobo-mysql-data` / `bobo-upload` / `bobo-logs` | 与容器前缀同改，否则两套服务共用数据卷 |
| `BOBO_REGISTRY` | 空 | 拼在两个 Dockerfile 的 `FROM` 前，用于换镜像站（结尾要带 `/`） | 国内填 `docker.1ms.run/`（见 7.8b） |
| `BOBO_MYSQL_IMAGE` | `mysql:8.0` | MySQL 用的镜像 | 换镜像站时同步改 |
| `BOBO_DEMO_ENABLED` | `false` | 演示数据重置等运维接口开关 | 生产保持 `false` |

其他由 `docker-compose.yml` 注入、`.env` 里没有的配置：`BOBO_UPLOAD_PATH=/app/data/upload`（对应卷 `bobo-upload`）、
`BOBO_UPLOAD_URL_PREFIX=/upload`（必须与 nginx `location /upload/` 一致）、`BOBO_LOG_PATH=/app/data/logs`
（对应卷 `bobo-logs`）、`SERVER_PORT=9080`、`SPRING_PROFILES_ACTIVE=prod`、`MYSQL_HOST=bobo-mysql`；
`application.yml` 里的 `bobo.upload.max-size-mb=10`、`bobo.upload.allow-ext=jpg,jpeg,png,webp,gif`、
`spring.servlet.multipart`（单文件 10MB / 单请求 40MB，与 nginx `client_max_body_size 40m` 对应，两边都要改）、`BOBO_DEMO_ENABLED=false`（演示数据重置等运维接口，生产保持关闭）。

容器、卷与网络：`bobo-mysql`（MySQL 8.0，`/var/lib/mysql`，healthcheck `mysqladmin ping`）、
`bobo-server`（Spring Boot，监听 9080，上传 `/app/data/upload`、日志 `/app/data/logs`）、
`bobo-web`（nginx 1.27，监听 80，静态 `/usr/share/nginx/html`、上传 `/data/upload` 只读、
站点配置 `/etc/nginx/conf.d/default.conf`）；卷 `bobo-mysql-data`（全部业务数据）、`bobo-upload`（上传图片，
后端 rw + nginx ro 共享）、`bobo-logs`（后端文件日志）；网络 `bobo-net`（bridge，服务名即 DNS 名）；
Compose 项目名 `bobo` 由 `docker-compose.yml` 的 `name: bobo` 指定，卷在宿主上显示为 `bobo_<卷名>`。
