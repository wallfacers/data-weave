# Implementation Plan: 大数据开发任务类型补全（MVP）

**Branch**: `059-bigdata-task-types` | **Date**: 2026-07-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/059-bigdata-task-types/spec.md`

## Summary

把任务类型目录补齐到覆盖主流大数据开发场景。经代码勘探，现状比 spec 假设的更接近目标：

- **执行器分发是纯数据驱动、add-only**：`WorkerExecService` 与 `InProcessTaskExecutionGateway` 都用 Spring 注入的 `Map<String,TaskExecutor>` 建 `byType`（键 = `type().toUpperCase()`）。新增执行器 = 新增一个 `@Component`，**无需改任何中央注册表**。
- **OLAP 数据源类型已存在**：`data.sql` 已 seed `HIVE(7)` / `CLICKHOUSE(9)` / `STARROCKS(10)` / `DORIS(11)`（含正确 driver class），`datasource_types` 是数据表非枚举。StarRocks/Doris 复用 MySQL 驱动、ClickHouse 用 clickhouse 驱动。→ OLAP 之 SQL 路径**已可跑**，工作收敛为「验证方言 + 前端暴露 + 驱动 jar 可加载」。
- **源→汇已有列**：`task_def.datasource_id` + `target_datasource_id`，无需改 schema 即可承载 DataX/SeaTunnel 的 source→sink 绑定。
- **Spark/Python 执行器已存在**，仅前端创建入口未暴露（硬编码只有 SQL/SHELL）。

因此真正的新增代码集中在：**4 个新执行器**（HIVE / FLINK / DATAX / SEATUNNEL，全部以 `SparkTaskExecutor` 子进程范式为模板）、**1 个共享上下文扩展**（`ExecutionContext` 增一个通用 `EngineSubmitRef`）、**master 侧解析+下发接线**、**本地 runtime 接线**、**前端全类型暴露**。全程 add-only，不改调度状态机、不改 schema DDL（schema_version 不变）。

技术手段：沿用 `SparkTaskExecutor` 的「ProcessBuilder → 逐行 onLine → waitFor(timeout) → destroyForcibly → exitCode 忠实透传 / 缺环境 → `ExecutionResult.skipped()`」保真范式；Hive 走 HiveServer2 JDBC（复用驱动隔离，不依赖 beeline 二进制）。

## Technical Context

**Language/Version**: Java 25（backend）· TypeScript/React 19（frontend）· Go（dw CLI）

**Primary Dependencies**: Spring Boot 4.0 / WebFlux / Spring Data JDBC（backend）；Next.js 16 + shadcn/ui（frontend）；外部引擎二进制由运行环境提供（`SPARK_HOME`/`FLINK_HOME`/`DATAX_HOME`/`SEATUNNEL_HOME`），JDBC 驱动经既有驱动隔离上传机制

**Storage**: PostgreSQL（默认）/ H2（`profiles=h2`）；**原则上不改 DDL**——`task_def.type` 为 `VARCHAR(32)`、`datasource_types` 数据驱动、`target_datasource_id` 已存在；引擎子模式（如 `_flinkMode`）随 `params_json` / 文件契约 `params` map 承载（自动 round-trip）。**唯一例外（G1）**：若 T002a 评估确认 `task_def.content`/`task_def_version.content` 的 `VARCHAR(4000)` 不足以承载 DataX/SeaTunnel/Flink 作业体，则扩为 `TEXT`（**T002a 已裁决并落地**：DataX/SeaTunnel/Flink 真实作业体典型 5-50KB，`VARCHAR(4000)` 不足会截断/写库失败；已扩 `task_def.content`/`task_def_version.content` 为 `TEXT` + `schema_version` 0.14.0→0.14.2，H2 重启验证 DDL 执行通过）

**Testing**: JUnit 5 + AssertJ（每个执行器：命令构造纯函数 + SKIPPED 判定 + 退出码透传单测；LocalRun parity 测试）；vitest + 浏览器门（前端全类型选择器）

**Target Platform**: Linux server（all-in-one 与 distributed 两种 scheduler 模式）+ 本地 `dw run`

**Project Type**: Web（backend 多模块 + frontend + cli），四模块 DDD

**Performance Goals**: 无新增性能目标；长跑引擎（Spark/Flink/DataX）日志沿用 5000 行截断上限；执行不阻塞调度线程（既有 `WorkerExecService` 线程池）

**Constraints**: 保真不变量（本地 `dw run` 与服务端逐项相等：exitCode / stdout-stderr / 超时 / SKIPPED）；三态语义不新增状态机状态；凭据不明文入定义/日志；缺引擎 → SKIPPED 不阻塞下游；CI 零外部依赖仍可跑通 SKIPPED 闭环

**Scale/Scope**: 新增 4 执行器 + 1 共享上下文字段 + master/local/前端接线；分 3 条并行工作流（A/B/C）交付

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
|---|---|---|
| **I. Files-First** | ✅ PASS | 每种任务的执行体是纯文本 content（DataX job JSON / SeaTunnel HOCON / Flink SQL / HQL），元数据（type、引擎子模式、源汇数据源绑定）走 `TaskDoc` + `params` map。新增子模式键复用既有 `params` map，**自动 round-trip**，无字段静默丢失。 |
| **II. Server is Source of Truth** | ✅ PASS | 新类型定义经 push 发布闸门 + 版本快照；不改 pull/push 语义。round-trip 完整性由「新配置只入 `params` map / 既有 `datasource`·`targetDatasource` 字段」保证——需契约测试断言。 |
| **III. Two-Legged Debugging** | ✅ PASS（有交付项） | 新类型必须在 `LocalRunMain.selectExecutor` + `buildContext` + `LocalRunArgs` + `dw` CLI 接线，复用同一执行器子进程；parity 测试断言本地↔服务端逐项相等。此为 NON-NEGOTIABLE，列为必做任务。 |
| **IV. AI Lives in Local Agent** | ✅ PASS | 不新增任何服务端 AI；不触碰 chat/agent 面。 |
| **V. Reuse the Kernel** | ✅ PASS | 复用调度内核、`byType` 分发、SQL 执行器、驱动隔离、写闸门；4 个新执行器为纯新增，零内核重写。 |

**Gate 结论：PASS，无违规。** Complexity Tracking 无条目。

设计后复核（Phase 1 末）：见文末「Post-Design Constitution Re-Check」。

## Project Structure

### Documentation (this feature)

```text
specs/059-bigdata-task-types/
├── plan.md              # 本文件
├── research.md          # Phase 0：接入方式决策（Hive JDBC vs beeline、引擎上下文建模、方言差异）
├── data-model.md        # Phase 1：任务类型目录 + 执行上下文 + 数据源类型实体
├── quickstart.md        # Phase 1：端到端验证脚本（每类型 SKIPPED 闭环 + 有引擎真跑）
├── contracts/
│   ├── task-executor.md #   TaskExecutor 契约（type/execute/三态结果）对新类型的约束
│   └── task-types.md    #   任务类型目录 + 每类型内容形态/绑定/子模式/编辑器语言/i18n key 契约
├── checklists/
│   └── requirements.md  # 已生成（/speckit-specify）
└── tasks.md             # /speckit-tasks 生成（本命令不产出）
```

### Source Code (repository root)

```text
backend/
├── dataweave-worker/src/main/java/com/dataweave/worker/
│   ├── domain/ExecutionContext.java            # 【共享·扩展】新增通用 EngineSubmitRef 可空字段（Flink/DataX/SeaTunnel 共用）
│   ├── infrastructure/HiveTaskExecutor.java    # 【新·工作流A】type()=HIVE，HiveServer2 JDBC 复用驱动隔离
│   ├── infrastructure/FlinkTaskExecutor.java   # 【新·工作流C】type()=FLINK，flink run / sql-client 子进程
│   ├── infrastructure/DataXTaskExecutor.java   # 【新·工作流B】type()=DATAX，datax.py 子进程
│   ├── infrastructure/SeaTunnelTaskExecutor.java # 【新·工作流B】type()=SEATUNNEL，seatunnel.sh 子进程
│   └── localrun/{LocalRunMain,LocalRunArgs}.java # 【接线·各工作流】selectExecutor/buildContext 增新 case + CLI flags
├── dataweave-master/src/main/java/com/dataweave/master/
│   ├── application/DatasourceResolver.java      # 【共享·扩展】ResolvedConnection 增引擎解析（Flink 集群 / 引擎 home）
│   ├── application/SchedulerKernel.java         # 【接线】镜像 _sparkMode 提取，增 _flinkMode 等下发注入
│   ├── application/TaskExecutionGateway.java    # 【接线】DispatchCommand 增引擎字段
│   └── filecontract/dto/TaskDoc.java            # 【评估】优先走 params map；仅当需类型化字段才动
├── dataweave-api/src/main/java/com/dataweave/api/infrastructure/
│   ├── InProcessTaskExecutionGateway.java        # 【接线】all-in-one 建 ctx 注入 engine ref（镜像 buildSparkRef:203-211）
│   └── DistributedTaskExecutionGateway.java      # 【接线】distributed over-wire 序列化 engine ref（镜像 :175-179）
└── dataweave-api/src/main/resources/data.sql    # 【可选·多半不需】Flink 集群配置走绑定数据源 props_json（镜像 Spark）或 FLINK_HOME env → 一般无需新 seed 行；OLAP 行已就绪

