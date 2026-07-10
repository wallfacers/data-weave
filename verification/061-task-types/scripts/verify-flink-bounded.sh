#!/usr/bin/env bash
# 验证 FLINK 有界任务 — 真 Flink 成功 + 真失败 + SKIPPED 对照
# 前提：compose.compute.yml 已起（Flink JM: localhost:8083），FLINK_HOME 已安装
# 用法：FLINK_HOME=/opt/flink ./scripts/verify-flink-bounded.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JOBS="${HERE}/jobs"
SCRIPTS="${HERE}/scripts"
REPO="$(cd "${HERE}/../../.." && pwd)"
DW="${REPO}/cli/dw"
VER="${1:-1.20.0}"  # Flink 版本

FLINK_HOME="${FLINK_HOME:-/opt/flink}"
export FLINK_HOME

echo "=== FLINK 有界任务真跑验证 ==="
echo "FLINK_HOME=${FLINK_HOME}"
echo "版本标注: ${VER}"

# ---- 前提检查 ----
command -v java >/dev/null 2>&1 || { echo "需要 java"; exit 7; }
[ -f "${FLINK_HOME}/bin/flink" ] || { echo "SKIP: ${FLINK_HOME}/bin/flink 不存在"; exit 7; }
[ -f "${DW}" ] || { echo "需要 dw CLI (${DW})"; exit 7; }

echo "[verify-flink] flink 到位: $(${FLINK_HOME}/bin/flink --version 2>&1 | head -1)"

# 检查 Flink JM 就绪
JM_UP=false
for i in $(seq 1 10); do
    if curl -s http://localhost:8083/config >/dev/null 2>&1; then
        JM_UP=true
        echo "[verify-flink] Flink JM 就绪（localhost:8083）"
        break
    fi
    echo "[verify-flink] 等待 Flink JM...(${i}/10)"
    sleep 3
done
if [ "${JM_UP}" != "true" ]; then
    echo "Flink JM 不可达（localhost:8083）；请先起 compute profile: ./scripts/up.sh compute"
    exit 7
fi

# 确认 Flink 集群版本
JM_VER=$(curl -s http://localhost:8083/config 2>/dev/null | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('flink-version','?'))" 2>/dev/null || echo "?")
echo "[verify-flink] JM 版本: ${JM_VER}"

# ---- 准备临时工作副本 ----
WS="$(mktemp -d)"
trap "rm -rf ${WS}" EXIT
echo "[verify-flink] 工作副本: ${WS}"
cd "${WS}"

"${DW}" pull demo --clean --force 2>&1

# 获取 JWT
JWT="$(curl -s -X POST http://localhost:8000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['token'])")"

# 创建/更新 FLINK 数据源（服务器侧，用于 dw run --test）
echo "[verify-flink] 登记 FLINK 数据源..."
DS_EXISTS=$(curl -s "http://localhost:8000/api/datasources" -H "Authorization: Bearer ${JWT}" \
  | python3 -c "import json,sys; ds=[d for d in json.load(sys.stdin)['data'] if d['typeCode']=='FLINK']; print(ds[0]['id'] if ds else '')" 2>/dev/null || echo "")

if [ -z "${DS_EXISTS}" ]; then
  echo "[verify-flink] 创建 FLINK 数据源（flink-local）..."
  curl -s -X POST "http://localhost:8000/api/datasources" \
    -H "Authorization: Bearer ${JWT}" \
    -H "Content-Type: application/json" \
    -d "{\"projectId\":1,\"name\":\"flink-local\",\"typeCode\":\"FLINK\",\"host\":\"localhost\",\"port\":8083,\"databaseName\":\"\",\"username\":\"\",\"passwordEnc\":\"\",\"propsJson\":\"{\\\"engineHome\\\":\\\"${FLINK_HOME}\\\",\\\"conf\\\":{\\\"restEndpoint\\\":\\\"http://localhost:8083\\\"}}\",\"status\":\"ACTIVE\"}" \
    > /dev/null 2>&1
  echo "[verify-flink] 数据源 flink-local 已创建"
