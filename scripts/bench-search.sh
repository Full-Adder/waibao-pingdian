#!/usr/bin/env bash
# 搜索压测：确保公司数 >= 5000（不足时按 6 城市 × 2 行业导入），
# 对 /api/companies/search 发起 100 次请求，输出 P95、平均耗时与失败率；P95 > 300ms 退出码 1。
# 依赖：curl、python3、根目录 .env（ADMIN_SEED_ACCOUNT / ADMIN_SEED_PASSWORD）、已启动后端。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
REQUESTS="${BENCH_REQUESTS:-100}"
KEYWORD="${BENCH_KEYWORD:-科技}"
TARGET_COMPANIES="${BENCH_TARGET_COMPANIES:-5000}"
P95_LIMIT_MS="${BENCH_P95_LIMIT_MS:-300}"
TIMEOUT_SECONDS="${BENCH_TIMEOUT_SECONDS:-10}"

read_env() {
  local key="$1" line value
  line="$(grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | tail -n 1 || true)"
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

if [[ ! -f "$ENV_FILE" ]]; then
  echo "未找到 $ENV_FILE，请先运行 ./scripts/dev-up.sh 或从 .env.example 复制。" >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1; then
  echo "需要 curl 与 python3。" >&2
  exit 1
fi

SERVER_PORT="${SERVER_PORT:-$(read_env SERVER_PORT)}"
SERVER_PORT="${SERVER_PORT:-8080}"
BASE_URL="${BASE_URL:-http://localhost:${SERVER_PORT}}"
ADMIN_ACCOUNT="${ADMIN_SEED_ACCOUNT:-$(read_env ADMIN_SEED_ACCOUNT)}"
ADMIN_PASSWORD="${ADMIN_SEED_PASSWORD:-$(read_env ADMIN_SEED_PASSWORD)}"

if [[ -z "$ADMIN_ACCOUNT" || -z "$ADMIN_PASSWORD" ]]; then
  echo "缺少 ADMIN_SEED_ACCOUNT / ADMIN_SEED_PASSWORD（见根目录 .env）。" >&2
  exit 1
fi

json_field() {
  # 用法：json_field '<json>' data.token；点分字段为空或解析失败时输出空串。
  python3 - "$1" "$2" <<'PY'
import json, sys
raw, path = sys.argv[1], sys.argv[2].split(".")
try:
    value = json.loads(raw)
    for key in path:
        value = value[key]
    print("" if value is None else value)
except Exception:
    print("")
PY
}

echo "[bench-search] 后端：${BASE_URL}"
HEALTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time "$TIMEOUT_SECONDS" "${BASE_URL}/api/health" || true)"
if [[ "$HEALTH_CODE" != "200" ]]; then
  echo "健康检查失败（HTTP ${HEALTH_CODE}），请先启动后端：cd backend && mvn -q spring-boot:run" >&2
  exit 1
fi

LOGIN_BODY="$(curl -sS --max-time "$TIMEOUT_SECONDS" -X POST "${BASE_URL}/api/admin/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"account\":\"${ADMIN_ACCOUNT}\",\"password\":\"${ADMIN_PASSWORD}\"}")"
TOKEN="$(json_field "$LOGIN_BODY" data.token)"
if [[ -z "$TOKEN" ]]; then
  echo "管理员登录失败：$(json_field "$LOGIN_BODY" message)" >&2
  exit 1
fi
AUTH_HEADER="Authorization: Bearer ${TOKEN}"

company_total() {
  local body
  body="$(curl -sS --max-time "$TIMEOUT_SECONDS" "${BASE_URL}/api/companies/search?page=1&size=1")"
  json_field "$body" data.total
}

wait_import_job() {
  local job_id="$1" status="" attempt=0 body
  while :; do
    body="$(curl -sS --max-time "$TIMEOUT_SECONDS" -H "$AUTH_HEADER" \
      "${BASE_URL}/api/admin/imports/${job_id}")"
    status="$(json_field "$body" data.status)"
    if [[ -z "$status" ]]; then
      echo "导入任务查询失败：${job_id}" >&2
      return 1
    fi
    if [[ "$status" != "RUNNING" ]]; then
      printf '%s' "$status"
      return 0
    fi
    attempt=$((attempt + 1))
    if [[ "$attempt" -gt 900 ]]; then
      echo "导入任务超时：${job_id}" >&2
      return 1
    fi
    sleep 1
  done
}

