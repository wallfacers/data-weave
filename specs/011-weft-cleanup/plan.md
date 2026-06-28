# Implementation Plan: Weft 掉头后代码库净化（v2 — 真树纠正）

**Branch**: `011-weft-cleanup` | **Date**: 2026-06-28 | **Spec**: [spec.md](spec.md) | **基准树**: `e568c38`（真 main）

> ⚠️ **v2 纠正**：v1 错建在 `6177ffa`（E 重塑**之前**的树），导致 `createAndOnline` 判定反转、MCP/JwtAuthFilter/data.sql 行号全失真。根因：建 worktree 后收尾 AI 把 D/E/揭红合入 main，main 推进，plan 未复查基准树。已 `git reset --hard main` 让 011 站到真 main（`e568c38`），本 v2 在真树上重写。详见 [research.md](research.md) R4/R6/R7。

## Summary

Weft 掉头后代码库的系统性净化：删除服务端 AI 编排残留链（ChatFile 整链 / AgentReply / AG-UI 鉴权白名单）、前端孤儿视图与死文案、MVP 执行桩 + create_task 残留方法（`createAndOnline`）；退役 `freeze_task` MCP 工具与 `dataweave-alert` 空骨架；迁移 `OpsService.tasks` 到 `/api/ops/instances` 后删除废弃端点；归档 `specs/001-010`。分 7 批顺序删除，每批 `./dev-install.sh` 编译 + `pnpm typecheck` + 测试 + 浏览器回归（宪法 IV 红线守护）。

> **关键纠正（vs v1）**：`createAndOnline` 在真 main 上是**死代码**（E 已把 PROJECT_PUSH 换成 `projectSyncService.push`，`createAndOnline` 无生产调用方，仅 2 测试引用）。specify 原判正确，**恢复 FR-004**，回归批次 3。

## Technical Context

**Language/Version**: Java 25（backend，JDK 25 symlink swap）+ TypeScript（frontend，Next.js 16 App Router）

**Primary Dependencies**:
- 后端：Spring Boot 4.0 / Spring Framework 7 / **Jackson 3** / WebFlux / Spring Data JDBC + JdbcTemplate / Maven 多模块（`com.dataweave`：api/master/worker/alert）
- 前端：React 19 / shadcn/ui（base style）/ hugeicons / next-intl / zustand

**Storage**: PostgreSQL（生产）/ H2（`profiles=h2`）/ Redis / MinIO。死存储：`data/chat-files/`（批次 1 一并删）

**Testing**: JUnit 5 + AssertJ + WebTestClient（后端）；vitest + 浏览器验证（前端）；`scripts/check-i18n.mjs`（i18n 双 bundle 一致性 CI 门）

**Target Platform**: Linux server（`:8000`）+ Web（`:4000`）

**Project Type**: web-service（backend Maven 多模块 + frontend Next.js）

**Performance Goals**: N/A——净化任务不引入性能目标；成功标准是零行为回归（FR-010）

**Constraints**:
- 零行为回归（FR-010）
- 宪法 IV 红线（NON-NEGOTIABLE）：不得损害 observability（run logs / DAG / lineage）与 scheduling kernel
- **worktree 基准 `e568c38`**（真 main，含 D/E/揭红）：已 `git reset --hard main`。main 是 moving target（收尾 AI 持续推进），若 main 再前进需复查基准

**Scale/Scope**: ~2800 行死代码 + `dataweave-alert` 整模块 + `specs/001-010` 归档

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

对照 `.specify/memory/constitution.md` Principle I–V：

| Principle | 判定 | 说明 |
|-----------|------|------|
| **I. Files-First** | ✅ Pass | 净化不涉及文件化定义 |
| **II. Server Source of Truth** | ✅ Pass | 净化不改 pull/push 治理、版本快照、租户隔离；删除的 ChatFile 数据 / `agent_chat_file` 表是 AI 遗留 |
| **III. Two-Legged Debugging (NON-NEGOTIABLE)** | ✅ Pass | 净化不动 CLI runtime / SQL·Shell executor / TEST 提交。**关键**：E 重塑后 `DefaultPlatformActionExecutor` 的 PROJECT_PUSH case 已改走 `projectSyncService.push`（:94/:226-275），**不再调 `createAndOnline`**——故删除 `createAndOnline` 不影响 executor 复用链路 |
| **IV. AI Lives Local (NON-NEGOTIABLE)** | ✅ Pass | 净化即履行 IV「chat cockpit / AG-UI / workhorse / proactive-notify / findings MUST be removed cleanly, **no residue**」。**红线**：observability + scheduling kernel 不可损害 → FR-012 浏览器回归 + FR-014 动态引用验证守护 |
| **V. Reuse the Kernel** | ✅ Pass | 净化是删除非重写；不动 scheduler / 版本快照 / executor / PolicyEngine / MCP framework |

