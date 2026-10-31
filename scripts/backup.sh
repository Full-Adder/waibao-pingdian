#!/usr/bin/env bash
# 每日备份：pg_dump -Fc 到 backups/，默认保留最近 30 份。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "未找到 $ENV_FILE，请先运行 ./scripts/dev-up.sh 或从 .env.example 复制。" >&2
  exit 1
fi

# 只读取指定键，避免 source 整个 .env。
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

BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}"
KEEP="${BACKUP_KEEP:-30}"
mkdir -p "$BACKUP_DIR"

TS="$(date +%Y%m%d_%H%M%S)"
OUT="$BACKUP_DIR/${DB_NAME}_${TS}.dump"

umask 077
PGPASSWORD="$DB_PASSWORD" pg_dump \
  -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -Fc -f "$OUT"

# 校验归档可读，避免留下损坏备份。
pg_restore --list "$OUT" >/dev/null
chmod 600 "$OUT"

if [[ "$KEEP" -gt 0 ]]; then
  ls -1t "$BACKUP_DIR"/*.dump 2>/dev/null \
    | tail -n +"$((KEEP + 1))" \
    | while IFS= read -r old; do
        [[ -n "$old" ]] && rm -f "$old"
      done
fi

echo "备份完成：$OUT"
