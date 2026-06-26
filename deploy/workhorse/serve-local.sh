#!/usr/bin/env bash
# 本地拉起 workhorse-agent(真 LLM 大脑) —— 固化 Fix A：解决「go 二进制只读单个 --config，
# 不合并 config.local.yaml、不认裸 ANTHROPIC_* env、env 无 model 覆盖项」导致有效 key 投递不进去、
# 聊天空白的坑(见 docs / 记忆 workhorse-config-loading-ignores-local-and-anthropic-env)。
#
# 做的事：config.yaml(模板, git) ⊕ config.local.yaml(真 key, gitignore) → config.runtime.yaml(gitignore)
#         → 用合并结果 + 绝对 mcp.json 路径，setsid 脱离进程组启动(WSL 下防 run_in_background 被 SIGTERM 误杀)。
#
# 用法：
#   deploy/workhorse/serve-local.sh                 # 端口 8300，前台
#   deploy/workhorse/serve-local.sh 8300 --detach   # 后台(setsid)，日志 /tmp/workhorse.log
#   WORKHORSE_BIN=/abs/workhorse-agent deploy/workhorse/serve-local.sh   # 覆盖二进制
set -euo pipefail
cd "$(dirname "$0")"

PORT="${1:-8300}"
DETACH=""
[ "${2:-}" = "--detach" ] && DETACH=1

# 二进制：优先 WORKHORSE_BIN，其次预编译 bin/，再次 workhorse 仓库 dist/
BIN="${WORKHORSE_BIN:-}"
if [ -z "$BIN" ]; then
  for c in "bin/workhorse-agent-linux-amd64" "$HOME/workspace/github/workhorse/workhorse-agent/dist/workhorse-agent"; do
    [ -x "$c" ] && BIN="$c" && break
  done
fi
[ -n "$BIN" ] && [ -x "$BIN" ] || { echo "✗ 找不到可执行 workhorse-agent 二进制；先跑 deploy/workhorse/fetch-bin.sh 或设 WORKHORSE_BIN" >&2; exit 1; }

if [ ! -f config.local.yaml ]; then
  echo "✗ 缺 config.local.yaml(放真 api_key 的覆盖层，已 gitignore)。" >&2
  echo "  从 config.yaml 复制 providers/models/agent 段，填上真实 api_key/base_url/models 即可。" >&2
  exit 1
fi

# 合并 → runtime 配置；mcp.config_path 改为本目录绝对路径(否则 go agent 找不到 mcp.json)
python3 merge-config.py config.yaml config.local.yaml > config.runtime.yaml
sed -i -E "s#^(  config_path:).*#\1 $(pwd)/mcp.json#" config.runtime.yaml

echo "✓ 已生成 config.runtime.yaml；启动 workhorse-agent @ 127.0.0.1:${PORT}（bin=${BIN}）"
if [ -n "$DETACH" ]; then
  setsid "$BIN" serve --config config.runtime.yaml --host 127.0.0.1 --port "$PORT" > /tmp/workhorse.log 2>&1 &
  sleep 3
  curl -s -o /dev/null -w "  /health => %{http_code}\n" --max-time 3 "http://127.0.0.1:${PORT}/health" || true
  echo "  日志：tail -f /tmp/workhorse.log"
else
  exec "$BIN" serve --config config.runtime.yaml --host 127.0.0.1 --port "$PORT"
fi
