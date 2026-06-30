# 设计: Distributed 模式端到端验证 + 测试补齐

**日期**: 2026-06-30
**状态**: 已通过 brainstorming 评审
**范围**: 028-distributed-validation — 用 docker-compose 起完整 distributed 集群，端到端验证 021-027 全功能，补齐集成测试

---

## 1. 背景与动机

021-027 全部功能在 `all-in-one`/H2 下测试通过，但 `distributed` 模式（多 master + 多 worker + PG + Redis + MinIO + neo4j）从未端到端真跑过。代码探索发现两个 distributed-only bug 已有修复（`FleetService` null→default 容量、`SchedulerKernel.assign()` NodeLoad 不变量反转），但修复本身从未在真实集群中验证。

本特性把 distributed 栈从"代码上应该能跑"升级为"真实验证能跑"，补齐关键路径自动化集成测试。

## 2. 目标与非目标

**本期目标**:
- 从 main 构建镜像，`docker compose --profile distributed up -d` 起完整集群
- 端到端验证脚本：建任务 → 下发 → 执行 → 回报 → 血缘/告警/事件全链路通
- 发现并修复 distributed-only bug
- 补齐关键路径集成测试（SlotManager/FleetService/SchedulerKernel）

**非目标 / 未来工作**:
- 性能/压测
- 新增功能（纯验证+修复）
- CI/CD 集成（测试太重，仅本地手动跑）

## 3. 验证场景矩阵

| 优先级 | 场景 | 断言 |
|--------|------|------|
| **P0** | 调度闭环 | worker 注册→master 认领→分配到有槽 worker→HTTP 下发→worker 执行→回报→CAS→SUCCESS |
| **P1** | 多 master 对等 | master-1 宕机时 master-2 继续认领调度，cron guard 防重 |
| **P1** | 多 worker 分发 | 两 worker 在线，任务按槽位负载分散，不集中到一个 |
| **P2** | 血缘 neo4j | SQL 任务跑完后 `:TaskRun-[:SYNCED]->:Table` + 列级 `:DERIVES_FROM` |
| **P2** | 告警引擎 | POLL 规则→真指标值→触发→EMAIL 分发（GreenMail 捕获） |
| **P2** | 事件中心 | 质量断言失败→HealthEvent 持久化→`GET /api/events` 可查 |
| **P3** | MinIO 日志归档 | 任务结束后日志归档到 `s3://logs/{biz_date}/{instance_id}/...` |
| **P3** | Redis EventBus/LogBus | 跨 master 唤醒 + 日志流经 Redis Stream |

## 4. 测试补齐清单

| 测试 | 类型 | 依赖 | 覆盖 |
|------|------|------|------|
| `SlotManagerTest` | 纯 JUnit | 无 | NULL 容量→0 槽回归防护 |
| `FleetServiceTest` | 纯 JUnit + Mockito | 无 | 新节点注册默认容量 (maxConcurrentTasks=10, reservedTestSlots=1) |
| `SchedulerKernelDistributedIT` | `@SpringBootTest` + PG | PG | 多 worker 心跳→任务分配不集中在一个 worker |
| `SlotManagerDistributedIT` | `@SpringBootTest` + PG | PG | 真实 PG 表 task_instance 的 usedCounts 聚合正确 |

## 5. 实现阶段

### Phase 1 — 起集群
- 从 main 构建 `dataweave-api` 和 `dataweave-worker` Docker 镜像
- `docker compose --profile distributed up -d`
- 等所有容器 healthy
- 验证基础连通性：`/api/health` + neo4j bolt + Redis PING + MinIO

### Phase 2 — 补齐单测
- `SlotManagerTest`：NULL 容量→0 槽回归防护
- `FleetServiceTest`：新节点注册默认容量值
- 纯 JUnit，不依赖 Docker

### Phase 3 — 端到端验证脚本
- `scripts/distributed-e2e-verify.sh`
- 经 master-1 API 建项目/任务/workflow → 触发运行 → 轮询状态
- 断言 neo4j 血缘 + 告警事件 + 事件中心记录

### Phase 4 — 集成测试
- `SchedulerKernelDistributedIT`：真实 PG + 多 worker 心跳→任务分散分配
- `SlotManagerDistributedIT`：真实 PG usedCounts 聚合

### Phase 5 — 修复
- 验证中发现的 distributed-only bug → 当场修 → 单测覆盖

### Phase 6 — 收口
- 更新 `docs/closure-026-029-handoff.md`
- 补踩坑记录

## 6. 成功标准

- SC-001: `docker compose --profile distributed up -d` 全部容器 healthy
- SC-002: 一个 SQL 任务经 distributed 栈完整闭环（下发→执行→回报→SUCCESS）
- SC-003: 多 worker 场景下任务分散分配（不集中在一个 worker）
- SC-004: neo4j 血缘 + 告警 + 事件中心全部真实验证通过
- SC-005: 新增 4 个测试全部绿
- SC-006: 既有 364 master tests 零回归

## 7. 已知风险

- **Docker 镜像构建**：`dataweave-api` 和 `dataweave-worker` Dockerfile 可能需要适配（当前 dev-install 不产 fat jar，distributed 需要）
- **WSL2 网络**：容器间通信 `worker-1:8100` 需确认 Docker DNS 解析正常
- **MinIO 首次启动**：需创建 bucket（`dataweave-logs`）
- **schema 初始化**：master-1 负责 `SPRING_SQL_INIT_MODE=always`，需确认 0.4.0 schema 正确初始化
