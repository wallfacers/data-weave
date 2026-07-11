#!/usr/bin/env bash
# 061 US2 DataX 真实引擎验证脚本。
# 三态全量：SUCCESS（真引擎在位+streamreader→streamwriter 成功）
#           FAILURE（真引擎在位+指向不存在表）
#           SKIPPED（清除 DATAX_HOME 跑同一 success 夹具）
# 依赖：DATAX_HOME 指向有效 DataX 安装；DW_API/DW_TOKEN + dw CLI + capture.sh。
set -euo pipefail

HARNESS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN_REPO="/home/wallfacers/project/data-weave"
SCRIPTS="$HARNESS/scripts"
JOBS="$HARNESS/jobs"
DW="$MAIN_REPO/cli/dw"
EVID="$MAIN_REPO/specs/061-task-type-verification/evidence"

# Worker classpath：dw run 需要 worker fat jar
export DW_WORKER_CP="${DW_WORKER_CP:-$MAIN_REPO/backend/dataweave-worker/target/dataweave-worker-0.7.0-SNAPSHOT-exec.jar}"

export DW_API="${DW_API:-http://localhost:8000}"
export DATAX_HOME="${DATAX_HOME:-$HOME/engines/datax}"
ENGINE_VER="datax_v202309"
PROJECT_ID=1  # demo 项目

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[verify-datax]${NC} $*"; }
warn()  { echo -e "${YELLOW}[verify-datax]${NC} $*"; }
err()   { echo -e "${RED}[verify-datax]${NC} $*"; }

# ---- 前置检查 ----
if [ -z "${DW_TOKEN:-}" ]; then
  err "DW_TOKEN 未设置，请先 export DW_TOKEN=<token>"
  exit 2
fi

info "DATAX_HOME=$DATAX_HOME"
if [ ! -f "$DATAX_HOME/bin/datax.py" ]; then
  err "datax.py 不存在：$DATAX_HOME/bin/datax.py（请先运行 clients/install-datax.sh）"
  exit 2
fi
if [ ! -x "$DATAX_HOME/bin/datax.py" ]; then
  warn "datax.py 不可执行，尝试 chmod +x"
  chmod +x "$DATAX_HOME/bin/datax.py"
fi

info "引擎版本: $ENGINE_VER"

# ---- 工作目录 ----
WORKDIR="/tmp/dw-verify-datax-$$"
rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"
cd "$WORKDIR"

# ---- 1. Pull 项目 ----
info "pull 项目 #$PROJECT_ID ..."
"$DW" pull "$PROJECT_ID" --dir . 2>&1

# ---- 2. 创建 success + fail 任务 ----
info "创建任务夹具..."

# success 任务
cp "$JOBS/datax.success.json" datax-success.json
cat > datax-success.task.yaml << 'YAML'
formatVersion: 1
name: DataX 成功-流式读写
type: DATAX
script: datax-success.json
timeoutSec: 300
YAML

# fail 任务（读取不存在的表）
cp "$JOBS/datax.fail.json" datax-fail.json
cat > datax-fail.task.yaml << 'YAML'
formatVersion: 1
name: DataX 失败-源表不存在
type: DATAX
script: datax-fail.json
timeoutSec: 300
YAML

# ---- 3. 更新 state.json + Push ----
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
"$DW" push --dir . 2>&1 || warn "push 可能失败（基线过期等），继续..."

# ---- 4. SUCCESS: dw run ----
info "========== DATAX SUCCESS (dw run) =========="
SUCCESS_LOG="$WORKDIR/success.log"
set +e
"$DW" run datax-success.task.yaml --dir . > "$SUCCESS_LOG" 2>&1
SUCCESS_EXIT=$?
set -e
info "退出码: $SUCCESS_EXIT"

# 断言
if grep -q "completed successfully" "$SUCCESS_LOG" && [ "$SUCCESS_EXIT" -eq 0 ]; then
  info "SUCCESS 断言通过：DataX 真跑成功，原生统计日志透出"
else
  err "SUCCESS 断言失败！退出码=$SUCCESS_EXIT"
  tail -20 "$SUCCESS_LOG"
fi

# 检查原生统计
grep -E "读出记录总数|读写失败总数|任务总计耗时" "$SUCCESS_LOG" || warn "未找到 DataX 原生统计行"

# ---- 5. FAILURE: dw run ----
info "========== DATAX FAILURE (dw run) =========="
FAIL_LOG="$WORKDIR/fail.log"
set +e
"$DW" run datax-fail.task.yaml --dir . > "$FAIL_LOG" 2>&1
FAIL_EXIT=$?
set -e
info "退出码: $FAIL_EXIT"

