# Research: Weft 掉头后代码库净化（v2 — 真树纠正）

**Feature**: 011-weft-cleanup | **Date**: 2026-06-28 | **Phase**: 0（Plan 核实）| **基准树**: `e568c38`（真 main）

> ⚠️ **版本说明**：v1 基于过时的 `6177ffa`（E 重塑**之前**的树），createAndOnline 判定、MCP/JwtAuthFilter/data.sql 行号全部失真。根因：建 worktree 后收尾 AI 把 D/E/揭红合入 main，main 推进，plan 未复查基准树位置。已 `git reset --hard main` 把 011 站到真 main（`e568c38`，含 D/E/揭红 + origin/main 整合）。本 v2 在真树上重核。

---

## R1. OpsService.tasks 迁移目标（v1 结论不变，补充确认）

**Decision**: 迁移到 `GET /api/ops/instances`（非 `/periodic-workflows`/`/manual-workflows`）。

**Rationale**: `log-panel.tsx` 仅消费 `t.id`+`t.name` 构建 `taskId→name` 映射渲染 tab 标题。两个工作流端点返回 `WorkflowListRow`（workflow 维度，主键不匹配 taskId 查找）。`/api/ops/instances` 的 `InstanceRow` 携带 `taskDefId`+`taskDefName`，可零成本构建映射。

**补充（评审确认）**: `log-panel` **无 TEST tab**——v1 的"留待实现期确认 runMode"可关闭，迁移无需带 `runMode` 参数。工作量小（单文件 ~10 行）。详见 [contracts/ops-tasks-migration.md](contracts/ops-tasks-migration.md)。

---

## R2. alert 骨架移除连带（行号更新到真 main）

**Decision**: 移除整模块需同步改 6 处（真 main 行号）。alert 模块外**零 Java 引用**，`@EnableJdbcRepositories` 仍列 `com.dataweave.alert.domain`（删模块后必须同步移除，否则启动崩）。

| # | 文件:行 | 改动 |
|---|---------|------|
| 1 | `backend/pom.xml:39` | 删 `<module>dataweave-alert</module>`（dependencyManagement 在 :57） |
| 2 | `backend/dataweave-api/pom.xml:41` | 删 `<artifactId>dataweave-alert</artifactId>` dependency |
| 3 | `DataWeaveApiApplication.java:28` | `@EnableJdbcRepositories` 去 `"com.dataweave.alert.domain"`（import :9） |
| 4 | `schema.sql:15, :38, :732, :747`（CREATE +5 偏移，095814f 后） | 删 `notification_channels` + `alert_rules` 的 DROP/CREATE |
| 5 | `data.sql:557-558, :560-561` | 删 alert seed（notification_channels + alert_rules INSERT） |
| 6 | `data.sql:647, :648` | 删 `ALTER TABLE ... RESTART WITH 100` |

**保留**: `data.sql:452, :454` 的 `data_quality.alerts`（业务 demo SQL 字符串，非 DDL 表，与模块无关）。

---

## R3. CLAUDE.md specs 链接（v1 结论不变）

**Decision**: 归档 `specs/001-010` 时 CLAUDE.md **无需批量更新**——导航表无 `specs/NNN` 链接（`grep` 零命中）。仅 `:142` 历史叙述举例可选更新。

---

## R4. createAndOnline（⚠️ v1 推翻 — 确认死代码，恢复 FR-004）

**Decision**: `TaskService.createAndOnline` **是死代码**，删除并迁移 2 个测试。恢复 spec FR-004、拓宽 Story 3。

**v1 错误根因**: v1 在 `6177ffa`（E 之前）树上核实，那时 `DefaultPlatformActionExecutor:132` 仍调 `createAndOnline`（MVP 执行器实现）→ v1 据此判"活代码"，撤销 FR-004。

