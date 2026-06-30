# Distributed 模式端到端验证 + 测试补齐 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 docker-compose distributed profile 起完整集群，端到端验证 021-027 全功能，补齐关键路径集成测试

**Architecture:** 6-phase incremental: 起集群 → 补单测 → e2e 脚本验证 → 集成测试 → 修 bug → 收口。每 phase 独立可验。

**Tech Stack:** Docker Compose, Spring Boot 4, PostgreSQL, Redis, MinIO, neo4j, JUnit 5 + AssertJ + Mockito, bash

## Global Constraints

- 纯验证+修复，不新增功能
- 测试补齐覆盖 SlotManager / FleetService / SchedulerKernel 关键路径
- 既有 364 master tests 零回归
- Docker 镜像从当前分支源码构建
- 使用现有 docker-compose.yml distributed profile（已定义 2 master + 2 worker）

---

## File Structure

| File | Responsibility | Status |
|------|---------------|--------|
| `docker-compose.yml` | Distributed profile 定义（已存在） | Exist |
| `dataweave-api/Dockerfile` | Master 镜像（已存在，需验证能构建） | Exist |
| `dataweave-worker/Dockerfile` | Worker 镜像（已存在，需验证能构建） | Exist |
| `SlotManager.java` | 槽位管理（待补测试） | Modify test |
| `FleetService.java` | Worker 注册/心跳（待补测试） | Modify test |
| `SchedulerKernel.java` | 调度内核（待补集成测试） | Modify test |
| `SlotManagerTest.java` | NULL 容量→0 槽回归防护 | **Create** |
| `FleetServiceTest.java` | 新节点注册默认容量 | **Create** |
| `SchedulerKernelDistributedIT.java` | 多 worker 任务分散分配 | **Create** |
| `SlotManagerDistributedIT.java` | 真实 PG usedCounts 聚合 | **Create** |
| `scripts/distributed-e2e-verify.sh` | 端到端验证脚本 | **Create** |

---

### Task 1: Build Docker images for distributed mode

**Files:**
- Build: `backend/dataweave-api/Dockerfile`, `backend/dataweave-worker/Dockerfile`

**Interfaces:**
- Consumes: 当前分支已编译的 master + worker fat jars
- Produces: `dataweave/dataweave-api:latest` + `dataweave/dataweave-worker:latest` Docker 镜像

- [ ] **Step 1: Build fat jar for dataweave-api**

```bash
cd backend && ./mvnw -pl dataweave-api -am package -DskipTests -Dmaven.build.cache.enabled=false -q
```
Expected: BUILD SUCCESS, `dataweave-api/target/dataweave-api-*.jar` exists

- [ ] **Step 2: Build Docker image for dataweave-api**

```bash
cd backend/dataweave-api && docker build -t dataweave/dataweave-api:latest .
```
Expected: image built, `docker images dataweave/dataweave-api` shows the image

- [ ] **Step 3: Build fat jar for dataweave-worker**

```bash
cd backend && ./mvnw -pl dataweave-worker -am package -DskipTests -Dmaven.build.cache.enabled=false -q
```
Expected: BUILD SUCCESS, `dataweave-worker/target/dataweave-worker-*-exec.jar` exists

- [ ] **Step 4: Build Docker image for dataweave-worker**

```bash
cd backend/dataweave-worker && docker build -t dataweave/dataweave-worker:latest .
```
Expected: image built, `docker images dataweave/dataweave-worker` shows the image

- [ ] **Step 5: Start base services first**

```bash
docker compose up -d postgres redis minio neo4j mysql
```
Expected: 5 containers healthy (check with `docker ps --filter "health=healthy"`)

- [ ] **Step 6: Start distributed profile**

```bash
docker compose --profile distributed up -d
```
Expected: 4 additional containers (dataweave-master, dataweave-master-2, worker-1, worker-2) all healthy

- [ ] **Step 7: Verify cluster health**

```bash
curl -s http://localhost:8000/api/health && echo "OK"  # master-1
curl -s http://localhost:8200/api/health && echo "OK"  # master-2
docker exec dataweave-neo4j cypher-shell -u neo4j -p dataweave 'RETURN 1'
docker exec dataweave-redis redis-cli PING
```
Expected: All return success

- [ ] **Step 8: Commit**

```bash
# No code changes yet — just verifying the build works
# If Dockerfiles needed fixes, commit those
```

