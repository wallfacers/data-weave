---

description: "Task list for 044-cron-concurrency-perf"
---

# Tasks: Cron 并发触发链路验证与性能基准

**Input**: Design documents from `specs/044-cron-concurrency-perf/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api.md, quickstart.md

**Tests**: 无独立 test 任务 —— 本 feature 本身即测试/验证工具,任务就是观测与核对。

**Organization**: 按 User Story 组织(US1 正确性 P1 → US2 量化 P2 → US3 瓶颈 P3),Foundational 阶段先解锁 cron 真触发(此前链路零触发)。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行(不同文件,无前置依赖)
- **[Story]**: 所属 user story
- 描述含确切文件路径

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 确认测试环境就绪

- [X] T001 确认 distributed 后端就绪:`curl localhost:8000/api/health` 与 `:8200/api/health` 均 200;`curl localhost:8000/api/fleet` 看到 worker-1/2 ONLINE(任务才会真跑完)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 修复 cron 表达式约定 + 加速扫描,让 cron 自动触发链路首次跑通

**⚠️ CRITICAL**: 此前 cron 自动触发为零(脚本误用 5 字段),本阶段未完成则所有 US 无从验证

- [X] T002 [P] 改 `tmp/cron-stress/cron-stress.sh`:cron 默认 `*/1 * * * *` → `*/10 * * * * *`(Spring 6 字段),保留 `-c` 覆盖
- [X] T003 [P] 改 `docker-compose.yml`:`dataweave-master` 与 `dataweave-master-2` 的 `environment` 各追加 `SCHEDULER_CRON_SCAN_INTERVAL_MS: "2000"`、`SCHEDULER_CRON_LOOKAHEAD_MS: "3000"`(只追加,不覆盖既有 env)
- [X] T004 重建 master:`docker compose --profile distributed up -d dataweave-master dataweave-master-2`;验证 cron 真触发 —— 建 3 个 wf,确认 `trigger_type=CRON` 实例出现、后端日志无 `非法 cron` WARN(依赖 T002、T003)

**Checkpoint**: cron 自动触发链路从零到通,可进入 US 验证

---

## Phase 3: User Story 1 - 正确性 (Priority: P1) 🎯 MVP

**Goal**: 大批量同 cron 到期下,定时触发成功率 ≥99%、多 master 去重无重复

**Independent Test**: `setup -n 50` + `cron-watch`,核对 `trigger_type=CRON` 实例数 ≈ 50 × 触发点

### Implementation for User Story 1

- [X] T005 [US1] `tmp/cron-stress/cron-stress.sh` 新增 `cron-watch` 子命令:按窗口轮询输出 `trigger_type=CRON` 实例增量、`cron_fire` 行数(`docker exec dataweave-postgres psql ...`)、master-1/2 触发分布、state 分布
- [X] T006 [US1] 跑 `setup -n 50 -c '*/10 * * * * *'` + `cron-watch -m 1`,核对 CRON 实例数 ≈ 50 × 触发点数(定时触发成功率 ≥99%,SC-001)(依赖 T005)
- [X] T007 [US1] 核对去重:`workflow_instance` 中 (workflow_id, scheduled_fire_time) 唯一、`cron_fire` 行数 = 触发点数、无双实例(SC-002)(依赖 T006)

**Checkpoint**: US1 独立可验证 —— cron 并发触发正确性确立

---

## Phase 4: User Story 2 - 量化 (Priority: P2)

**Goal**: 给出每触发点实例创建吞吐 + 到点→实例延迟基线,可复现

**Independent Test**: 10/50/100 三种规模跑,看吞吐与延迟稳定性

### Implementation for User Story 2

- [X] T008 [US2] `tmp/cron-stress/cron-stress.sh` 扩展 `cron-watch`:每触发点实例创建吞吐(实例/s)+ `scheduled_fire_time → started_at` 延迟(平均 / p99)(依赖 T005 的 cron-watch)
- [X] T009 [US2] 多规模(10 / 50 / 100)各跑 + cron-watch,核对重复测量吞吐/延迟波动 ≤20%(SC-003)(依赖 T008)

**Checkpoint**: US2 独立可验证 —— 性能基线建立

---

## Phase 5: User Story 3 - 瓶颈定位 (Priority: P3)

**Goal**: 指出并发触发链路最先饱和环节 + 定量证据

**Independent Test**: 高压并发到期下,看 metrics 哪个环节先到顶

### Implementation for User Story 3

- [X] T010 [P] [US3] `tmp/cron-stress/cron-stress.sh` 的 `metrics` 增强:聚合 `scheduler_*` / `dispatch_*` / `hikaricp_*`,标注最先饱和项(利用率/等待最高)
- [X] T011 [P] [US3] 双 master 触发分布对比:`DW_BASE=http://localhost:8000` 与 `:8200` 各跑 metrics,算 `cron_fire` 撞键放弃数(master-1 dispatch + master-2 dispatch − 唯一触发点数)

**Checkpoint**: US3 独立可验证 —— 瓶颈定位有定量证据

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 清理与结论沉淀

- [X] T012 [P] `tmp/cron-stress/cron-stress.sh` teardown 清理本次 wf/task;`cron_fire`/`workflow_instance` 膨胀时手动清理或调短 `cron-fire-retention-days`(SC-005)
- [X] T013 把基线数字(吞吐 / 延迟 / 撞键率 / 瓶颈环节)与结论回写 `specs/044-cron-concurrency-perf/research.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖
- **Foundational (Phase 2)**: 依赖 Setup;**阻塞所有 US**(cron 链路未跑通则无从验证)
- **US1 (Phase 3)**: 依赖 Foundational
- **US2 (Phase 4)**: 依赖 Foundational;扩展基于 US1 的 cron-watch
- **US3 (Phase 5)**: 依赖 Foundational;与 US1/US2 并行无冲突(只读 metrics)
- **Polish (Phase 6)**: 依赖 US 完成

### 关键任务依赖

- T004 依赖 T002 + T003(改完脚本与 env 才能验证)
- T006 依赖 T005(cron-watch 先建再用)
- T007 依赖 T006
- T008 依赖 T005(扩展 cron-watch)
- T009 依赖 T008

### Parallel Opportunities

- T002 ‖ T003(不同文件:脚本 ‖ docker-compose)
- T010 ‖ T011(都只读 metrics,不同观测维度)
- T012 ‖ T013(清理 ‖ 文档回写)

---

## Implementation Strategy

### MVP First (Setup + Foundational + US1)

1. Phase 1:T001 确认环境
2. Phase 2:T002 改 cron 表达式 + T003 改 env + T004 重建并验证 cron 真触发
3. Phase 3:T005 cron-watch + T006 正确性核对 + T007 去重核对
4. **STOP and VALIDATE**:cron 并发触发正确性确立(MVP 交付)

### Incremental Delivery

5. US2:量化吞吐/延迟基线
6. US3:定位瓶颈(为阶段二全链路优化指路)
7. Polish:清理 + 结论沉淀进 research.md

---

## Notes

- 后端 Java 代码零改动;仅改 `tmp/cron-stress/cron-stress.sh`(git-ignored)+ `docker-compose.yml`(env 覆盖)
- 阶段二(入口 /run、执行端、调度内核优化)不在本 feature;US3 的瓶颈结论为其指路
- 每个 Checkpoint 可独立停下验证;commit 节奏:Foundational 后 / 每个 US 后