**Additional Constraints**: No Legacy Migration（`agent_chat_file` 属 AI 遗留，删除符合）✅；Isolated worktree（`dw-011-weft-cleanup`，基于真 main）✅

**Gate 结论**：无违反，无需 Complexity Tracking。实现期 Re-check：删 observability 类孤儿后必须通过 FR-012 浏览器回归。

## Project Structure

### Documentation (this feature)

```text
specs/011-weft-cleanup/
├── plan.md                          # 本文件（v2）
├── research.md                      # Phase 0（v2 真树核实）
├── data-model.md                    # 受影响数据资产（真实行号）
├── contracts/ops-tasks-migration.md # OpsService.tasks 迁移契约
├── quickstart.md                    # 清理后验证清单
├── checklists/requirements.md
└── tasks.md                         # Phase 2（/speckit-tasks 产出）
```

### Source Code (删除清单分布，行号基于 e568c38)

```text
backend/
├── pom.xml                                  # [批6:39] 删 <module>dataweave-alert</module>
├── dataweave-alert/                         # [批6] 整模块删除（6 Java 类）
├── dataweave-api/
│   ├── pom.xml                              # [批6:41] 删 alert dependency
│   ├── DataWeaveApiApplication.java         # [批6:28] @EnableJdbcRepositories 去 alert.domain（必须）
│   ├── interfaces/ChatFileController.java   # [批1] 删
│   ├── interfaces/ApiMvpWorkerExecController.java  # [批3] 删（路由冲突桩）
│   ├── interfaces/OpsController.java        # [批4] 删 @Deprecated tasks()
│   ├── application/AgentReply.java          # [批1] 删
│   ├── application/mcp/McpToolRegistry.java # [批5:595-619] 删 freeze_task 注册块
│   ├── application/DataOpsBridge*.java       # [批5] 删 setFrozen 链
│   ├── infrastructure/JwtAuthFilter.java    # [批1:39] 删 /agui 白名单 + 注释:24
│   └── resources/schema.sql                 # [批1:911-923+DROP:56] agent_chat_file；[批6:732,747] alert 表
│   └── resources/data.sql                   # [批6:557-561,647-648] alert seed（freeze_task 无 seed，漂移）
└── dataweave-master/
    ├── application/ChatFileService.java     # [批1] 删
    ├── application/TaskService.java         # [批3:387,388,399] 删 createAndOnline（死代码）
    ├── application/OpsService.java          # [批4/5] 删 @Deprecated tasks() + setFrozen
    ├── application/DefaultPlatformActionExecutor.java  # 不动（已改走 projectSyncService.push）
    ├── domain/{ChatFile,ChatFileRepository}.java  # [批1] 删
    └── infrastructure/{ChatFileStorage,LocalChatFileStorage}.java  # [批1] 删

frontend/  # [批2] 孤儿组件 9 + 死 i18n + 5 未用依赖 + 死 CSS + 配置；[批4] log-panel 迁移
specs/001-010/ → specs/archive/  # [批7] 归档
```

## 删除清单与分批验证计划

> 验证基线（每批通用）：① 后端 `./dev-install.sh -q` 零错误；② 前端 `pnpm typecheck`；③ 相关测试通过；④ 全仓库 `grep` 确认已删项零残留。

### 前置依赖（批次 0）— 测试基线必须先绿

**⚠️ BLOCKER**：e568c38 整合 origin/main 后，api 测试套件基线为红（据第二轮审查 + `[[weft-origin-merge-followup]]`：270 项中 23F+66E，test-isolation 级联）。FR-009/SC-001 用"测试通过"作每批删除验证门——基线红时，删除后测试红无法区分是删除导致还是原本红，**验证门失效**。

**处置**：011 实现期（`/speckit-tasks` → 实现）开始前，**必须先修 api 测试隔离拿绿基线**（属合并 followup / 009 收尾范畴，**非 011 死代码清理职责**）。绿基线到位前不得开始批次 1-7 的删除。