COUNT_BEFORE="$(company_total)"
COUNT_BEFORE="${COUNT_BEFORE:-0}"
echo "[bench-search] 当前 ACTIVE 公司数：${COUNT_BEFORE}"

if [[ "$COUNT_BEFORE" -lt "$TARGET_COMPANIES" ]]; then
  echo "[bench-search] 公司数不足 ${TARGET_COMPANIES}，开始 6 城市 × 2 行业导入…"
  for city in 北京 上海 深圳 杭州 广州 成都; do
    for industry in IT人力外包 IT项目外包; do
      created="$(curl -sS --max-time "$TIMEOUT_SECONDS" -X POST "${BASE_URL}/api/admin/imports" \
        -H "$AUTH_HEADER" -H 'Content-Type: application/json' \
        -d "{\"city\":\"${city}\",\"industry\":\"${industry}\",\"triggerType\":\"MANUAL\"}")"
      job_id="$(json_field "$created" data.id)"
      if [[ -z "$job_id" ]]; then
        echo "创建导入任务失败：${city}/${industry}：$(json_field "$created" message)" >&2
        exit 1
      fi
      status="$(wait_import_job "$job_id")"
      echo "  - ${city} / ${industry}: ${status} (job ${job_id})"
      if [[ "$status" == "FAILED" ]]; then
        echo "导入任务失败：${job_id}" >&2
        exit 1
      fi
    done
  done
  COUNT_AFTER="$(company_total)"
  echo "[bench-search] 导入后 ACTIVE 公司数：${COUNT_AFTER:-0}"
else
  echo "[bench-search] 公司数已满足 ${TARGET_COMPANIES}，跳过导入。"
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
RESULT_FILE="$TMP_DIR/timings.txt"
: > "$RESULT_FILE"

echo "[bench-search] 发起 ${REQUESTS} 次搜索：keyword=${KEYWORD} page=1 size=20"
for ((i = 1; i <= REQUESTS; i++)); do
  set +e
  measurement="$(curl -sS -o /dev/null --max-time "$TIMEOUT_SECONDS" \
    -w '%{http_code} %{time_total}' \
    --get "${BASE_URL}/api/companies/search" \
    --data-urlencode "keyword=${KEYWORD}" \
    --data-urlencode "page=1" \
    --data-urlencode "size=20" 2>"$TMP_DIR/curl.err")"
  code=$?
  set -e
  if [[ $code -ne 0 ]]; then
    echo "ERROR 0.000000" >> "$RESULT_FILE"
    continue
  fi
  http_code="${measurement%% *}"
  seconds="${measurement#* }"
  if [[ "$http_code" != "200" ]]; then
    echo "ERROR ${seconds}" >> "$RESULT_FILE"
  else
    echo "OK ${seconds}" >> "$RESULT_FILE"
  fi
done

python3 - "$RESULT_FILE" "$P95_LIMIT_MS" <<'PY'
import sys
path, limit = sys.argv[1], float(sys.argv[2])
total = ok = 0
timings = []
for line in open(path, encoding="utf-8"):
    parts = line.split()
    if not parts:
        continue
    total += 1
    if parts[0] == "OK":
        ok += 1
        timings.append(float(parts[1]) * 1000.0)
timings.sort()
if not timings:
    print("[bench-search] 没有成功的请求")
    sys.exit(1)
p95 = timings[min(len(timings) - 1, int(round((len(timings) - 1) * 0.95)))]
avg = sum(timings) / len(timings)
failure_rate = (total - ok) / total * 100 if total else 100.0
print(f"[bench-search] 成功 {ok}/{total}，失败率 {failure_rate:.2f}%")
print(f"[bench-search] 平均 {avg:.1f}ms，P50 {timings[len(timings)//2]:.1f}ms，P95 {p95:.1f}ms（阈值 {limit:.0f}ms）")
if p95 > limit or failure_rate > 0:
    sys.exit(1)
PY
