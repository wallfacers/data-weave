# Feature Specification: Distributed 模式端到端真实验证

**Feature Branch**: `028-distributed-validation`

**Created**: 2026-06-30

**Status**: Draft

**Input**: "Distributed 模式端到端真实验证——起 full distributed 集群，验证调度/执行/血缘/告警/事件全链路，补齐 SlotManager/FleetService/SchedulerKernel 关键路径集成测试"

> **范围边界**：021-027 全部功能在 all-in-one/H2 下测试通过，但 distributed 模式（多 master + 多 worker + PG + Redis + MinIO + neo4j）从未端到端真跑过。代码探索发现两个 distributed-only bug 已有修复但修复本身从未在真实集群中验证。本特性将 distributed 栈从「代码上应该能跑」升级为「真实验证能跑」，补齐关键路径自动化集成测试。不新增功能——纯验证+修复+测试补齐。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 起完整 distributed 集群并通过 E2E 脚本验证全链路 (Priority: P1)

运维执行 `docker compose --profile distributed up -d`，全部容器（2 master + 2 worker + PG + Redis + MinIO + neo4j + MySQL）健康启动。运行端到端验证脚本，确认一个 SQL 任务经 distributed 栈完整闭环：worker 注册 → master 认领 → 分配到有槽 worker → HTTP 下发 → worker 执行 → 回报 → CAS 推进 → SUCCESS。脚本同时验证 neo4j 血缘落地 + 告警引擎触发 + 事件中心可查。

**Why this priority**: 核心价值——证明 distributed 栈不是"代码上应该能跑"而是"真实验证能跑"。没有它，distributed 模式等同于未交付。

**Independent Test**: `docker compose --profile distributed up -d` → 全部 healthy → `./scripts/distributed-e2e-verify.sh` → 6 项检查全部通过 → 0 退出码。

**Acceptance Scenarios**:

1. **Given** 干净的 docker 环境 + 构建好的 master/worker 镜像, **When** `docker compose --profile distributed up -d`, **Then** 9 个容器在 120s 内全部 healthy（含 neo4j healthcheck 最长等待）。
2. **Given** 集群 healthy, **When** 经 master-1 API 创建一个 SQL 任务并触发工作流运行, **Then** 任务被下发到某个 worker 执行，状态 CAS 从 WAITING→DISPATCHED→RUNNING→SUCCESS，60s 内完成。
3. **Given** SQL 任务执行成功, **When** 查询 neo4j, **Then** `:TaskRun-[:SYNCED]->:Table` 存在，syncSummary 返回真实行数。
4. **Given** 集群 healthy, **When** 查询事件中心, **Then** 返回事件列表（非 error）。

---

### User Story 2 - 多 worker 任务分散分配 (Priority: P1)

两个 worker 都在线且有空槽时，连续创建多个任务，任务应分散到两个 worker 上执行，而非全部集中在同一 worker。验证调度内核的 NodeLoad 贪心分配在 distributed 模式下正确工作。

**Why this priority**: 多 worker 分散分配是 distributed 模式区别于 all-in-one 的核心价值——负载分散。如果任务只去一个 worker，distributed 等于白部署。

**Independent Test**: 注册 2 个 worker（各有 5 槽），创建 4 个 WAITING 任务，断言至少 1 个任务分配到 worker-2。

**Acceptance Scenarios**:

1. **Given** worker-1 (5 槽) + worker-2 (5 槽) 都在线且无运行任务, **When** 连续提交 4 个任务, **Then** 两个 worker 至少各有 1 个任务被分配（不集中在一个 worker）。
2. **Given** worker-1 槽满（used=capacity）, **When** 新任务进入 WAITING, **Then** 任务分配到 worker-2（唯一有空槽的节点）。

---

### User Story 3 - 关键路径回归防护（自动化测试补齐） (Priority: P2)

补齐 distributed 关键路径的自动化测试，防止未来改动破坏已修复的 distributed-only bug。覆盖：SlotManager NULL 容量安全降级、FleetService 新 worker 注册默认容量、SchedulerKernel 多 worker 分散分配、SlotManager usedCounts PG 聚合。

**Why this priority**: 回归防护保证 distributed 模式长期可靠。P2 因为不影响当前功能，但缺少它意味着 distributed bug 可能在未来悄无声息地回归。

