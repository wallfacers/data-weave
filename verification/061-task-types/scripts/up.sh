#!/usr/bin/env bash
# 起某 profile 的引擎环境。用法：./scripts/up.sh <olap|hive|integration|compute>
# 错峰约定：只起自己那批，跑完用 down.sh 停；禁全量常驻（单台 WSL2 防 OOM）。
set -euo pipefail

PROFILE="${1:?用法: up.sh <olap|hive|integration|compute>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="$HERE/compose.$PROFILE.yml"

[ -f "$COMPOSE" ] || { echo "缺少 $COMPOSE（该 profile 的 compose 尚未落地，见对应工作流任务）" >&2; exit 2; }

# 幂等创建共享网络
docker network inspect dw061net >/dev/null 2>&1 || docker network create dw061net

echo "[up] profile=$PROFILE → $COMPOSE"
docker compose -f "$COMPOSE" up -d
echo "[up] 已起。就绪判据见 README / 各 verify-<engine>.sh；跑完请 ./scripts/down.sh $PROFILE"