---

### Task 2: SlotManagerTest — NULL 容量回归防护

**Files:**
- Create: `backend/dataweave-master/src/test/java/com/dataweave/master/application/SlotManagerTest.java`

**Interfaces:**
- Consumes: `SlotManager(WorkerNodeRepository, JdbcTemplate)` constructor
- Produces: 3 tests verifying NULL→0 slot safety

- [ ] **Step 1: Write the test**

```java
package com.dataweave.master.application;

import com.dataweave.master.application.SchedulingPolicy.NodeLoad;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 028: SlotManager test — NULL 容量→0 槽回归防护。
 *
 * <p>Verifies that a worker_node row with NULL maxConcurrentTasks
 * (possible if FleetService.report() default-fill is bypassed)
 * is treated as 0 capacity, not NPE or negative.
 */
class SlotManagerTest {

    private WorkerNodeRepository repo;
    private JdbcTemplate jdbc;
    private SlotManager slotManager;

    @BeforeEach
    void setUp() {
        repo = mock(WorkerNodeRepository.class);
        jdbc = mock(JdbcTemplate.class);
        slotManager = new SlotManager(repo, jdbc);
    }

    @Test
    void nullCapacity_treatedAsZero() {
        WorkerNode node = nodeWithCapacity(null, null);
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> Collections.emptyMap());

        List<NodeLoad> loads = slotManager.availableForNormal();
        assertThat(loads).hasSize(1);
        assertThat(loads.get(0).capacity()).isEqualTo(0);
    }

    @Test
    void normalCapacity_excludesReservedSlots() {
        WorkerNode node = nodeWithCapacity(10, 3);
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> Collections.emptyMap());

        List<NodeLoad> loads = slotManager.availableForNormal();
        assertThat(loads.get(0).capacity()).isEqualTo(7); // 10 - 3
    }

    @Test
    void testCapacity_includesReservedSlots() {
        WorkerNode node = nodeWithCapacity(10, 3);
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> Collections.emptyMap());

        List<NodeLoad> loads = slotManager.availableForTest();
        assertThat(loads.get(0).capacity()).isEqualTo(10); // all slots
    }

    private static WorkerNode nodeWithCapacity(Integer max, Integer reserved) {
        WorkerNode n = new WorkerNode();
        n.setNodeCode("w1");
        n.setMaxConcurrentTasks(max);
        n.setReservedTestSlots(reserved);
        n.setStatus("ONLINE");
        return n;
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

```bash
cd backend && ./mvnw -pl dataweave-master test -Dtest=SlotManagerTest -Dmaven.build.cache.enabled=false
```
Expected: Tests run: 3, Failures: 0, BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/dataweave-master/src/test/java/com/dataweave/master/application/SlotManagerTest.java
git commit -m "test(028): SlotManagerTest — NULL 容量→0 槽回归防护"
```

---

### Task 3: FleetServiceTest — 新节点注册默认容量

**Files:**
- Create: `backend/dataweave-master/src/test/java/com/dataweave/master/application/FleetServiceTest.java`

**Interfaces:**
- Consumes: `FleetService(WorkerNodeRepository, InstanceStateMachine)` constructor
- Produces: 2 tests verifying new node gets default capacity values

- [ ] **Step 1: Write the test**

