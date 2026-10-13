#!/usr/bin/env bash
# =============================================================================
# 本地开发一键启动（需要本机已装 JDK 17、Maven、Node 18+、MySQL 8）
#
#   ./dev.sh              启动后端 + 前端开发服务
#   ./dev.sh db           只启动一个跑在 Docker 里的 MySQL（推荐，省得自己装）
#   ./dev.sh stop         停掉本脚本起的所有进程
#
# 为什么用脚本而不是 docker compose：开发期改一行代码要重启容器太慢，
# 这里让后端跑 mvn spring-boot:run、前端跑 vite dev，改代码即时生效。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$ROOT/.run"
PID_DIR="$RUNTIME_DIR/pids"
LOG_DIR="$RUNTIME_DIR/logs"
mkdir -p "$PID_DIR" "$LOG_DIR"

DB_NAME="${MYSQL_DATABASE:-bobo}"
DB_USER="${MYSQL_USERNAME:-bobo}"
DB_PASS="${MYSQL_PASSWORD:-bobo123456}"
DB_PORT="${MYSQL_PORT:-3306}"
DB_ROOT_PASS="${MYSQL_ROOT_PASSWORD:-root123456}"

log()  { printf '\033[1;33m[bobo]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[bobo]\033[0m %s\n' "$*" >&2; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令：$1，请先安装（见 README 的「本地开发」）"
}

start_db() {
  need docker
  if docker ps --filter "name=bobo-dev-mysql" --format '{{.Names}}' | grep -q bobo-dev-mysql; then
    log "MySQL 开发容器已在运行（$DB_PORT）"
    return
  fi
  log "启动 MySQL 开发容器（端口 $DB_PORT，数据存在 named volume bobo-dev-mysql-data）"
  docker run -d --name bobo-dev-mysql \
    -e MYSQL_ROOT_PASSWORD="$DB_ROOT_PASS" \
    -e MYSQL_DATABASE="$DB_NAME" \
    -e MYSQL_USER="$DB_USER" \
    -e MYSQL_PASSWORD="$DB_PASS" \
    -e TZ=Asia/Shanghai \
    -p "$DB_PORT:3306" \
    -v bobo-dev-mysql-data:/var/lib/mysql \
    -v "$ROOT/mysql/init:/docker-entrypoint-initdb.d:ro" \
    mysql:8.0 \
    --character-set-server=utf8mb4 \
    --collation-server=utf8mb4_unicode_ci >/dev/null
  log "等待 MySQL 初始化（首次会自动执行 mysql/init 下的脚本）…"
  for _ in $(seq 1 60); do
    if docker exec bobo-dev-mysql mysqladmin ping -h127.0.0.1 -uroot -p"$DB_ROOT_PASS" --silent >/dev/null 2>&1; then
      log "MySQL 就绪"
      return
    fi
    sleep 2
  done
  fail "MySQL 启动超时，查看日志：docker logs bobo-dev-mysql"
}

start_backend() {
  need mvn
  [ -n "${JAVA_HOME:-}" ] || log "提示：JAVA_HOME 未设置，将使用 PATH 里的 java（需要 17+）"
  log "启动后端（http://localhost:9080，日志 $LOG_DIR/backend.log）"
  (
    cd "$ROOT/bobo-server"
    MYSQL_HOST=127.0.0.1 MYSQL_PORT="$DB_PORT" MYSQL_DATABASE="$DB_NAME" \
    MYSQL_USERNAME="$DB_USER" MYSQL_PASSWORD="$DB_PASS" \
    SERVER_PORT="${SERVER_PORT:-9080}" \
    BOBO_JWT_SECRET="${BOBO_JWT_SECRET:-bobo-local-dev-secret-key-please-change-in-prod-2026}" \
    nohup mvn -q spring-boot:run >"$LOG_DIR/backend.log" 2>&1 &
    echo $! >"$PID_DIR/backend.pid"
  )
}

start_frontend() {
  need node
  log "启动前端（http://localhost:5173，日志 $LOG_DIR/frontend.log）"
  (
    cd "$ROOT/bobo-web"
    if [ ! -d node_modules ]; then
      log "首次运行，安装前端依赖…"
      if command -v pnpm >/dev/null 2>&1; then pnpm install; else npm install; fi
    fi
    nohup npm run dev >"$LOG_DIR/frontend.log" 2>&1 &
    echo $! >"$PID_DIR/frontend.pid"
  )
}

stop_all() {
  for name in backend frontend; do
    if [ -f "$PID_DIR/$name.pid" ]; then
      pid="$(cat "$PID_DIR/$name.pid")"
      if kill -0 "$pid" >/dev/null 2>&1; then
        kill "$pid" && log "已停止 $name (pid $pid)"
      fi
      rm -f "$PID_DIR/$name.pid"
    fi
  done
  log "MySQL 容器未停止；如需停止：docker stop bobo-dev-mysql"
}

case "${1:-all}" in
  db)   start_db ;;
  stop) stop_all ;;
  all)  start_db; start_backend; start_frontend
        log "全部启动完成：前台 http://localhost:5173  管理后台 http://localhost:5173/admin"
        log "接口文档 http://localhost:9080/swagger-ui.html（后端起来后约 20 秒可用）" ;;
  *)    fail "未知参数：$1（可用：all | db | stop）" ;;
esac
