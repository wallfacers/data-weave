#!/usr/bin/env bash
# 停某 profile 的引擎环境（释放本机资源，错峰必用）。用法：./scripts/down.sh <profile>
set -euo pipefail

PROFILE="${1:?用法: down.sh <olap|hive|integration|compute>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="$HERE/compose.$PROFILE.yml"

[ -f "$COMPOSE" ] || { echo "缺少 $COMPOSE" >&2; exit 2; }

echo "[down] profile=$PROFILE"
docker compose -f "$COMPOSE" down -v
echo "[down] 已停并清卷。"
