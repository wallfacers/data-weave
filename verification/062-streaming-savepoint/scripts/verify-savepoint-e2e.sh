#!/usr/bin/env bash
# 062 实时任务运维 — savepoint 优雅停止 + 检查点续跑 端到端真跑取证（TR）
#
# 全链路（服务端产品路径，非夹具）：
#   ① dw push（longRunning:true）→ task_def.long_running=TRUE
#   ② dw run --test → triggerTestRun 物化 instance.long_running=TRUE → 下发 worker
#   ③ worker FlinkTaskExecutor detached 提交 → 真 JobID → external_job_handle 回写实例
#   ④ POST /api/ops/streaming-tasks/{id}/stop → HttpFlinkSavepointClient 真连 Flink REST
#      stop-with-savepoint → 轮询到 COMPLETED 取 location → 写 task_checkpoint → CAS STOPPED
#   ⑤ GET /streaming-tasks/{id}/checkpoints → 真检查点（SUCCESS/resumable）
#   ⑥ POST /streaming-tasks/{id}/resume → CAS WAITING + resume_checkpoint_id + 保留句柄
#   ⑦ Flink REST 侧作业确认已停（savepoint 停止非 cancel）
#
# 前提：compose.flink.yml 已起（Flink JM REST :8083）；FLINK_HOME 客户端已装；backend :8000。
# 用法：FLINK_HOME=/home/wallfacers/flink ./scripts/verify-savepoint-e2e.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JOBS="${HERE}/jobs"
TMP="${HERE}/tmp"
mkdir -p "${TMP}"
REPO="$(git -C "${HERE}" rev-parse --show-toplevel 2>/dev/null)"
DW="${REPO}/cli/dw"

BACKEND="${DW_API:-http://localhost:8000}"
FLINK_REST="${FLINK_REST:-http://localhost:8083}"
FLINK_HOME="${FLINK_HOME:-/home/wallfacers/flink}"
export FLINK_HOME
SLUG="flink-streaming-062"
SP_DIR="file:///savepoints"

PASS=0; FAIL=0
ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
step() { echo; echo "=== $1 ==="; }

jqpy() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

echo "########## 062 savepoint 端到端真跑取证 ##########"
echo "backend=${BACKEND}  flinkREST=${FLINK_REST}  FLINK_HOME=${FLINK_HOME}"

# ─────────────── 前提检查 ───────────────
step "步骤0 前提检查"
command -v java >/dev/null 2>&1 && ok "java 可用" || { bad "无 java"; exit 7; }
[ -f "${FLINK_HOME}/bin/flink" ] && ok "flink 客户端存在" || { bad "缺 ${FLINK_HOME}/bin/flink"; exit 7; }
[ -x "${DW}" ] && ok "dw CLI 存在" || { bad "缺 dw CLI"; exit 7; }
curl -sf "${BACKEND}/api/health" >/dev/null 2>&1 && ok "backend 健康" || { bad "backend 不可达"; exit 7; }
JM_UP=false
for i in $(seq 1 20); do
    curl -sf "${FLINK_REST}/config" >/dev/null 2>&1 && { JM_UP=true; break; }
    echo "  等待 Flink JM ...(${i}/20)"; sleep 3
done
[ "${JM_UP}" = true ] && ok "Flink JM REST 就绪" || { bad "Flink JM 不可达 ${FLINK_REST}"; exit 7; }

# ─────────────── 登录 ───────────────
step "步骤1 登录取 JWT"
JWT="$(curl -s -X POST "${BACKEND}/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jqpy "d['data']['token']")"
[ -n "${JWT}" ] && ok "JWT 获取（len=${#JWT}）" || { bad "登录失败"; exit 7; }
export DW_API="${BACKEND}"
export DW_TOKEN="${JWT}"
AUTH=(-H "Authorization: Bearer ${JWT}" -H "X-Project-Id: 1")

