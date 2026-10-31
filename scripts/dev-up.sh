#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
else
  echo "[dev-up] 未找到 .env，已从 .env.example 复制（请把 DB_USER 改成本机用户名）"
  cp .env.example .env
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-$(whoami)}"
DB_NAME="${DB_NAME:-waibao_dev}"
export PGPASSWORD="${DB_PASSWORD:-}"

if ! command -v pg_isready >/dev/null 2>&1; then
  echo "[dev-up] 未找到 PostgreSQL 客户端。请先安装：brew install postgresql@18" >&2
  exit 1
fi

if ! pg_isready -h "$DB_HOST" -p "$DB_PORT" >/dev/null 2>&1; then
  echo "[dev-up] PostgreSQL 未启动。请执行：brew services start postgresql@18" >&2
  exit 1
fi

if psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -tAc \
  "select 1 from pg_database where datname = '$DB_NAME'" | grep -q 1; then
  echo "[dev-up] 数据库已存在：${DB_NAME}"
else
  echo "[dev-up] 创建数据库：${DB_NAME}（owner=${DB_USER}）"
  createdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -O "$DB_USER" "$DB_NAME"
fi

echo "[dev-up] 数据库已就绪：${DB_HOST}:${DB_PORT}/${DB_NAME}（用户 ${DB_USER}）"

if ! redis-cli ping >/dev/null 2>&1; then
  echo "[dev-up] Redis 未启动。请执行：brew services start redis" >&2
  exit 1
fi
echo "[dev-up] Redis 已就绪：$(redis-cli ping)"

if ! nc -z 127.0.0.1 5672 2>/dev/null; then
  echo "[dev-up] RabbitMQ 未启动。请执行：brew services start rabbitmq" >&2
  exit 1
fi
echo "[dev-up] RabbitMQ 已就绪：127.0.0.1:5672"

if ! nc -z 127.0.0.1 8081 2>/dev/null; then
  echo "[dev-up] 提示：XXL-Job admin 未启动，可另开终端执行 ./scripts/start-xxl-job-admin.sh（JDK17）" >&2
fi

echo ""
echo "下一步（另开终端）："
echo "  cd backend && mvn spring-boot:run"
echo "  curl -s http://localhost:8080/api/health"
echo ""
echo "前台：cd frontend && npm install && npm run dev   # http://localhost:3000"
echo "后台：cd admin && npm install && npm run dev      # http://localhost:5173"
echo "调度：./scripts/start-xxl-job-admin.sh            # http://localhost:8081/xxl-job-admin"
echo ""
echo "测试：cd backend && mvn test   # 自动创建并删除临时测试库，不需要 Docker"
