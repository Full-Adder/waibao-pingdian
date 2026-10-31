#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

echo "[check-contract] 校验 spec/openapi/openapi.yaml ..."
npx --yes @redocly/cli lint spec/openapi/openapi.yaml
echo "[check-contract] 通过"