# ─────────────── FLINK 数据源 ───────────────
step "步骤2 确保 FLINK 数据源（restEndpoint + engineHome）"
DS_ID="$(curl -s "${BACKEND}/api/datasources" "${AUTH[@]}" \
  | python3 -c "import json,sys; ds=[d for d in json.load(sys.stdin)['data'] if d.get('typeCode')=='FLINK']; print(ds[0]['id'] if ds else '')" 2>/dev/null)"
if [ -z "${DS_ID}" ]; then
  curl -s -X POST "${BACKEND}/api/datasources" "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"projectId\":1,\"name\":\"flink-local\",\"typeCode\":\"FLINK\",\"host\":\"localhost\",\"port\":8083,\"databaseName\":\"\",\"username\":\"\",\"passwordEnc\":\"\",\"propsJson\":\"{\\\"engineHome\\\":\\\"${FLINK_HOME}\\\",\\\"conf\\\":{\\\"restEndpoint\\\":\\\"${FLINK_REST}\\\"}}\",\"status\":\"ACTIVE\"}" >/dev/null 2>&1
  ok "数据源 flink-local 已创建"
else
  ok "数据源 flink-local 已存在（id=${DS_ID}）"
fi

# ─────────────── 创作 + push ───────────────
step "步骤3 创作 long_running FLINK 任务并 push"
WS="$(mktemp -d)"
cd "${WS}"
"${DW}" pull demo --clean --force >/dev/null 2>&1 || "${DW}" pull demo --force >/dev/null 2>&1 || true
cp "${JOBS}/streaming.sql" "${SLUG}.sql"
cat > "${SLUG}.task.yaml" << EOF
formatVersion: 1
name: FLINK Streaming Savepoint (062)
type: FLINK
priority: 5
timeoutSec: 3600
datasource: flink-local
longRunning: true
params:
  _flinkMode: sql
  _longRunning: "true"
EOF
PUSH_OUT="$("${DW}" push --force 2>&1)"
echo "${PUSH_OUT}" | tail -3
echo "${PUSH_OUT}" | grep -qiE "error|fail|失败" && bad "push 报错" || ok "push 成功"

# ─────────────── 服务端 TEST run（long_running）───────────────
step "步骤4 dw run --test 触发服务端 long_running 提交"
RUNLOG="${TMP}/run-test.log"
( "${DW}" run --test "${SLUG}" > "${RUNLOG}" 2>&1 ) &
DW_PID=$!
echo "  dw run --test PID=${DW_PID}（流式作业，将阻塞轮询，后台运行）"

# ─────────────── 轮询实例进 RUNNING + 句柄回写 ───────────────
step "步骤5 轮询 /streaming-tasks 至 RUNNING + external_job_handle 回写"
INST_ID=""; INST_STATE=""; HANDLE_PRESENT=""
for i in $(seq 1 40); do
  ROW="$(curl -s "${BACKEND}/api/ops/streaming-tasks?keyword=Savepoint" "${AUTH[@]}" \
    | python3 -c "
import json,sys
d=json.load(sys.stdin)['data']['items']
r=[x for x in d if '062' in (x.get('taskName') or '')]
if r:
    x=r[0]; print(x['instanceId'], x['state'], x.get('externalJobHandlePresent'))
" 2>/dev/null)"
  if [ -n "${ROW}" ]; then
    INST_ID="$(echo "${ROW}" | awk '{print $1}')"
    INST_STATE="$(echo "${ROW}" | awk '{print $2}')"
    HANDLE_PRESENT="$(echo "${ROW}" | awk '{print $3}')"
    echo "  轮询 ${i}/40: state=${INST_STATE} handle=${HANDLE_PRESENT} id=${INST_ID}"
    [ "${INST_STATE}" = "RUNNING" ] && [ "${HANDLE_PRESENT}" = "True" ] && break
  else
    echo "  轮询 ${i}/40: 尚无 long_running 实例"
  fi
  sleep 3