if [ "$FAIL_EXIT" -ne 0 ]; then
  info "FAILURE 断言通过：真实作业错误，退出码=$FAIL_EXIT"
  grep -Ei "exception|error|不存在|failed|doesn't exist" "$FAIL_LOG" || warn "未找到引擎原生错误行"
else
  err "FAILURE 断言失败！预期非零退出码，实际 $FAIL_EXIT"
fi

# ---- 6. SKIPPED 对照：清 DATAX_HOME ----
info "========== DATAX SKIPPED (unset DATAX_HOME) =========="
SKIP_LOG="$WORKDIR/skipped.log"
set +e
DATAX_HOME_SAVED="$DATAX_HOME"
unset DATAX_HOME
"$DW" run datax-success.task.yaml --dir . > "$SKIP_LOG" 2>&1
SKIP_EXIT=$?
export DATAX_HOME="$DATAX_HOME_SAVED"
set -e
info "退出码: $SKIP_EXIT"

if grep -qi "已跳过\|SKIPPED\|跳过" "$SKIP_LOG"; then
  info "SKIPPED 断言通过：缺引擎优雅跳过，不阻塞"
else
  warn "SKIPPED 断言可能需要检查：未找到'已跳过'关键字"
fi

# ---- 7. dw run --test（服务端）----
info "========== DATAX --test (server) =========="
SERVER_LOG="$WORKDIR/server-test.log"
set +e
SERVER_OUT=$("$DW" run --test datax-success.task.yaml --dir . 2>&1)
SERVER_EXIT=$?
echo "$SERVER_OUT" > "$SERVER_LOG"
set -e

INSTANCE_ID=$(echo "$SERVER_OUT" | grep -oP '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1)
if [ -n "$INSTANCE_ID" ]; then
  info "服务端实例: $INSTANCE_ID"
  sleep 3  # 给服务端几秒执行时间
  "$DW" logs cat "$INSTANCE_ID" > "$WORKDIR/server-full.log" 2>&1 || true
  if grep -qi "已跳过\|SKIPPED\|success\|SUCCESS" "$WORKDIR/server-full.log"; then
    info "服务端日志已捕获"
  fi
else
  warn "无法解析服务端实例 ID"
fi

# ---- 8. 取证 ----
info "========== 证据采集 =========="
mkdir -p "$EVID/DATAX"

cp "$SUCCESS_LOG" "$EVID/DATAX/success.log"
cp "$FAIL_LOG"    "$EVID/DATAX/fail.log"
cp "$SKIP_LOG"    "$EVID/DATAX/skipped.log"

# 更新 ledger（使用主仓库的 capture.sh 确保证据落盘到正确位置）
CAPTURE="$MAIN_REPO/verification/061-task-types/scripts/capture.sh"
bash "$CAPTURE" DATAX success "$SUCCESS_EXIT" "$SUCCESS_LOG" "$ENGINE_VER" "dw run" 2>&1 || true
bash "$CAPTURE" DATAX fail    "$FAIL_EXIT"    "$FAIL_LOG"    "$ENGINE_VER" "dw run" 2>&1 || true
bash "$CAPTURE" DATAX skipped "$SKIP_EXIT"    "$SKIP_LOG"    "$ENGINE_VER" "dw run" 2>&1 || true

info "证据已保存: $EVID/DATAX/"
info "SUCCESS log: $(wc -l < "$SUCCESS_LOG") 行"
info "FAIL log:    $(wc -l < "$FAIL_LOG") 行"
info "SKIP log:    $(wc -l < "$SKIP_LOG") 行"

# ---- 9. 摘要 ----
echo ""
echo "============================================"
echo "  DataX 验证摘要"
echo "============================================"
echo "  引擎版本: $ENGINE_VER"
echo "  DATAX_HOME: $DATAX_HOME"
echo "  SUCCESS:  $( [ "$SUCCESS_EXIT" -eq 0 ] && echo '✅ PASS' || echo '❌ FAIL' ) (exit=$SUCCESS_EXIT)"
echo "  FAILURE:  $( [ "$FAIL_EXIT" -ne 0 ] && echo '✅ PASS' || echo '❌ FAIL' ) (exit=$FAIL_EXIT)"
echo "  SKIPPED:  $( grep -qi '已跳过\|SKIPPED' "$SKIP_LOG" && echo '✅ PASS' || echo '⚠️  CHECK' )"
echo "  证据目录: $EVID/DATAX/"
echo "============================================"

# 清理（可选）
# rm -rf "$WORKDIR"