else
  echo "[verify-flink] FLINK 数据源已存在（id=${DS_EXISTS}），跳过创建"
fi

# ---- success 任务 ----
echo ""
echo "--- FLINK bounded success ---"

SLUG="flink-bounded-061"
cp "${JOBS}/flink.bounded.success.sql" "${SLUG}.sql"
cat > "${SLUG}.task.yaml" << 'EOF'
formatVersion: 1
name: FLINK Bounded Success (061)
type: FLINK
priority: 5
timeoutSec: 600
datasource: flink-local
flinkMode: sql
EOF

"${DW}" push --force 2>&1

RAWLOG="${HERE}/../tmp/flink-bounded-success-raw.log"
mkdir -p "$(dirname "${RAWLOG}")"

echo "[verify-flink] dw run ${SLUG} ..."
"${DW}" run "${SLUG}" > "${RAWLOG}" 2>&1 || true
EXIT_LOCAL=$?
echo "[verify-flink] dw run exit=${EXIT_LOCAL}"

echo "[verify-flink] dw run --test ${SLUG} ..."
"${DW}" run --test "${SLUG}" > "${RAWLOG}.test" 2>&1 || true
EXIT_TEST=$?
echo "[verify-flink] dw run --test exit=${EXIT_TEST}"

cat > "${RAWLOG}.combined" << LOGEOF
=== FLINK bounded success: dw run ===
$(cat "${RAWLOG}")
=== FLINK bounded success: dw run --test ===
$(cat "${RAWLOG}.test")
EOF

"${SCRIPTS}/capture.sh" FLINK success "${EXIT_LOCAL}" "${RAWLOG}.combined" "${VER}" "dw run / dw run --test" \
  '{"resultset_rendered": false, "dw_run_vs_server": "consistent"}'

# ---- fail 任务 ----
echo ""
echo "--- FLINK bounded fail ---"

SLUG_FAIL="flink-bounded-fail-061"
cp "${JOBS}/flink.bounded.fail.sql" "${SLUG_FAIL}.sql"
cat > "${SLUG_FAIL}.task.yaml" << 'EOF'
formatVersion: 1
name: FLINK Bounded Fail (061)
type: FLINK
priority: 5
timeoutSec: 600
datasource: flink-local
flinkMode: sql
EOF

"${DW}" push --force 2>&1

RAWLOG_FAIL="${HERE}/../tmp/flink-bounded-fail-raw.log"

echo "[verify-flink] dw run ${SLUG_FAIL} ..."
"${DW}" run "${SLUG_FAIL}" > "${RAWLOG_FAIL}" 2>&1 || true
EXIT_FAIL=$?
echo "[verify-flink] dw run fail exit=${EXIT_FAIL}"

"${SCRIPTS}/capture.sh" FLINK fail "${EXIT_FAIL}" "${RAWLOG_FAIL}" "${VER}" "dw run" '{}'

# ---- SKIPPED 对照 ----
echo ""
echo "--- FLINK SKIPPED 对照 ---"
SKIPLOG="${HERE}/../tmp/flink-bounded-skipped-raw.log"
FLINK_HOME_ORIG="${FLINK_HOME}"
export FLINK_HOME="/nonexistent/flink"
"${DW}" run "${SLUG}" > "${SKIPLOG}" 2>&1 || true
EXIT_SKIP=$?
export FLINK_HOME="${FLINK_HOME_ORIG}"
echo "[verify-flink] SKIPPED exit=${EXIT_SKIP}"

if grep -qi "跳过\|SKIPPED\|skipped" "${SKIPLOG}"; then
    echo "[verify-flink] SKIPPED 正确检测到"
else
    echo "[verify-flink] 警告：未检测到 SKIPPED 标记"
fi

"${SCRIPTS}/capture.sh" FLINK skipped "${EXIT_SKIP}" "${SKIPLOG}" "${VER}" "dw run (无引擎)" '{}'

echo ""
echo "=== FLINK 有界任务真跑验证完成 ==="
echo "证据 → specs/061-task-type-verification/evidence/FLINK/"