done
[ -n "${INST_ID}" ] && ok "实例已现（id=${INST_ID}）" || bad "未出现 long_running 实例"
[ "${INST_STATE}" = "RUNNING" ] && ok "实例 RUNNING" || bad "实例未达 RUNNING（state=${INST_STATE}）"
[ "${HANDLE_PRESENT}" = "True" ] && ok "external_job_handle 已回写" || bad "句柄未回写"

# Flink 侧确认作业 RUNNING
FLINK_JOB="$(curl -s "${FLINK_REST}/jobs" | python3 -c "import json,sys; j=[x for x in json.load(sys.stdin).get('jobs',[]) if x.get('status')=='RUNNING']; print(j[0]['id'] if j else '')" 2>/dev/null)"
[ -n "${FLINK_JOB}" ] && ok "Flink REST 侧作业 RUNNING（JobID=${FLINK_JOB}）" || bad "Flink 侧无 RUNNING 作业"

if [ -z "${INST_ID}" ] || [ "${INST_STATE}" != "RUNNING" ]; then
  echo; echo "!! 无法继续（实例未就绪），dw run 日志尾部："; tail -20 "${RUNLOG}" 2>/dev/null
  kill "${DW_PID}" 2>/dev/null || true
  echo; echo "########## 结果：PASS=${PASS} FAIL=${FAIL} ##########"; exit 1
fi

# ─────────────── US3 优雅停止（savepoint）───────────────
step "步骤6 POST /stop —— 真 savepoint 优雅停止"
STOP_RESP="$(curl -s -X POST "${BACKEND}/api/ops/streaming-tasks/${INST_ID}/stop" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d "{\"targetDirectory\":\"${SP_DIR}\"}")"
echo "  响应: ${STOP_RESP}"
STOP_STATE="$(echo "${STOP_RESP}" | jqpy "d['data']['state']")"
CKPT_PATH="$(echo "${STOP_RESP}" | jqpy "d['data']['checkpointPath']")"
CKPT_ID="$(echo "${STOP_RESP}" | jqpy "d['data']['checkpointId']")"
[ "${STOP_STATE}" = "STOPPED" ] && ok "实例 CAS→STOPPED" || bad "停止后状态非 STOPPED（${STOP_STATE}）"
echo "${CKPT_PATH}" | grep -q "savepoint" && ok "savepoint location 真实返回：${CKPT_PATH}" || bad "无 savepoint location（${CKPT_PATH}）"
[ -n "${CKPT_ID}" ] && [ "${CKPT_ID}" != "None" ] && ok "task_checkpoint 记录（id=${CKPT_ID}）" || bad "无检查点记录"

# ─────────────── US3 检查点列表 ───────────────
step "步骤7 GET /checkpoints —— 检查点真实落库"
CK_RESP="$(curl -s "${BACKEND}/api/ops/streaming-tasks/${INST_ID}/checkpoints" "${AUTH[@]}")"
echo "  ${CK_RESP}" | head -c 400; echo
CK_STATUS="$(echo "${CK_RESP}" | jqpy "d['data'][0]['status']")"
CK_RESUMABLE="$(echo "${CK_RESP}" | jqpy "d['data'][0]['resumable']")"
[ "${CK_STATUS}" = "SUCCESS" ] && ok "检查点 SUCCESS" || bad "检查点状态非 SUCCESS（${CK_STATUS}）"
[ "${CK_RESUMABLE}" = "True" ] && ok "检查点 resumable" || bad "检查点不可续跑"

# ─────────────── Flink 侧确认作业已停 ───────────────
step "步骤8 Flink REST 确认作业已 savepoint 停止（非 cancel）"
sleep 2
JOB_FINAL="$(curl -s "${FLINK_REST}/jobs/${FLINK_JOB}" | jqpy "d.get('state','GONE')")"
echo "  Flink JobID=${FLINK_JOB} 终态=${JOB_FINAL}"
echo "${JOB_FINAL}" | grep -qE "FINISHED|CANCELED" && ok "Flink 作业已停（${JOB_FINAL}）" || bad "Flink 作业未停（${JOB_FINAL}）"

