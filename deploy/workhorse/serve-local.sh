#!/usr/bin/env bash
# 启动真 workhorse sidecar —— DataWeave 071 companion 大脑(DeepSeek 推理)。
#
# 【两种部署形态(072 收口后)】
#   - 标准/生产:docker compose(distributed profile)的 workhorse 受管服务(见 docker-compose.yml),
#     master 经服务名 http://workhorse:8300 寻址。优先用此。
#   - 本脚本(serve-local.sh):裸机本地调试备选,非容器(快速改 config.runtime 重启,不走 docker)。
#     容器化部署时勿启动本脚本(与 workhorse 容器争 8300)。二者配置对齐(allowed_origins 白名单 + bearer + mcp servers)。
#
# 可重入:先杀占用 8300 的旧进程(用 ss 取 pid,避免 pkill -f 自杀),再 setsid 脱离启动。
# 日志 → 项目根 tmp/wh.log。bind 0.0.0.0(non-loopback,配 allowed_origins 白名单收紧)。
#
# 前置(见 config.runtime.yaml):providers DeepSeek + allowed_origins 白名单 + auth.enabled + mcp servers(dataweave→/mcp)。
# 配套(master 侧):WorkhorseBrainClient 发 Origin(白名单)+ Bearer + docker-compose COMPANION_BRAIN_BASE_URL/COMPANION_BRAIN_ORIGIN。
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WH_ROOT="$(cd "$DIR/../.." && pwd)"   # data-weave 根
LOG="$WH_ROOT/tmp/wh.log"
BIN="$DIR/bin/workhorse-agent-linux-amd64"
CONFIG="$DIR/config.runtime.yaml"

# 1. 释放 8300(用 ss 取 pid;打印占用者进程名供审计——脚本语义是清端口让位 workhorse,
#    非 workhorse 占用者如联调假 workhorse 也清,但显式标记透明可追溯)
pid8300=$(ss -ltnp 2>/dev/null | grep ':8300' | grep -oP 'pid=\K[0-9]+' | head -1 || true)
if [ -n "$pid8300" ]; then
  comm=$(ps -p "$pid8300" -o comm= 2>/dev/null | tr -d ' \n' || echo "?")
  case "$comm" in
    *workhorse*) echo "释放 8300(kill 旧 workhorse pid=$pid8300)" ;;
    *) echo "释放 8300(kill 占用者 pid=$pid8300 comm=$comm —— 非 workhorse,仍清以让位)" ;;
  esac
  kill "$pid8300" 2>/dev/null || true; sleep 1
fi

# 2. setsid 脱离启动(WSL2 防后台被杀 + 日志不挂管道)
setsid bash -c "cd '$DIR' && '$BIN' serve --config '$CONFIG' --host 0.0.0.0 --port 8300 > '$LOG' 2>&1" </dev/null >/dev/null 2>&1 & disown
sleep 4

# 3. 验证监听
if ss -ltn 2>/dev/null | grep -q ':8300'; then
  echo "workhorse 已启动 [::]:8300  日志: $LOG"
  echo "健康探活: curl -s localhost:8300/health"
else
  echo "!! 启动失败,日志尾部:"; tail -20 "$LOG" 2>/dev/null; exit 1
fi