**待复核**：审查结论待第一手验证（后台测试进行中）；若实际为绿则此前置撤销。

### 批次 1 — 服务端 AI 残留链（P1）

**删除**:
- `dataweave-api/interfaces/ChatFileController.java` + `test/ChatFileControllerTest.java`
- `dataweave-master/application/ChatFileService.java` + `domain/{ChatFile,ChatFileRepository}.java` + `infrastructure/{ChatFileStorage,LocalChatFileStorage}.java`
- `dataweave-api/application/AgentReply.java`
- `JwtAuthFilter.java:39` 删 `"/agui"` 白名单条目 + 连带 javadoc（:24）+ 连带注释（`CorsConfig:13`、`SseNoBufferingWebFilter:15`、`OpsController:43`）

**连带（FR-011）**: `schema.sql` 的 `agent_chat_file` 表（CREATE `:911-923` + DROP `:56`[095814f 加]，无 seed）；本地 `data/chat-files/` 残留；`application.yml` chat-files 路径（若有）。

**验证**: 编译 + `grep -rn "ChatFile\|AgentReply" backend/` 零命中 + 启动检查 `/agui` 无 controller。**注意**：揭红 7762422 给 JwtAuthFilter 加的 CORS 预检修复（:8 import + :54-59）**不删**——它和 /agui 白名单无关。

### 批次 2 — 前端孤儿视图与死文案（P1）

**删除**: 孤儿组件（9）：`ops/{instance-table,log-viewer-panel,task-def-list,task-search-bar}.tsx`、`settings-sheet.tsx`、`workspace/views/lineage-graph.tsx`、`ui/{sheet,skeleton,separator}.tsx`；**孤儿 i18n 命名空间**（双 bundle 同步删整命名空间，agent 重核确认）：`agent`、`agentRail`、`approvalCard`、`chat`、`cockpit`、`diagnosis`、`diagnosisCard`、`findings`、`fixActions`、`resultTable`（10 个，全 006 AI 残留）；`instanceTable` 仅保留 4 个 state key（`stateRunning`/`stateSuccess`/`stateFailed`/`stateStopped`，`run-logs-tabs.tsx:174-177` 消费），删其余 26；`settings` 联动待定（删 `settings-sheet.tsx` 后重扫确认是否变孤儿）；**保留 origin 新增的活跃命名空间** `workflowInstanceDetail`、`instanceLog`（**不可删**）；未用依赖（5）：`@phosphor-icons/react`、`@remixicon/react`、`dompurify`、`marked`、`morphdom`；死 CSS（`globals.css` `.markdown-body` + `.dw-textarea-thumb`）；配置（`.env.local`、`check-i18n.mjs` allowlist、`syntax-palette.ts` 的 `CHAT_SHIKI_THEME`）。

**验证**: `pnpm typecheck` + `check-i18n.mjs` + **FR-012 浏览器回归**（run logs / DAG / lineage placeholder）。

### 批次 3 — MVP 执行桩 + create_task 残留（P2，恢复完整范围）

**删除**:
- `dataweave-api/interfaces/ApiMvpWorkerExecController.java`（确认死代码；`WorkerExecService:32` 的 `"worker-exec"` 是线程名，非类引用）
- `dataweave-master/application/TaskService.java` 的 `createAndOnline`（:387 4 参重载 + :388 委托 + :399-401 8 参本体 + :384 兼容注释）——E 重塑后无生产调用方（`DefaultPlatformActionExecutor` 已改走 `projectSyncService.push`）

**测试迁移**: `TaskServiceLineageTest.java`（:73,88,98,108,119）+ `LineageGraphEndpointTest.java:45` 改用 `taskDefRepository.save` + `lineageGraphService.recordDesignTimeIo`（`LineageGraphService:66` public @Transactional，直接落血缘）。**`saveDraft`+`publish` 与 `projectSyncService.push` 均不落血缘（已核实），不可替代**——测试必须直接调 `recordDesignTimeIo` 或封装 helper。

**验证**: 编译 + 单进程启动确认 `POST /internal/worker/exec` 仅 `WorkerExecController` 一处 + `grep "createAndOnline" backend/ src/main` 零命中 + 迁移后测试通过。

### 批次 4 — OpsService.tasks 迁移 + 删除（P3）

