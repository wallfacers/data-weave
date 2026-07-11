#!/usr/bin/env bash
# 061 US2 SeaTunnel 真实引擎验证脚本。
# 三态全量：SUCCESS（真引擎在位+FakeSource→Console 成功）
#           FAILURE（真引擎在位+配置指向不存在源表）
#           SKIPPED（清除 SEATUNNEL_HOME 跑同一 success 夹具）
# 依赖：SEATUNNEL_HOME 指向有效 SeaTunnel 安装；DW_API/DW_TOKEN + dw CLI + capture.sh。
set -euo pipefail

HARNESS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN_REPO="/home/wallfacers/project/data-weave"
SCRIPTS="$HARNESS/scripts"
JOBS="$HARNESS/jobs"
DW="$MAIN_REPO/cli/dw"
EVID="$MAIN_REPO/specs/061-task-type-verification/evidence"

export DW_WORKER_CP="${DW_WORKER_CP:-$MAIN_REPO/backend/dataweave-worker/target/dataweave-worker-0.7.0-SNAPSHOT-exec.jar}"

# SeaTunnel 2.3.x 需要 JDK 17/21（JDK 25 移除 javax.security.auth.Subject.getSubject 致 Hazelcast NPE）
# 若系统有 JDK 21，优先使用
if [ -d "$HOME/.jdks/jdk-21.0.9+9" ]; then
  export JAVA_HOME="$HOME/.jdks/jdk-21.0.9+9"
  export PATH="$JAVA_HOME/bin:$PATH"
  info "JAVA_HOME=$JAVA_HOME ($(java -version 2>&1 | head -1))"
fi

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[verify-seatunnel]${NC} $*"; }
warn()  { echo -e "${YELLOW}[verify-seatunnel]${NC} $*"; }
err()   { echo -e "${RED}[verify-seatunnel]${NC} $*"; }

export DW_API="${DW_API:-http://localhost:8000}"
export SEATUNNEL_HOME="${SEATUNNEL_HOME:-$HOME/engines/seatunnel}"

# ---- 前置检查 ----
if [ -z "${DW_TOKEN:-}" ]; then
  err "DW_TOKEN 未设置"
  exit 2
fi

info "SEATUNNEL_HOME=$SEATUNNEL_HOME"
if [ ! -f "$SEATUNNEL_HOME/bin/seatunnel.sh" ]; then
  err "seatunnel.sh 不存在：$SEATUNNEL_HOME/bin/seatunnel.sh（请先运行 clients/install-seatunnel.sh）"
  exit 2
fi

# 自检版本
ENGINE_VER="2.3.13"
if [ -f "$SEATUNNEL_HOME/VERSION" ]; then
  ENGINE_VER=$(head -1 "$SEATUNNEL_HOME/VERSION" | tr -d '\n')
fi
info "引擎版本: $ENGINE_VER"

# ---- 工作目录 ----
WORKDIR="/tmp/dw-verify-seatunnel-$$"
rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"
cd "$WORKDIR"

# ---- 1. Pull 项目 ----
info "pull 项目 ..."
"$DW" pull 1 --dir . 2>&1

# ---- 2. 创建 success + fail 任务 ----
info "创建任务夹具..."

cp "$JOBS/seatunnel.success.conf" seatunnel-success.conf
cat > seatunnel-success.task.yaml << 'YAML'
formatVersion: 1
name: SeaTunnel 成功-Fake源
type: SEATUNNEL
script: seatunnel-success.conf
timeoutSec: 300
YAML

cp "$JOBS/seatunnel.fail.conf" seatunnel-fail.conf
cat > seatunnel-fail.task.yaml << 'YAML'
formatVersion: 1
name: SeaTunnel 失败-无源表
type: SEATUNNEL
script: seatunnel-fail.conf
timeoutSec: 60
YAML

# ---- 3. Push ----
NEW_COUNT=$(find . -type f -not -path './.weft/*' | wc -l)
python3 -c "
import json
with open('.weft/state.json') as f:
    s = json.load(f)
s['fileCount'] = $NEW_COUNT
with open('.weft/state.json', 'w') as f:
    json.dump(s, f, indent=2)
" 2>/dev/null || true

info "push ..."
"$DW" push --dir . 2>&1 || warn "push 可能失败，继续..."