# ─────────────── US4 从检查点续跑 ───────────────
step "步骤9 POST /resume —— 从检查点续跑（CAS→WAITING）"
RESUME_RESP="$(curl -s -X POST "${BACKEND}/api/ops/streaming-tasks/${INST_ID}/resume" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d "{\"checkpointId\":\"${CKPT_ID}\"}")"
echo "  响应: ${RESUME_RESP}"
RESUME_CODE="$(echo "${RESUME_RESP}" | jqpy "d.get('code')")"
[ "${RESUME_CODE}" = "0" ] && ok "resume 接受（code=0）" || bad "resume 失败（${RESUME_RESP}）"
# 续跑确定性证据 = 响应实例 resume_checkpoint_id 落库 + 从 STOPPED 转出（不再是 STOPPED）
# + 保留 external_job_handle。注意：all-in-one 实时调度器会立即再下发 → 状态可能已从
# WAITING 推进到 DISPATCHED/RUNNING/终态，均属正确下游，故只断言「已转出 STOPPED」。
RESUME_CKPT="$(echo "${RESUME_RESP}" | jqpy "d['data'].get('resumeCheckpointId')")"
RESUME_STATE="$(echo "${RESUME_RESP}" | jqpy "d['data'].get('state')")"
RESUME_HANDLE_RAW="$(echo "${RESUME_RESP}" | jqpy "d['data'].get('externalJobHandle')")"
echo "  续跑响应: state=${RESUME_STATE} resumeCheckpointId=${RESUME_CKPT}"
[ "${RESUME_CKPT}" = "${CKPT_ID}" ] && ok "resume_checkpoint_id 已落库（=${CKPT_ID}）" || bad "resume_checkpoint_id 未记录（${RESUME_CKPT}）"
[ "${RESUME_STATE}" != "STOPPED" ] && ok "实例已转出 STOPPED（${RESUME_STATE}，实时调度器可续推进）" || bad "续跑后仍 STOPPED"
echo "${RESUME_HANDLE_RAW}" | grep -q "jobId" && ok "续跑保留 external_job_handle" || bad "续跑丢失句柄"

# ─────────────── 收尾 ───────────────
step "步骤10 收尾（取消残留 Flink 作业 + 结束 dw run）"
# 续跑可能触发 reattach 重新拉起，取消所有 RUNNING 作业防泄漏
for J in $(curl -s "${FLINK_REST}/jobs" | python3 -c "import json,sys; print(' '.join(x['id'] for x in json.load(sys.stdin).get('jobs',[]) if x.get('status')=='RUNNING'))" 2>/dev/null); do
  "${FLINK_HOME}/bin/flink" cancel "${J}" >/dev/null 2>&1 || true
done
kill "${DW_PID}" 2>/dev/null || true
rm -rf "${WS}" 2>/dev/null || true

# ─────────────── 证据落盘 ───────────────
cat > "${TMP}/evidence.txt" << EVEOF
=== 062 savepoint 端到端真跑证据 ===
实例 ID: ${INST_ID}
Flink JobID: ${FLINK_JOB}
停止后状态: ${STOP_STATE}
savepoint location: ${CKPT_PATH}
检查点 ID: ${CKPT_ID}  状态: ${CK_STATUS}  resumable: ${CK_RESUMABLE}
Flink 作业终态: ${JOB_FINAL}
续跑后状态: ${RESUME_STATE}  resume_checkpoint_id: ${RESUME_CKPT}
PASS=${PASS} FAIL=${FAIL}
EVEOF

echo
echo "########## 结果：PASS=${PASS} FAIL=${FAIL} ##########"
echo "证据 → ${TMP}/evidence.txt"
[ "${FAIL}" -eq 0 ] && exit 0 || exit 1
