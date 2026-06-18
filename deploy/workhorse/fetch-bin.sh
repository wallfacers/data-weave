#!/usr/bin/env bash
# workhorse-agent 六平台二进制分发脚本（变更 dataweave-managed-sidecar，tasks 5.1）。
#
# managed 模式（agent.workhorse.managed=true）下，DataWeave 后端按平台从 deploy/workhorse/bin/
# 选取 workhorse-agent-{goos}-{goarch}[.exe] 拉起。二进制是交付物非源码——由本脚本从 workhorse-agent
# 仓库 `scripts/build.sh all` 产出后拷入，目录已 .gitignore，不入 git（对齐 cli/ 的 dw 约定）。
#
# 用法：
#   WORKHORSE_AGENT_REPO=/path/to/workhorse-agent ./deploy/workhorse/fetch-bin.sh
#   # 或省略环境变量，脚本默认探测 ../workhorse-agent 与 ~/workspace/.../workhorse-agent
#
# 前置：workhorse-agent 仓库已 clone 且 Go 工具链可用（脚本只调用其 scripts/build.sh）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="${SCRIPT_DIR}/bin"

# 1) 定位 workhorse-agent 仓库
REPO="${WORKHORSE_AGENT_REPO:-}"
if [[ -z "${REPO}" ]]; then
  for cand in "${SCRIPT_DIR}/../../../workhorse-agent" "${HOME}/workspace/github/workhorse-agent" "../workhorse-agent"; do
    if [[ -f "${cand}/scripts/build.sh" ]]; then REPO="${cand}"; break; fi
  done
fi
if [[ -z "${REPO}" || ! -f "${REPO}/scripts/build.sh" ]]; then
  echo "ERROR: 找不到 workhorse-agent 仓库（含 scripts/build.sh）。" >&2
  echo "  设置 WORKHORSE_AGENT_REPO=/path/to/workhorse-agent 后重试。" >&2
  exit 1
fi
REPO="$(cd "${REPO}" && pwd)"
echo "==> workhorse-agent 仓库：${REPO}"

# 2) 构建六平台二进制（linux/darwin/windows × amd64/arm64）
echo "==> 构建六平台二进制（scripts/build.sh all）..."
( cd "${REPO}" && bash scripts/build.sh all )

# 3) 拷入 deploy/workhorse/bin/（覆盖旧版）
mkdir -p "${BIN_DIR}"
echo "==> 拷贝二进制 → ${BIN_DIR}"
found=0
for f in "${REPO}"/dist/workhorse-agent-* "${REPO}"/bin/workhorse-agent-*; do
  [[ -e "${f}" ]] || continue
  cp -f "${f}" "${BIN_DIR}/"
  chmod +x "${BIN_DIR}/$(basename "${f}")" 2>/dev/null || true
  echo "    $(basename "${f}")"
  found=1
done
if [[ "${found}" -eq 0 ]]; then
  echo "ERROR: build 后未在 ${REPO}/dist 或 ${REPO}/bin 找到 workhorse-agent-* 产物。" >&2
  echo "  确认 workhorse-agent scripts/build.sh all 的输出目录。" >&2
  exit 1
fi

echo "==> 完成。当前 ${BIN_DIR}："
ls -1 "${BIN_DIR}"