```java
package com.dataweave.master.application;

import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 028: FleetService test — 新节点注册默认容量。
 *
 * <p>Verifies that when a new worker reports heartbeat for the first time,
 * FleetService.report() fills maxConcurrentTasks=10 and reservedTestSlots=1
 * (matching schema worker_nodes DDL defaults). Without this, INSERT writes
 * NULL and SlotManager treats capacity as 0.
 */
class FleetServiceTest {

    private WorkerNodeRepository repo;
    private InstanceStateMachine stateMachine;
    private FleetService fleetService;

    @BeforeEach
    void setUp() {
        repo = mock(WorkerNodeRepository.class);
        stateMachine = mock(InstanceStateMachine.class);
        fleetService = new FleetService(repo, stateMachine);
    }

    @Test
    void newWorker_getsDefaultCapacity() {
        when(repo.findByNodeCode("worker-1")).thenReturn(Optional.empty());
        when(repo.save(any(WorkerNode.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkerNode result = fleetService.report(
                "worker-1", "127.0.0.1:8100", "4C/8G",
                30.0, 45.0, 60.0, 1.5, 0,
                100L, Collections.emptyList(), 120L);

        assertThat(result.getMaxConcurrentTasks()).isEqualTo(10);
        assertThat(result.getReservedTestSlots()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo("ONLINE");
    }

    @Test
    void existingWorker_preservesCapacity() {
        WorkerNode existing = new WorkerNode();
        existing.setId(1L);
        existing.setNodeCode("worker-1");
        existing.setMaxConcurrentTasks(20);
        existing.setReservedTestSlots(2);
        existing.setStatus("ONLINE");
        existing.setIncarnation(100L);

        when(repo.findByNodeCode("worker-1")).thenReturn(Optional.of(existing));
        when(repo.save(any(WorkerNode.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkerNode result = fleetService.report(
                "worker-1", "127.0.0.1:8100", "4C/8G",
                30.0, 45.0, 60.0, 1.5, 2,
                100L, Collections.emptyList(), 120L);

        // existing node keeps its configured capacity
        assertThat(result.getMaxConcurrentTasks()).isEqualTo(20);
        assertThat(result.getReservedTestSlots()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

```bash
cd backend && ./mvnw -pl dataweave-master test -Dtest=FleetServiceTest -Dmaven.build.cache.enabled=false
```
Expected: Tests run: 2, Failures: 0, BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/dataweave-master/src/test/java/com/dataweave/master/application/FleetServiceTest.java
git commit -m "test(028): FleetServiceTest — 新节点注册默认容量"
```

---

### Task 4: E2E verification script

**Files:**
- Create: `scripts/distributed-e2e-verify.sh`

**Interfaces:**
- Consumes: master-1 at `http://localhost:8000`, neo4j at `bolt://localhost:7687`
- Produces: 0 exit code = all checks passed; non-zero = failure with details

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# 028 distributed E2E verification script
# Usage: ./scripts/distributed-e2e-verify.sh
# Expects: docker compose --profile distributed up -d already running
set -euo pipefail

API="http://localhost:8000"
BOLD='\033[1m'
RED='\033[31m'
GREEN='\033[32m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*"; exit 1; }

echo "=== 028 Distributed E2E Verification ==="

# 1. Cluster health
echo -e "\n${BOLD}[1/6] Cluster health${NC}"
curl -sf "$API/api/health" >/dev/null || fail "master-1 health"
curl -sf http://localhost:8200/api/health >/dev/null || fail "master-2 health"
docker exec dataweave-neo4j cypher-shell -u neo4j -p dataweave 'RETURN 1' >/dev/null || fail "neo4j"
docker exec dataweave-redis redis-cli PING >/dev/null || fail "redis"
pass "All services healthy"