**迁移**: `log-panel.tsx` 改用 `/api/ops/instances`——返回 **`Page<InstanceRow>` 信封**（取 `.items`），用 `InstanceRow.taskDefId/taskDefName` 构建映射。**前端 `types.ts` 需先新增 `InstanceRow` 接口**（agent 重核：当前缺失）。log-panel 无 TEST tab，无需带 `runMode`。

**删除**: `OpsController.tasks()`（:98 `@Deprecated`）+ `OpsService.tasks()`（`@Deprecated`）+ `frontend/lib/types.ts` 的 `TaskDef`（若仅此处用）。

**⚠️ 不删**: `OpsController:269` 的 `GET /api/ops/tasks/{taskDefId}/latest-instance`（**现行端点**，`task-editor-pane.tsx:234` 在用）——只删 :98 的列表端点，勿混淆。

**连带（收尾）**: `dto/InstanceRow.java` 是孤儿（OpsService 用 OpsContracts 版），可顺清。

**验证**: typecheck + log-panel 功能保真 + `grep -rn '"/api/ops/tasks"' frontend/`（精确列表）零命中、latest-instance 保留。详见 [contracts/ops-tasks-migration.md](contracts/ops-tasks-migration.md)。

### 批次 5 — freeze_task 退役（P3，前置客户端验证）

**前置（FR-015）**: grep MCP 客户端代码 + 查调用记录，确认无外部 `freeze_task` 调用方；若有则暂缓升级。

**删除**:
- `McpToolRegistry.java:595-619` 的 freeze_task 注册块（注释 :590-594「暂留兼容」）+ handler 调 `setFrozen`（:612）
- `DataOpsBridge.setFrozen`（@Deprecated）+ `DataOpsBridgeRealImpl.setFrozen` + `DataOpsBridgeStub.setFrozen`
- `OpsService.setFrozen` + 相关测试（`OpsServiceDataCenterTest` 的 setFrozen 用例）

**⚠️ 无 policy_rule 连带**: `freeze_task` 的 `policy_rules` seed **不在** data.sql（种子漂移，走默认 L2）——退役时无需删 policy_rule。

**验证**: 编译 + 测试 + MCP `tools.list` 不含 freeze_task + `grep "freeze_task\|setFrozen"` 零命中。

### 批次 6 — alert 骨架移除（P3，整模块）

**删除**: `dataweave-alert/` 整模块（6 Java 类）。

**连带（6 处，真 main 行号）**:
1. `backend/pom.xml:39` 删 `<module>dataweave-alert</module>`
2. `dataweave-api/pom.xml:41` 删 `<artifactId>dataweave-alert</artifactId>`
3. `DataWeaveApiApplication.java:28` `@EnableJdbcRepositories` 去 `"com.dataweave.alert.domain"`（**必须**，否则启动崩）
4. `schema.sql:732, :747`（+ DROP :15, :38）删 `notification_channels` + `alert_rules` 表
5. `data.sql:557-558, :560-561` 删 alert seed
6. `data.sql:647, :648` 删 `ALTER TABLE ... RESTART WITH 100`
7. **保留** `data.sql:452, :454` 的 `data_quality.alerts`（业务 demo SQL）

**验证**: 编译 + 启动检查（`@EnableJdbcRepositories` 不报错）+ `grep -r "com.dataweave.alert" backend/` 零命中。

### 批次 7 — specs 归档（P3，逐目录点名）

**移动 12 个目录到 `specs/archive/`**（⚠️ 编号撞车，**不能用 glob `001-010`**——会漏 `005-weft-pivot` / `006-weft-ai-teardown`）：`001-distributed-cron-trigger`、`002-ops-dag-viewer`、`003-instance-dag-viewer`、`004-dag-node-detail-panel`、`005-dag-dialog-consolidation`、`005-weft-pivot`、`006-weft-ai-teardown`、`006-workflow-instance-ops`、`007-weft-file-contract`、`008-weft-pull-push-api`、`009-weft-cli-runtime`、`010-weft-mcp-tools`（agent 重核：12 个全部已落地，含 origin 整合带进的 `005-dag-dialog-consolidation` / `006-workflow-instance-ops`；部分 tasks.md 有未勾项属漂移，归档即冻结历史）。

**CLAUDE.md**: 无 `specs/NNN` 链接（无需批量更新）；`feature.json` 已指向 011。

**验证**: `specs/` active 区仅含 `011-weft-cleanup`；`archive/` 含 12 个目录。

## Complexity Tracking

> 无 Constitution Check 违反，本节不适用。