**真 main（e568c38）事实**: E 重塑把 PROJECT_PUSH case 从 `createAndOnline` 换成 `projectPush` 方法：
- `DefaultPlatformActionExecutor.java:94` — `case "PROJECT_PUSH","PROJECT_PUSH_DESTRUCTIVE" -> projectPush(action,locale);`
- `projectPush`（:226-275）解码 JSON payload → `projectSyncService.push(...)`（:254-255）
- **不再调 `createAndOnline`**

`createAndOnline` 在真 main 的引用分布：
| 类型 | 位置 |
|---|---|
| 定义（src/main） | `TaskService.java:387`（4 参重载，注释 :384「兼容旧方法（MCP create_task 调用）」）、`:388`（委托）、`:399-401`（8 参本体） |
| 测试（src/test） | `TaskServiceLineageTest.java:73,88,98,108,119`（5 处）、`LineageGraphEndpointTest.java:45`（1 处） |

src/main **零生产调用方**。按 FR-013（仅测试引用=死代码）= 死代码。specify 原判正确。

**教训（v1 教训的纠正）**: v1 写"引用核实必须覆盖全部模块"——这是**反教训**（v1 确实覆盖了 master，但在错树上）。真教训：**plan 核实必须在最终基准树上跑，且 main 是 moving target（收尾 AI 持续推进），每次核实前必须确认基准树位置**。

---

## R5. 死代码抽样（真 main e568c38 确认）

| 符号 | src/main | src/test | 判定 |
|---|---|---|---|
| `ChatFileController` | 仅自身定义 | `ChatFileControllerTest` | 死代码 ✓ |
| `ChatFileService`/`ChatFile`(domain)/`ChatFileRepository`/`ChatFileStorage`/`LocalChatFileStorage` | 簇内互引，无外部生产调用 | — | 死代码簇 ✓ |
| `AgentReply` | 仅自身定义（10 处引用全在自身文件） | — | 死代码 ✓ |
| `ApiMvpWorkerExecController` | 仅自身定义（`WorkerExecService:32` 是 `"worker-exec"` 线程名误报，非类引用） | — | 死代码 ✓ |
| `createAndOnline` | 仅 TaskService 自身定义 | 2 文件 6 处 | 死代码（R4） |
| 前端 `InstanceTable`/`LogViewerPanel`/`TaskDefList`/`TaskSearchBar`/`SettingsTrigger`/`LineageGraph` | — | — | 零 importer，死代码 ✓ |

**ChatFile 簇连带**: `schema.sql` 的 `agent_chat_file` 表（CREATE `:911-923` + DROP `:56`[095814f 加]，无 seed）。

**注意**: 前端 `LineageGraph`(组件) 是死代码，**勿混**后端 `LineageGraphService`/`LineageGraphController`（活跃生产类）。

---

## R6（新增）. McpToolRegistry E 重塑后结构 + freeze_task 种子漂移

**E 重塑后工具集**（registerTools() 共 26 个工具）：`project_pull/diff/push`、`query_*`（task_definitions/instances/fleet/metric/lineage/failed_instances）、`instance_logs`、`task_rerun`、`node_exec`、`approve_and_execute`、`pause/resume/kill_instance`、`test_run`、`trigger/resume/rerun_workflow`、`rerun_instance`、`set_instance_success`、`batch_instance_ops`、`freeze_task`、`submit/query_backfill`。

- `create_task`/`update_task`/`delete_task`：**已删**（仅注释残留 `:323`/`:384`「CRUD 工具已移除，定义写入一律走 project_push」）
- `project_push`：在（`:184`，E 新增，风险自适应 L1/L2）
- `freeze_node`：**未注册为 MCP 工具**（仅 REST `OpsController:515` + `DataOpsBridge.setNodeFrozen`）

**freeze_task 注册块**: `McpToolRegistry.java:595-619`（注释 `:590-594`「TODO 暂留以兼容现有 MCP 客户端工具列表」），handler 调 `dataOpsBridge.setFrozen`（:612），actionType=`FREEZE_TASK`（:604）。

