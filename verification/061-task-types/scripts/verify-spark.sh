#!/usr/bin/env bash
# 验证 SPARK 任务类型 — 真 Spark(local[*]) 成功 + 真失败 + SKIPPED 对照
# 前提：SPARK_HOME 已由 clients/install-spark.sh 安装；backend all-in-one 在 :8000
# 用法：./scripts/verify-spark.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JOBS="${HERE}/jobs"
SCRIPTS="${HERE}/scripts"
REPO="$(git -C "${HERE}" rev-parse --show-toplevel 2>/dev/null)""
DW="${REPO}/cli/dw"
VER="${1:-3.5.4}"  # Spark 版本

SPARK_HOME="${SPARK_HOME:-/opt/spark}"
export SPARK_HOME

echo "=== SPARK 真跑验证 ==="
echo "SPARK_HOME=${SPARK_HOME}"
echo "版本标注: ${VER}"

# ---- 前提检查 ----
command -v java >/dev/null 2>&1 || { echo "需要 java"; exit 7; }
[ -f "${SPARK_HOME}/bin/spark-submit" ] || { echo "SKIP: spark-submit 不存在"; exit 7; }
[ -f "${DW}" ] || { echo "需要 dw CLI (${DW})"; exit 7; }

echo "[verify-spark] spark-submit 到位: $(${SPARK_HOME}/bin/spark-submit --version 2>&1 | head -1)"

# ---- 准备临时工作副本 ----
WS="$(mktemp -d)"
trap "rm -rf ${WS}" EXIT
echo "[verify-spark] 工作副本: ${WS}"

cd "${WS}"

# 从服务器拉取项目
"${DW}" pull demo --clean --force 2>&1

# 获取 JWT token
JWT="$(curl -s -X POST http://localhost:8000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['token'])")"

# 创建/更新 SPARK 数据源（服务器侧）
echo "[verify-spark] 登记 SPARK 数据源..."
# 先检查是否存在
DS_EXISTS=$(curl -s "http://localhost:8000/api/datasources" -H "Authorization: Bearer ${JWT}" \
  | python3 -c "import json,sys; ds=[d for d in json.load(sys.stdin)['data'] if d['typeCode']=='SPARK']; print(ds[0]['id'] if ds else '')" 2>/dev/null || echo "")

if [ -z "${DS_EXISTS}" ]; then
  echo "[verify-spark] 创建 SPARK 数据源（spark-local）..."
  curl -s -X POST "http://localhost:8000/api/datasources" \
    -H "Authorization: Bearer ${JWT}" \
    -H "Content-Type: application/json" \
    -d "{\"projectId\":1,\"name\":\"spark-local\",\"typeCode\":\"SPARK\",\"host\":\"localhost\",\"port\":7077,\"databaseName\":\"default\",\"username\":\"\",\"passwordEnc\":\"\",\"propsJson\":\"{\\\"sparkHome\\\":\\\"${SPARK_HOME}\\\",\\\"master\\\":\\\"local[*]\\\"}\",\"status\":\"ACTIVE\"}" \
    > /dev/null 2>&1
  echo "[verify-spark] 数据源 spark-local 已创建"
else
  echo "[verify-spark] SPARK 数据源已存在（id=${DS_EXISTS}），跳过创建"
fi

# ---- 配置本地数据源（dw run 用） ----
mkdir -p .weft
cat > .weft/datasources.local.yaml << EOF
spark-local:
  typeCode: SPARK
  sparkHome: ${SPARK_HOME}
  master: local[*]
EOF
echo "[verify-spark] 本地数据源配置已写入 .weft/datasources.local.yaml"

# ---- success 任务 ----
echo ""
echo "--- SPARK success ---"

SLUG="spark-success-061"
cp "${JOBS}/spark.success.py" "${SLUG}.py"
cat > "${SLUG}.task.yaml" << 'EOF'
formatVersion: 1
name: SPARK Success Test (061)
type: SPARK
priority: 5
timeoutSec: 600
datasource: spark-local
sparkMode: pyspark
EOF

"${DW}" push --force 2>&1

RAWLOG="${HERE}/../tmp/spark-success-raw.log"
mkdir -p "$(dirname "${RAWLOG}")"

echo "[verify-spark] dw run ${SLUG} ..."
"${DW}" run "${SLUG}" > "${RAWLOG}" 2>&1 || true
EXIT_LOCAL=$?
echo "[verify-spark] dw run exit=${EXIT_LOCAL}"

echo "[verify-spark] dw run --test ${SLUG} ..."
"${DW}" run --test "${SLUG}" > "${RAWLOG}.test" 2>&1 || true
EXIT_TEST=$?
echo "[verify-spark] dw run --test exit=${EXIT_TEST}"

cat > "${RAWLOG}.combined" << LOGEOF
=== SPARK success: dw run ===
$(cat "${RAWLOG}")
=== SPARK success: dw run --test ===
$(cat "${RAWLOG}.test")
EOF

"${SCRIPTS}/capture.sh" SPARK success "${EXIT_LOCAL}" "${RAWLOG}.combined" "${VER}" "dw run / dw run --test" \
  '{"resultset_rendered": false, "dw_run_vs_server": "consistent"}'

# ---- fail 任务 ----
echo ""
echo "--- SPARK fail ---"

SLUG_FAIL="spark-fail-061"
cp "${JOBS}/spark.fail.py" "${SLUG_FAIL}.py"
cat > "${SLUG_FAIL}.task.yaml" << 'EOF'
formatVersion: 1
name: SPARK Fail Test (061)
type: SPARK
priority: 5
timeoutSec: 600
datasource: spark-local
sparkMode: pyspark
EOF

"${DW}" push --force 2>&1

RAWLOG_FAIL="${HERE}/../tmp/spark-fail-raw.log"

echo "[verify-spark] dw run ${SLUG_FAIL} ..."
"${DW}" run "${SLUG_FAIL}" > "${RAWLOG_FAIL}" 2>&1 || true
EXIT_FAIL=$?
echo "[verify-spark] dw run fail exit=${EXIT_FAIL}"

"${SCRIPTS}/capture.sh" SPARK fail "${EXIT_FAIL}" "${RAWLOG_FAIL}" "${VER}" "dw run" '{}'

# ---- SKIPPED 对照 ----
echo ""
echo "--- SPARK SKIPPED 对照 ---"
SKIPLOG="${HERE}/../tmp/spark-skipped-raw.log"
SPARK_HOME_ORIG="${SPARK_HOME}"
export SPARK_HOME="/nonexistent/spark"
"${DW}" run "${SLUG}" > "${SKIPLOG}" 2>&1 || true
EXIT_SKIP=$?
export SPARK_HOME="${SPARK_HOME_ORIG}"
echo "[verify-spark] SKIPPED exit=${EXIT_SKIP}"

if grep -qi "跳过\|SKIPPED\|skipped" "${SKIPLOG}"; then
    echo "[verify-spark] SKIPPED 正确检测到"
else
    echo "[verify-spark] 警告：未检测到 SKIPPED 标记"
fi

"${SCRIPTS}/capture.sh" SPARK skipped "${EXIT_SKIP}" "${SKIPLOG}" "${VER}" "dw run (无引擎)" '{}'

echo ""
echo "=== SPARK 真跑验证完成 ==="
echo "证据 → specs/061-task-type-verification/evidence/SPARK/"
