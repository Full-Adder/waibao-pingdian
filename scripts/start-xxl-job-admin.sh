#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

JAR="$ROOT_DIR/infra/xxl-job/xxl-job-admin-2.4.1.jar"
if [ ! -f "$JAR" ]; then
  cat >&2 <<'EOF'
[start-xxl-job-admin] 未找到 infra/xxl-job/xxl-job-admin-2.4.1.jar

请先准备 admin 2.4.1 可执行 jar（本地交付，不提交远程仓库）：
  方式一（从源码构建，需 JDK17 + Maven）：
    git clone --depth 1 --branch 2.4.1 https://github.com/xuxueli/xxl-job.git /tmp/xxl-job-2.4.1
    cd /tmp/xxl-job-2.4.1 && mvn -pl xxl-job-admin -am package -DskipTests
    cp xxl-job-admin/target/xxl-job-admin-2.4.1.jar infra/xxl-job/
  方式二（使用官方发行包）：
    下载 https://github.com/xuxueli/xxl-job/releases/download/2.4.1/xxl-job-2.4.1.zip
    解压后把 xxl-job-admin/target/xxl-job-admin-2.4.1.jar 复制到 infra/xxl-job/
EOF
  exit 1
fi

DB_HOST="${XXL_DB_HOST:-127.0.0.1}"
DB_PORT="${XXL_DB_PORT:-3306}"
DB_NAME="${XXL_DB_NAME:-xxl_job}"
DB_USER="${XXL_DB_USER:-xxl}"
DB_PASSWORD="${XXL_DB_PASSWORD:-xxl_dev_password}"
ADMIN_PORT="${XXL_JOB_ADMIN_PORT:-8081}"
ACCESS_TOKEN="${XXL_JOB_ACCESS_TOKEN:-waibao_xxl_token}"
LOG_FILE="${XXL_JOB_ADMIN_LOG:-/tmp/xxl-job-admin.log}"

# admin 2.4.1 基于 Spring Boot 2.7，优先使用 JDK17
if [ -n "${JAVA17_HOME:-}" ] && [ -x "$JAVA17_HOME/bin/java" ]; then
  JAVA="$JAVA17_HOME/bin/java"
elif [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
  JAVA=/opt/homebrew/opt/openjdk@17/bin/java
elif /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  JAVA="$(/usr/libexec/java_home -v 17)/bin/java"
else
  echo "[start-xxl-job-admin] 未找到 JDK17，请先安装：brew install openjdk@17" >&2
  exit 1
fi

echo "[start-xxl-job-admin] JDK: $JAVA"
echo "[start-xxl-job-admin] DB : ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "[start-xxl-job-admin] URL: http://localhost:${ADMIN_PORT}/xxl-job-admin （admin / 123456）"
echo "[start-xxl-job-admin] 日志: ${LOG_FILE}"

exec "$JAVA" -jar "$JAR" \
  --server.port="${ADMIN_PORT}" \
  "--spring.datasource.url=jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai" \
  "--spring.datasource.username=${DB_USER}" \
  "--spring.datasource.password=${DB_PASSWORD}" \
  "--xxl.job.accessToken=${ACCESS_TOKEN}" >"${LOG_FILE}" 2>&1