**Independent Test**: `./mvnw -pl dataweave-master test -Dtest="SlotManagerTest,FleetServiceTest,SchedulerKernelDistributedIT,SlotManagerDistributedIT"` → 全部绿。

**Acceptance Scenarios**:

1. **Given** WorkerNode 的 maxConcurrentTasks=null, **When** SlotManager.availableForNormal() 计算, **Then** 该节点 capacity=0、free=0（不 NPE，不误判为有槽）。
2. **Given** 新 worker 首次心跳（repo 返回 empty）, **When** FleetService.report() 处理, **Then** 返回的 WorkerNode 自动获得 maxConcurrentTasks=10 和 reservedTestSlots=1。
3. **Given** task_instance 中 w1 有 3 条 DISPATCHED/RUNNING、w2 有 1 条, **When** SlotManager.snapshotOnline(), **Then** w1.used=3, w2.used=1。

---

### Edge Cases

- worker 心跳超时（30s 无心跳）→ master 标记 OFFLINE，slot 不再可用
- worker incarnation 变化（重启）→ master 检测到变化，该 worker 的 DISPATCHED/RUNNING 实例标记 FAILED
- 所有 worker 槽满 → 新任务保持 WAITING，等待槽释放（轮询重试）
- worker 执行中宕机 → master 心跳超时判离线 + 租约过期，任务回 WAITING 重分派
- neo4j 首次初始化（schema init）→ 异步完成不阻塞 master 启动（已有修复）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持 `docker compose --profile distributed up -d` 一键启动完整 distributed 集群（PG + Redis + MinIO + neo4j + MySQL + 2 master + 2 worker）。
- **FR-002**: 集群启动后，master-1 MUST 完成 schema 初始化（`SPRING_SQL_INIT_MODE=always`），master-2 MUST NOT 重复初始化（`never`）。
- **FR-003**: worker 注册 MUST 在首次心跳时自动补默认容量值（maxConcurrentTasks=10, reservedTestSlots=1），避免 INSERT NULL 导致 SlotManager 误判 0 槽。
- **FR-004**: SchedulerKernel 的 NodeLoad 计算 MUST 在 distributed 模式下正确反映各 worker 的空闲槽数，空闲 worker 不因计算反转被排除。
- **FR-005**: E2E 验证脚本 MUST 覆盖：集群健康、worker 注册（≥2）、建项目/任务/工作流、触发运行、等待 SUCCESS、neo4j 血缘落地、事件中心可访问。
- **FR-006**: SlotManager MUST 在 worker_nodes 的 maxConcurrentTasks 或 reservedTestSlots 为 NULL 时安全降级为 0，不抛 NPE。
- **FR-007**: 既有 364+ master 单元测试 MUST 零回归（distributed 改动不影响 all-in-one 行为）。

### Key Entities *(include if feature involves data)*

- **WorkerNode**: 集群 worker 节点注册表（已存在）。关键字段：nodeCode, host, maxConcurrentTasks, reservedTestSlots, status, incarnation。
- **NodeLoad**: 调度内核的节点负载视图（已存在）。capacity, used, free 由 SlotManager 实时派生。
- **TaskInstance**: 任务实例（已存在）。关键字段：state (DISPATCHED/RUNNING), worker_node_code（记录被分配到哪个 worker）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `docker compose --profile distributed up -d` 全部 9 个容器在 120s 内 healthy。
- **SC-002**: 一个 SQL 任务经 distributed 栈完整闭环（WAITING→DISPATCHED→RUNNING→SUCCESS）在 60s 内完成。
- **SC-003**: E2E 验证脚本全部 6 项检查通过（0 退出码）。
- **SC-004**: 新增 4 个测试类全部绿（≥7 个测试用例，0 failures）。
- **SC-005**: 既有 master 测试零回归（≥364 tests, 0 failures）。
- **SC-006**: 多 worker 场景下任务分散分配（不集中在一个 worker）。

## Assumptions

- Docker 环境正常（daemon 可达，WSL2 集成已开，docker compose 可用）。
- 镜像从当前分支源码构建（不依赖外部 registry）。
- Dockerfile（dataweave-api/Dockerfile, dataweave-worker/Dockerfile）已存在且可用，或需小修。
- E2E 验证脚本仅本地手动跑，不进入 CI/CD（太重，需完整 Docker 环境）。
- 不新增功能——纯验证与测试补齐。发现的 distributed-only bug 当场修。
