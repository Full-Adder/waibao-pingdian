#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

echo "[build-all] 后端打包 ..."
(cd backend && mvn -q -DskipTests package)

echo "[build-all] 前台构建 ..."
(cd frontend && npm install && npm run build)

echo "[build-all] 后台构建 ..."
(cd admin && npm install && npm run build)

echo "[build-all] 完成"