frontend/
├── components/workspace/catalog-tree.tsx              # 【工作流C】create-task 类型选择器：SQL/SHELL → 全类型
├── components/workspace/views/workflow-canvas-view.tsx # 【工作流C】画布内建任务类型选择器同步扩展
├── components/workspace/task-config-panel.tsx          # 【工作流C】任务配置面板类型选项扩展
├── components/workspace/shared/params-table.tsx        # 【工作流C】taskTypeToLang 增 HIVE/FLINK/DATAX/SEATUNNEL 映射
└── messages/{zh-CN,en-US}.json                         # 【共享·i18n】taskType* 键补齐（两 bundle 对齐）

cli/                                                    # 【接线·工作流B/C】dw run 支持新类型 flag（--flink-mode 等）
```

**Structure Decision**: 沿用既有四模块 DDD Web 结构（backend 多模块 + frontend + cli）。新增执行器落 `dataweave-worker/infrastructure`（基础设施层实现 domain `TaskExecutor` 接口），解析落 `dataweave-master/application`，前端落 workspace 组件。**无新模块、无跨层重构**——故不触发 `superpowers:brainstorming` 前置门（CLAUDE.md「新模块/跨 DDD 层重构」才要求）。

### 3-Agent 并行工作流切分（供 /speckit-tasks 落地）

| 工作流 | 负责 US | 主要产出 | 触及共享面（需协调） |
|---|---|---|---|
| **A** — OLAP+Hive | US1 | 验证 OLAP SQL 方言真跑（ClickHouse/StarRocks/Doris 均已 seed 且四处同步点齐全 → 主要验证 + 驱动 jar 可加载）+ `HiveTaskExecutor`（HiveServer2 JDBC）+ Hive/OLAP 血缘接入 | i18n（数据源标签为 backend `name` 列，无需 key） |
| **B** — 数据集成 | US2 | `DataXTaskExecutor` + `SeaTunnelTaskExecutor` + `ExecutionContext.EngineSubmitRef`（**共享类型·先落 main**）+ `DatasourceResolver` 引擎解析 + `SchedulerKernel`/两 gateway 接线 + LocalRun 接线 | `ExecutionContext`（新字段，C 也消费）、`DatasourceResolver`、`SchedulerKernel`、两 gateway、i18n |
| **C** — Flink+入口 | US3 | `FlinkTaskExecutor`（集群配置走绑定数据源 props_json/env，多半无需 seed）+ 前端全类型选择器（catalog-tree/workflow-canvas/task-config-panel）+ `taskTypeToLang` + i18n `taskType*` 键（含缺失的 `taskTypeSpark`） | `ExecutionContext`（消费 B 的 `EngineSubmitRef`）、i18n |

**协调硬规则**（遵循 CLAUDE.md 多 Agent 协作 + SDD worktree 隔离）：
1. 各工作流各自独立 `git worktree`，禁在同一副本交错。
2. **共享类型 `ExecutionContext.EngineSubmitRef` 由工作流 B 先实现并合入 main**，C 基于其消费——契约先行，避免同文件对撞。
3. `data.sql` / i18n bundle 为 A/C 共同触及面 → 合并前对齐 key，禁互相覆盖；碰撞即停并上报，绝不静默择一。
4. `byType` 自动注册（Spring `@Component`）→ 新执行器无需改中央注册表，天然低冲突。

## Complexity Tracking

> 无 Constitution 违规，无需填写。

## Post-Design Constitution Re-Check

Phase 1 设计产出（data-model / contracts / quickstart）后复核：

- **I / II（Files-First / round-trip）**：设计选择「引擎子模式与配置优先入既有 `params` map + `datasource`/`targetDatasource` 字段」，不新增 `TaskDoc` 类型化字段（除非 tasks 阶段证明必要），round-trip 自动闭合。契约测试（push→pull 等价）列入 tasks。✅
- **III（保真）**：contracts/task-executor.md 明确新类型必须在 LocalRun + `dw` CLI 接线并有 parity 断言。✅
- **V（内核复用）**：data-model 确认执行器为纯 add-only、无状态机/schema DDL 改动。✅

**复核结论：设计不引入新违规，Gate 保持 PASS。**
