#!/usr/bin/env bash
# 恢复指定 dump 到 .env 配置的库：pg_restore --clean --if-exists（会覆盖现有数据）。
# 用法：scripts/restore.sh <dumpfile> [--yes]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"

usage() {
  echo "用法：$0 <dumpfile> [--yes]" >&2
}

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

DUMP="$1"
shift
ASSUME_YES=""
for arg in "$@"; do
  case "$arg" in
    --yes|-y) ASSUME_YES="1" ;;
    *) echo "未知参数：$arg" >&2; usage; exit 1 ;;
  esac
done

if [[ ! -f "$DUMP" ]]; then
  echo "备份文件不存在：$DUMP" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "未找到 $ENV_FILE，请先运行 ./scripts/dev-up.sh 或从 .env.example 复制。" >&2
  exit 1
fi

read_env() {
  local key="$1" line value
  line="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)"
  [[ -z "$line" ]] && return 0
  value="${line#*=}"
  value="${value%$'\r'}"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  if [[ "$value" == \"*\" && "$value" == *\" ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

DB_HOST="${DB_HOST:-$(read_env DB_HOST)}"
DB_PORT="${DB_PORT:-$(read_env DB_PORT)}"
DB_NAME="${DB_NAME:-$(read_env DB_NAME)}"
DB_USER="${DB_USER:-$(read_env DB_USER)}"
DB_PASSWORD="${DB_PASSWORD:-$(read_env DB_PASSWORD)}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-waibao_dev}"
DB_USER="${DB_USER:-$(whoami)}"

if [[ -z "$ASSUME_YES" ]]; then
  read -r -p "将使用 --clean --if-exists 恢复到 ${DB_NAME}@${DB_HOST}:${DB_PORT}，现有数据会被覆盖。继续？[y/N] " answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *) echo "已取消。" >&2; exit 1 ;;
  esac
fi

PGPASSWORD="$DB_PASSWORD" pg_restore \
  --clean --if-exists --no-owner \
  -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  "$DUMP"

echo "恢复完成：$DUMP -> ${DB_NAME}@${DB_HOST}:${DB_PORT}"
