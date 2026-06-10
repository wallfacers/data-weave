#!/usr/bin/env bash
# 构建 dw CLI 单二进制。产物 cli/dw 不入 git（见根 .gitignore）。
# 用法：./build.sh [GOOS GOARCH]，缺省构建本机平台。
set -euo pipefail
cd "$(dirname "$0")"

OUT="dw"
if [ "${1:-}" != "" ] && [ "${2:-}" != "" ]; then
  export GOOS="$1" GOARCH="$2"
  OUT="dw-${GOOS}-${GOARCH}"
fi

go build -trimpath -o "$OUT" .
echo "built: cli/$OUT"
