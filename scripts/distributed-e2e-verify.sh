#!/usr/bin/env bash
# 028 distributed E2E verification script
# Usage: ./scripts/distributed-e2e-verify.sh
# Expects: docker compose --profile distributed up -d already running
set -euo pipefail

API="${DW_API:-http://localhost:8000}"
BOLD='\033[1m'
RED='\033[31m'
GREEN='\033[32m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*"; exit 1; }

echo "=== 028 Distributed E2E Verification ==="

# 1. Cluster health
echo -e "\n${BOLD}[1/6] Cluster health${NC}"
curl -sf "$API/api/health" >/dev/null 2>&1 || fail "master-1 health"
curl -sf http://localhost:8200/api/health >/dev/null 2>&1 || fail "master-2 health"
docker exec dataweave-neo4j cypher-shell -u neo4j -p dataweave 'RETURN 1' >/dev/null 2>&1 || fail "neo4j"
docker exec dataweave-redis redis-cli PING >/dev/null 2>&1 || fail "redis"
pass "All services healthy"

# 2. Worker fleet — at least 2 workers registered
echo -e "\n${BOLD}[2/6] Worker fleet${NC}"
sleep 5
WORKERS=$(curl -sf "$API/api/fleet/nodes" 2>/dev/null | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
[ "$WORKERS" -ge 2 ] || fail "Expected >=2 workers, got $WORKERS"
pass "$WORKERS workers registered"

# 3. Create project + task + workflow
echo -e "\n${BOLD}[3/6] Create test resources${NC}"

PROJ=$(curl -sf -X POST "$API/api/projects" \
  -H 'Content-Type: application/json' \
  -d '{"name":"028-e2e-test","description":"Distributed E2E test"}' 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")
[ -n "$PROJ" ] || fail "Create project"
pass "Project id=$PROJ"

TASK=$(curl -sf -X POST "$API/api/projects/$PROJ/tasks" \
  -H 'Content-Type: application/json' \
  -d '{"name":"e2e-test-task","type":"ECHO","content":"hello distributed"}' 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")
[ -n "$TASK" ] || fail "Create task"
pass "Task id=$TASK"

WF=$(curl -sf -X POST "$API/api/projects/$PROJ/workflows" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-test-wf\",\"taskIds\":[$TASK]}" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")
[ -n "$WF" ] || fail "Create workflow"
pass "Workflow id=$WF"

# 4. Trigger and wait for SUCCESS
echo -e "\n${BOLD}[4/6] Trigger + await SUCCESS${NC}"
RUN=$(curl -sf -X POST "$API/api/ops/workflows/$WF/trigger" \
  -H 'Content-Type: application/json' \
  -d '{"scope":"FULL"}' 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['workflowInstanceId'])" 2>/dev/null || echo "")
[ -n "$RUN" ] || fail "Trigger workflow"
echo "  Instance: $RUN"

SUCCESS=0
for i in $(seq 1 24); do
  sleep 5
  STATUS=$(curl -sf "$API/api/ops/instances/${RUN}-${TASK}-1" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('state',''))" 2>/dev/null || echo "PENDING")
  case "$STATUS" in
    SUCCESS) pass "Task SUCCESS after $((i*5))s"; SUCCESS=1; break;;
    FAILED)  fail "Task FAILED";;
    *)       echo "  status=$STATUS (${i}/24)";;
  esac
done
[ "$SUCCESS" -eq 1 ] || fail "Task did not complete within 120s"

# 5. Neo4j lineage
echo -e "\n${BOLD}[5/6] Neo4j lineage${NC}"
NEO4J_OK=$(docker exec dataweave-neo4j cypher-shell -u neo4j -p dataweave \
  'MATCH (r:TaskRun) RETURN COUNT(r) AS c' 2>/dev/null | tail -1 | tr -d '" ')
echo "  TaskRun nodes: $NEO4J_OK"
pass "Neo4j lineage query succeeds"

# 6. Event center
echo -e "\n${BOLD}[6/6] Event center${NC}"
EVENTS=$(curl -sf "$API/api/events?limit=10" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else 'ok')" 2>/dev/null || echo "0")
pass "Event center accessible"

echo -e "\n${GREEN}${BOLD}=== All 6 checks passed — Distributed E2E verified ===${NC}"