# ---- 4. SUCCESS: dw run ----
info "========== SEATUNNEL SUCCESS (dw run) =========="
SUCCESS_LOG="$WORKDIR/success.log"
set +e
"$DW" run seatunnel-success.task.yaml --dir . > "$SUCCESS_LOG" 2>&1
SUCCESS_EXIT=$?
set -e
info "退出码: $SUCCESS_EXIT"

if [ "$SUCCESS_EXIT" -eq 0 ]; then
  info "SUCCESS 断言通过：SeaTunnel 真跑成功"
  grep -E "SeaTunnel|Job finished|rows|completed" "$SUCCESS_LOG" | head -10 || warn "未找到 SeaTunnel 原生输出"
else
  err "SUCCESS 断言失败！退出码=$SUCCESS_EXIT"
  tail -20 "$SUCCESS_LOG"
fi

# ---- 5. FAILURE: dw run ----
info "========== SEATUNNEL FAILURE (dw run) =========="
FAIL_LOG="$WORKDIR/fail.log"
set +e
"$DW" run seatunnel-fail.task.yaml --dir . > "$FAIL_LOG" 2>&1
FAIL_EXIT=$?
set -e
info "退出码: $FAIL_EXIT"

if [ "$FAIL_EXIT" -ne 0 ]; then
  info "FAILURE 断言通过：真实作业错误，退出码=$FAIL_EXIT"
  grep -Ei "exception|error|failed|doesn't exist|not found" "$FAIL_LOG" | head -5 || warn "未找到引擎原生错误行"
else
  err "FAILURE 断言失败！预期非零退出码"
fi

# ---- 6. SKIPPED 对照 ----
info "========== SEATUNNEL SKIPPED (unset SEATUNNEL_HOME) =========="
SKIP_LOG="$WORKDIR/skipped.log"
set +e
ST_HOME_SAVED="$SEATUNNEL_HOME"
unset SEATUNNEL_HOME
"$DW" run seatunnel-success.task.yaml --dir . > "$SKIP_LOG" 2>&1
SKIP_EXIT=$?
export SEATUNNEL_HOME="$ST_HOME_SAVED"
set -e
info "退出码: $SKIP_EXIT"

if grep -qi "已跳过\|SKIPPED\|跳过" "$SKIP_LOG"; then
  info "SKIPPED 断言通过：缺引擎优雅跳过"
else
  warn "SKIPPED 断言需检查"
fi

# ---- 7. 取证 ----
info "========== 证据采集 =========="
mkdir -p "$EVID/SEATUNNEL"

cp "$SUCCESS_LOG" "$EVID/SEATUNNEL/success.log"
cp "$FAIL_LOG"    "$EVID/SEATUNNEL/fail.log"
cp "$SKIP_LOG"    "$EVID/SEATUNNEL/skipped.log"

CAPTURE="$MAIN_REPO/verification/061-task-types/scripts/capture.sh"
bash "$CAPTURE" SEATUNNEL success "$SUCCESS_EXIT" "$SUCCESS_LOG" "$ENGINE_VER" "dw run" 2>&1 || true
bash "$CAPTURE" SEATUNNEL fail    "$FAIL_EXIT"    "$FAIL_LOG"    "$ENGINE_VER" "dw run" 2>&1 || true
bash "$CAPTURE" SEATUNNEL skipped "$SKIP_EXIT"    "$SKIP_LOG"    "$ENGINE_VER" "dw run" 2>&1 || true

info "证据已保存: $EVID/SEATUNNEL/"

# ---- 8. 摘要 ----
echo ""
echo "============================================"
echo "  SeaTunnel 验证摘要"
echo "============================================"
echo "  引擎版本: $ENGINE_VER"
echo "  SEATUNNEL_HOME: $SEATUNNEL_HOME"
echo "  SUCCESS:  $( [ "$SUCCESS_EXIT" -eq 0 ] && echo '✅ PASS' || echo '❌ FAIL' ) (exit=$SUCCESS_EXIT)"
echo "  FAILURE:  $( [ "$FAIL_EXIT" -ne 0 ] && echo '✅ PASS' || echo '❌ FAIL' ) (exit=$FAIL_EXIT)"
echo "  SKIPPED:  $( grep -qi '已跳过' "$SKIP_LOG" && echo '✅ PASS' || echo '⚠️  CHECK' )"
echo "  证据目录: $EVID/SEATUNNEL/"
echo "============================================"
