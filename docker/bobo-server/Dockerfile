# =============================================================================
# bobo 校园二手交易平台 —— 后端镜像
# 多阶段构建：builder 用 Maven 打 fat jar，runtime 只带 JRE，镜像更小。
#
# 需要换成镜像站 / 私有仓库的基础镜像时（国内服务器访问 Docker Hub 常常超时），
# 用一行 sed 替换后照常构建：
#   sed -i 's|^FROM maven:|FROM docker.1ms.run/maven:|; s|^FROM eclipse-temurin:|FROM docker.1ms.run/eclipse-temurin:|' Dockerfile
# 这里刻意不用 ARG ${VAR} 做参数化：本机验证时发现该写法在当前
# Docker/BuildKit 版本下会出现 "base name (${VAR}) should not be blank"，
# 直接可读的 FROM 更可靠（要改就改这一行，没有隐式来源）。
# =============================================================================

ARG REGISTRY=
# ---------- 构建阶段 ----------
FROM ${REGISTRY}maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# 先只复制 pom，把依赖层缓存下来：改业务代码时不必重新下载依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

ARG REGISTRY=
# ---------- 运行阶段 ----------
FROM ${REGISTRY}eclipse-temurin:17-jre

# 时区与语言：日志时间和前端展示都按东八区，LANG 用于保障中文不乱码
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/target/bobo-server.jar /app/app.jar

# 上传目录与日志目录：由 docker-compose 挂载成卷，容器重建不丢数据
RUN mkdir -p /app/data/upload /app/data/logs

EXPOSE 9080

# 健康检查直接探接口文档地址：它由 Spring 自己提供，
# 能同时证明「Web 容器起来了」和「Spring 上下文装配完成」，
# 不需要额外引入 actuator 依赖。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fs http://127.0.0.1:9080/v3/api-docs >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