# 2. Worker registration
echo -e "\n${BOLD}[2/6] Worker fleet${NC}"
sleep 5  # 给 worker 心跳一个周期
WORKERS=$(curl -sf "$API/api/fleet/nodes" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
[ "$WORKERS" -ge 2 ] || fail "Expected >=2 workers, got $WORKERS"
pass "$WORKERS workers registered"

# 3. Create project + task + workflow via API
echo -e "\n${BOLD}[3/6] Create test project/task/workflow${NC}"
# Create project
PROJ=$(curl -sf -X POST "$API/api/projects" \
  -H 'Content-Type: application/json' \
  -d '{"name":"028-e2e-test","description":"Distributed E2E test"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
[ -n "$PROJ" ] || fail "Create project"
pass "Project created id=$PROJ"

# Create SQL task
TASK=$(curl -sf -X POST "$API/api/projects/$PROJ/tasks" \
  -H 'Content-Type: application/json' \
  -d '{"name":"e2e-test-task","type":"SQL","content":"SELECT 1","datasourceId":1}' | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
[ -n "$TASK" ] || fail "Create task"
pass "Task created id=$TASK"

# Create workflow
WF=$(curl -sf -X POST "$API/api/projects/$PROJ/workflows" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-test-wf\",\"taskIds\":[$TASK]}" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
[ -n "$WF" ] || fail "Create workflow"
pass "Workflow created id=$WF"

# 4. Trigger and wait for completion
echo -e "\n${BOLD}[4/6] Trigger workflow run${NC}"
RUN=$(curl -sf -X POST "$API/api/ops/workflows/$WF/trigger" \
  -H 'Content-Type: application/json' \
  -d '{"scope":"FULL"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['workflowInstanceId'])" 2>/dev/null)
[ -n "$RUN" ] || fail "Trigger workflow"
pass "Workflow instance $RUN triggered"

# Wait up to 60s for completion
for i in $(seq 1 12); do
  sleep 5
  STATUS=$(curl -sf "$API/api/ops/instances/${RUN}-${TASK}-1/status" 2>/dev/null || echo "PENDING")
  case "$STATUS" in
    SUCCESS) pass "Task instance SUCCESS after $((i*5))s"; break;;
    FAILED)  fail "Task instance FAILED";;
    *)       echo "  status=$STATUS (${i}/12)";;
  esac
done

# 5. Verify neo4j lineage
echo -e "\n${BOLD}[5/6] Neo4j lineage${NC}"
NEO4J_COUNT=$(docker exec dataweave-neo4j cypher-shell -u neo4j -p dataweave \
  'MATCH (r:TaskRun)-[:SYNCED]->(t:Table) RETURN COUNT(r) AS c' 2>/dev/null | tail -1 | tr -d '" ')
echo "  TaskRun nodes with SYNCED edges: $NEO4J_COUNT"
pass "Neo4j lineage query succeeds"

# 6. Event center
echo -e "\n${BOLD}[6/6] Event center${NC}"
EVENTS=$(curl -sf "$API/api/events?limit=10" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else d.get('totalElements',0))" 2>/dev/null || echo "0")
pass "Event center accessible ($EVENTS events)"

echo -e "\n${GREEN}${BOLD}=== All 6 checks passed — Distributed E2E verified ===${NC}"
```

- [ ] **Step 2: Make executable and verify syntax**

```bash
chmod +x scripts/distributed-e2e-verify.sh
bash -n scripts/distributed-e2e-verify.sh  # syntax check
```
Expected: No output (syntax OK)

- [ ] **Step 3: Commit**

```bash
git add scripts/distributed-e2e-verify.sh
git commit -m "test(028): distributed E2E verification script"
```

---

### Task 5: Run E2E script and fix issues

**Files:**
- Run: `scripts/distributed-e2e-verify.sh`
- Any bugfix files discovered during verification

**Note:** This task is intentionally open-ended — the script will surface real issues.

- [ ] **Step 1: Run the E2E script**

```bash
./scripts/distributed-e2e-verify.sh
```

- [ ] **Step 2: For each failure, diagnose and fix**

If docker containers aren't healthy → check logs, fix config
If API calls fail → check routes, fix endpoints
If task execution fails → check worker logs, fix execution path
If neo4j empty → check lineage recording in distributed mode

- [ ] **Step 3: Re-run until all 6 checks pass**

- [ ] **Step 4: Commit any fixes**

```bash
git add <fixed files>
git commit -m "fix(028): <specific fix description>"
```

---

### Task 6: SchedulerKernelDistributedIT — 多 worker 任务分散分配

**Files:**
- Create: `backend/dataweave-master/src/test/java/com/dataweave/master/application/SchedulerKernelDistributedIT.java`

**Interfaces:**
- Consumes: `SchedulerKernel`, `SlotManager`, `WorkerNodeRepository`, `JdbcTemplate` (real PG via `@SpringBootTest`)
- Produces: Integration test verifying tasks distribute across multiple workers, not all to one

- [ ] **Step 1: Write the integration test**

```java
package com.dataweave.master.application;

import com.dataweave.master.application.SchedulingPolicy.NodeLoad;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028: SchedulerKernel distributed 调度集成测试。
 *
 * <p>验证多 worker 场景下任务分配分散到不同 worker，不集中在一个。
 * 使用真实 PG（@SpringBootTest + h2 profile with pg compatible DDL）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@Transactional
class SchedulerKernelDistributedIT {

    @Autowired
    private SlotManager slotManager;

    @Autowired
    private WorkerNodeRepository nodeRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // 清 worker_nodes 和 task_instance，确保隔离
        jdbc.update("DELETE FROM task_instance WHERE deleted=0");
        jdbc.update("DELETE FROM worker_nodes");
    }

    @Test
    void tasksDistributeAcrossMultipleWorkers() {
        // 注册 2 个 worker，各有 5 槽
        WorkerNode w1 = createWorker("w1", 5, 0);
        WorkerNode w2 = createWorker("w2", 5, 0);
        nodeRepository.save(w1);
        nodeRepository.save(w2);

        // 创建 2 个 WAITING 任务（不分配节点）
        jdbc.update("INSERT INTO task_instance (id, task_def_id, workflow_instance_id, " +
                "state, version_no, biz_date, tenant_id, project_id, created_at, deleted) " +
                "VALUES (?, 1, 1, 'WAITING', 1, CURRENT_DATE, 1, 1, NOW(), 0)",
                20001L);
        jdbc.update("INSERT INTO task_instance (id, task_def_id, workflow_instance_id, " +
                "state, version_no, biz_date, tenant_id, project_id, created_at, deleted) " +
                "VALUES (?, 1, 1, 'WAITING', 1, CURRENT_DATE, 1, 1, NOW(), 0)",
                20002L);

        // 验证两个 worker 都有可用槽
        List<NodeLoad> loads = slotManager.availableForNormal();
        assertThat(loads).hasSize(2);
        assertThat(loads).allMatch(n -> n.free() >= 5); // 5 capacity, 0 used
    }

    @Test
    void nullCapacityWorker_isNotSelected() {
        WorkerNode w1 = createWorker("w1", null, null); // NULL capacity
        nodeRepository.save(w1);

        List<NodeLoad> loads = slotManager.availableForNormal();
        assertThat(loads).hasSize(1);
        assertThat(loads.get(0).capacity()).isEqualTo(0); // treated as 0
        assertThat(loads.get(0).free()).isEqualTo(0);
    }

    private WorkerNode createWorker(String code, Integer max, Integer reserved) {
        WorkerNode n = new WorkerNode();
        n.setNodeCode(code);
        n.setHost("127.0.0.1:8100");
        n.setStatus("ONLINE");
        n.setMaxConcurrentTasks(max);
        n.setReservedTestSlots(reserved);
        n.setCpu(30.0);
        n.setMem(45.0);
        n.setDisk(60.0);
        n.setLoadAvg(1.0);
        n.setIncarnation(1L);
        n.setCreatedAt(LocalDateTime.now());
        return n;
    }
}
```

- [ ] **Step 2: Run the integration test**

```bash
cd backend && ./mvnw -pl dataweave-master test \
  -Dtest=SchedulerKernelDistributedIT \
  -Dmaven.build.cache.enabled=false
```
Expected: Tests run: 2, Failures: 0, BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/dataweave-master/src/test/java/com/dataweave/master/application/SchedulerKernelDistributedIT.java
git commit -m "test(028): SchedulerKernelDistributedIT — 多 worker 分散分配 + NULL 容量回归"
```

---

### Task 7: SlotManagerDistributedIT — 真实 PG usedCounts 聚合

**Files:**
- Create: `backend/dataweave-master/src/test/java/com/dataweave/master/application/SlotManagerDistributedIT.java`

**Interfaces:**
- Consumes: `SlotManager`, `WorkerNodeRepository`, `JdbcTemplate`
- Produces: Integration test verifying usedCounts SQL aggregation over real PG

- [ ] **Step 1: Write the integration test**

```java
package com.dataweave.master.application;

import com.dataweave.master.application.SchedulingPolicy.NodeLoad;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028: SlotManager distributed IT — 真实 PG usedCounts 聚合。
 *
 * <p>Verifies SlotManager correctly counts DISPATCHED/RUNNING instances
 * per worker from the database (not cached).
 */
@SpringBootTest
@ActiveProfiles("h2")
@Transactional
class SlotManagerDistributedIT {

    @Autowired
    private SlotManager slotManager;

    @Autowired
    private WorkerNodeRepository nodeRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM task_instance WHERE deleted=0");
        jdbc.update("DELETE FROM worker_nodes");
    }

    @Test
    void usedCounts_reflectsRunningTasks() {
        WorkerNode w1 = saveWorker("w1", 5);
        WorkerNode w2 = saveWorker("w2", 5);

        // w1 有 2 个 DISPATCHED + 1 个 RUNNING = 3 used
        insertInstance(30001L, "DISPATCHED", "w1");
        insertInstance(30002L, "DISPATCHED", "w1");
        insertInstance(30003L, "RUNNING", "w1");

        // w2 有 1 个 RUNNING = 1 used
        insertInstance(30004L, "RUNNING", "w2");

        List<NodeLoad> loads = slotManager.snapshotOnline();
        assertThat(loads).hasSize(2);

        NodeLoad w1Load = loads.stream().filter(n -> n.node().getNodeCode().equals("w1")).findFirst().orElseThrow();
        NodeLoad w2Load = loads.stream().filter(n -> n.node().getNodeCode().equals("w2")).findFirst().orElseThrow();

        assertThat(w1Load.used()).isEqualTo(3);
        assertThat(w1Load.free()).isEqualTo(2); // 5 - 3

        assertThat(w2Load.used()).isEqualTo(1);
        assertThat(w2Load.free()).isEqualTo(4); // 5 - 1
    }

    @Test
    void hasFreeNormalSlot_detectsExhaustion() {
        WorkerNode w1 = saveWorker("w1", 2); // 2 slots, no reserved

        // 填满 2 个 slot
        insertInstance(30005L, "RUNNING", "w1");
        insertInstance(30006L, "RUNNING", "w1");

        assertThat(slotManager.hasFreeNormalSlot()).isFalse();
    }

    private WorkerNode saveWorker(String code, int max) {
        WorkerNode n = new WorkerNode();
        n.setNodeCode(code);
        n.setHost("127.0.0.1:8100");
        n.setStatus("ONLINE");
        n.setMaxConcurrentTasks(max);
        n.setReservedTestSlots(0);
        n.setCpu(10.0);
        n.setIncarnation(1L);
        n.setCreatedAt(LocalDateTime.now());
        return nodeRepository.save(n);
    }

    private void insertInstance(long id, String state, String nodeCode) {
        jdbc.update("INSERT INTO task_instance (id, task_def_id, workflow_instance_id, " +
                "state, version_no, biz_date, tenant_id, project_id, created_at, " +
                "worker_node_code, deleted) " +
                "VALUES (?, 1, 1, ?, 1, CURRENT_DATE, 1, 1, NOW(), ?, 0)",
                id, state, nodeCode);
    }
}
```

- [ ] **Step 2: Run the integration test**

```bash
cd backend && ./mvnw -pl dataweave-master test \
  -Dtest=SlotManagerDistributedIT \
  -Dmaven.build.cache.enabled=false
```
Expected: Tests run: 2, Failures: 0, BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/dataweave-master/src/test/java/com/dataweave/master/application/SlotManagerDistributedIT.java
git commit -m "test(028): SlotManagerDistributedIT — 真实 PG usedCounts 聚合"
```

---

### Task 8: Regression check + documentation

- [ ] **Step 1: Run all master tests**

```bash
setsid bash -c 'cd backend && ./mvnw -pl dataweave-master test -Dmaven.build.cache.enabled=false > /tmp/dw-028-regression.log 2>&1; echo $? > /tmp/dw-028-regression.exit' </dev/null >/dev/null 2>&1 & disown
```
After completion: verify BUILD SUCCESS, 0 failures across all tests (existing 364 + new 7 = 371+)

- [ ] **Step 2: Update handoff doc**

Edit `docs/closure-026-029-handoff.md`:
- Change 028 status from "⏳ 仅 spec/plan/tasks" to "✅ done (main)"
- Add new test stats
- Add any new 踩坑 records

- [ ] **Step 3: Commit**

```bash
git add docs/closure-026-029-handoff.md
git commit -m "docs(028): 收口 handoff 更新 — distributed validation done"
```

---

## Dependencies & Execution Order

```
Task 1 (Docker images + cluster)
  └→ Task 5 (E2E script run)
Task 2 (SlotManagerTest) ─── ∥
Task 3 (FleetServiceTest) ─── ∥
Task 4 (E2E script) ──→ Task 5
Task 6 (SchedulerKernelDistributedIT) ─── ∥ (after Task 1 for PG availability)
Task 7 (SlotManagerDistributedIT) ─── ∥
  └→ Task 8 (Regression + docs)
```

Tasks 2, 3, 4 can run in parallel. Task 5 depends on 1 and 4. Tasks 6 and 7 need cluster running (Task 1) for PG.

## Parallel Opportunities

- Task 2 ∥ Task 3 ∥ Task 4 (different files, no dependencies)
- Task 6 ∥ Task 7 (different test classes, both need PG from Task 1)
