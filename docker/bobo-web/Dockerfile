# =============================================================================
# bobo 校园二手交易平台 —— 前端镜像（构建 + nginx 运行合一）
# 之所以不再单独一个 frontend 容器：静态资源由 nginx 直接托管最快，
# 同时 nginx 还要负责 /api 与 /upload 的反向代理，合并成一个容器更省资源。
#
# 需要换成镜像站 / 私有仓库的基础镜像时（国内服务器访问 Docker Hub 常常超时）：
#   sed -i 's|^FROM node:|FROM docker.1ms.run/node:|; s|^FROM nginx:|FROM docker.1ms.run/nginx:|' Dockerfile
# 这里刻意不用 ARG ${VAR} 做参数化（当前 Docker/BuildKit 版本下会报
# "base name (${VAR}) should not be blank"），可读的 FROM 更可靠。
# =============================================================================

ARG REGISTRY=
# ---------- 构建阶段 ----------
FROM ${REGISTRY}node:22-alpine AS builder

# 站点 base 路径：站点可以部署在子路径下（例如 /bobo-campus/）。
# 必须在**构建期**注入，因为 Vite 要把 base 写进产物路径与 import.meta.env.BASE_URL。
# 默认 '/' 保证不传参时行为与以前完全一致。
# 注意：改这个值后 nginx 的 location 也要同步（见 docker/bobo-web/nginx/default.conf 顶部说明）。
ARG VITE_BASE=/
ENV VITE_BASE=${VITE_BASE}

WORKDIR /build

# 用 corepack 激活 package.json 里 packageManager 指定的 pnpm 版本，
# 配合 pnpm-lock.yaml 做到与本地开发完全一致的依赖树（npm ci 需要 package-lock.json，
# 而本仓库的锁文件是 pnpm 生成的，混用会得到不同的解析结果）。
RUN corepack enable

# 先只复制依赖清单，把依赖层缓存下来：改业务代码不必重新装依赖
COPY bobo-web/package.json bobo-web/pnpm-lock.yaml bobo-web/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY bobo-web/ .
RUN pnpm run build

ARG REGISTRY=
# ---------- 运行阶段 ----------
FROM ${REGISTRY}nginx:alpine

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 站点配置与静态产物
COPY --from=builder /build/dist /usr/share/nginx/html
COPY docker/bobo-web/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY docker/bobo-web/nginx/nginx.conf /etc/nginx/nginx.conf

EXPOSE 80

# 健康检查刻意走 /api/categories（会穿过 nginx 打到后端），而不是只看 /healthz：
# 后者只证明 nginx 活着，前者能同时证明「nginx → 后端」这条链路是通的，
# 否则容器显示健康、页面却全部报错，反而是更难发现的故障。
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/api/categories || exit 1

CMD ["nginx", "-g", "daemon off;"]