**⚠️ 种子漂移（重要）**: `freeze_task` 与 `freeze_node` 的 `policy_rules` seed **都不在** data.sql（`grep FREEZE_TASK|FREEZE_NODE` 零命中）——这两个写动作走 PolicyEngine 默认 L2。**退役 freeze_task 时无 policy_rule 连带要删**（v1 假设"若有"是多余的）。

---

## R7（新增）. JwtAuthFilter 揭红细节

- `/agui` 白名单条目：`JwtAuthFilter.java:39`（`PREFIX_WHITELIST` :38-44），javadoc :24
- **揭红 7762422 的 8 行是 CORS 预检修复**（import `CorsUtils` :8 + `filter()` 开头预 flight 放行 :54-59），**与 /agui 无关**——删 /agui 白名单条目不影响揭红的 CORS 修复。
- 连带 /agui 注释：`CorsConfig.java:13`、`SseNoBufferingWebFilter.java:15`、`OpsController.java:43`

---

## R8（第二轮审查重核）. InstanceRow 迁移契约修正

`/api/ops/instances` 返回 **`Page<InstanceRow>` 信封**（`OpsController:192 new Page<>(...)`），**非裸 List**——v2 contract 写成 List 是错的，迁移必须解 `.items`。`InstanceRow` 是 **OpsContracts 版**（`OpsContracts.java:21-24`，字段含 `taskDefId`/`taskDefName`/`workflowInstanceId`...），**非** `dto/InstanceRow.java`（后者是孤儿，OpsService 未用，可收尾清）。前端 `types.ts` **缺 `InstanceRow` 接口**（迁移前须新增）。`taskDefId`/`taskDefName` 存在 → 迁移可行。origin 新增 `WorkflowInstanceRow`（`/api/ops/workflow-instances`，活的，不碰）。

## R9（第二轮审查重核）. i18n 孤儿精确清单

双检测（`useTranslations("ns")` + `t("ns.` 全前缀）后：
- **真孤儿 10 命名空间**（全 006 AI 残留，双 bundle 同步删整）：`agent`、`agentRail`、`approvalCard`、`chat`、`cockpit`、`diagnosis`、`diagnosisCard`、`findings`、`fixActions`、`resultTable`
- **`instanceTable`**：保留 4 state key（`stateRunning`/`stateSuccess`/`stateFailed`/`stateStopped`，`run-logs-tabs.tsx:174-177` 消费），删其余 26
- **`settings`**：联动待定——删 `settings-sheet.tsx` 后重扫确认是否变孤儿
- **origin 新增活跃（不可删）**：`workflowInstanceDetail`、`instanceLog`
- **5 个易误判**（useT=0 但 t-prefix 活跃，保留）：`auth`、`versionHistory`、`workflowConfig`、`workspace`、`settings`（联动前）

## R10（第二轮审查重核）. specs 归档逐目录

specs/ 13 目录，**编号撞车**（`005-dag-dialog-consolidation`↔`005-weft-pivot`、`006-weft-ai-teardown`↔`006-workflow-instance-ops`）——glob `001-010` 会漏 weft 的 005/006。12 个目录（除 011）**全部已落地**（含 origin 带进的两个），逐目录点名归档。部分 tasks.md 未勾项属漂移（特性已 commit 落地），归档即冻结。

## Phase 0 结论（v2 + 第二轮重核）

所有失真已纠正：
- R4 createAndOnline → 恢复死代码判定，恢复 FR-004
- R2/R6/R7 行号 → 真主 e568c38
- R6 freeze_task 种子漂移 → 无 policy_rule 连带
- R8 InstanceRow → Page 信封 + types.ts 缺接口 + dto 版孤儿
- R9 i18n → 10 真孤儿 + instanceTable 4-key + origin 活跃保留
- R10 specs → 逐目录点名（编号撞车）

**遗留**：测试基线红绿待第一手确认（审查称 270:23F+66E，引用 [[weft-origin-merge-followup]]；若红，011 实现期需先修 api 测试隔离拿绿基线，否则 FR-009/SC-001 验证门失效）。基准树 e568c38（main moving target，再推进需复查）。
