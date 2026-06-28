# Tasks: Weft 掉头后代码库净化

**Input**: Design documents from `/specs/011-weft-cleanup/`（基准树 `e568c38`）

**Prerequisites**: plan.md (required), spec.md, research.md, data-model.md, contracts/

**Organization**: 按 spec 的 6 个 user story 分 phase；US4（freeze+alert）拆两 phase。每批删后即验证（编译/typecheck/grep/浏览器回归）。**实现期需绿测试基线**（Phase 2 BLOCKER）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件，无依赖）
- **[Story]**: 所属 user story（US1–US6）
- 含精确文件路径

---

## Phase 1: Setup（确认基准）

**Purpose**: 确认 worktree 站在真 main、起点编译绿

- [x] T001 确认基准树：`git rev-parse --short HEAD` = `e568c38`，`git status` 仅 `specs/011-weft-cleanup/` 未追踪 + `.specify/feature.json` 改动
- [x] T002 [P] 起点编译验证：`cd backend && ./dev-install.sh -q` 零错误（确认起点绿编译；测试基线见 Phase 2）

---

## Phase 2: Foundational（测试基线 BLOCKER）

**⚠️ CRITICAL**: e568c38 整合后 api 测试红（270:23F+66E，BUILD FAILURE）。FR-009/SC-001 验证门失效。**绿基线前不得开始 Phase 3+**。

- [ ] T003 🔴 BLOCKER 确认 api 测试基线绿：`cd backend && ./mvnw -pl dataweave-api test` → BUILD SUCCESS、0 Failures/0 Errors。**由别的 AI 修复（合并 followup，非 011 职责）**；长时间未修则 011 出手（排查 `SchedulingParameterIntegrationTest` context failure 级联，见 `[[weft-origin-merge-followup]]`）

**Checkpoint**: 测试基线绿 → 用户故事实现可开始

---

## Phase 3: User Story 1 — 服务端 AI 残留链（Priority: P1）🎯 MVP

**Goal**: 删除 AG-UI 聊天附件整链 + AgentReply + /agui 鉴权白名单（006 AI 拆除的残渣）

**Independent Test**: `./dev-install.sh` 零错误 + `grep -rn "ChatFile\|AgentReply" backend/` 零命中 + 启动 `/agui` 无 controller

- [x] T004 [P] [US1] 删 ChatFile 簇：`dataweave-api/interfaces/ChatFileController.java` + `test/.../ChatFileControllerTest.java` + `dataweave-master/application/ChatFileService.java` + `domain/{ChatFile,ChatFileRepository}.java` + `infrastructure/{ChatFileStorage,LocalChatFileStorage}.java`
- [x] T005 [P] [US1] 删 `dataweave-api/application/AgentReply.java`（零外部引用死代码）
- [x] T006 [US1] 删 `JwtAuthFilter.java` 的 `/agui` 白名单条目(:39) + javadoc(:24) + 连带注释(`CorsConfig.java:13`、`SseNoBufferingWebFilter.java:15`、`OpsController.java:43`)。**⚠️ 保留揭红 CORS 修复（`import :8` + `:54-59`）——与 /agui 无关**
- [x] T007 [US1] 连带清理（FR-011）：`schema.sql` 删 `agent_chat_file` 表(CREATE :911-923 + DROP :56[095814f 加]) + 本地 `data/chat-files/` 残留目录 + `application.yml` chat-files 路径(若有)
- [x] T008 [US1] 验证：`./dev-install.sh -q` + `grep -rn "ChatFile\|AgentReply" backend/` 零命中 + 启动确认 `/agui` 无映射

**Checkpoint**: US1 完成，服务端 AI 残留链清零

---

## Phase 4: User Story 2 — 前端孤儿视图与死文案（Priority: P1）🎯 MVP

**Goal**: 删 9 孤儿组件 + 死 i18n + 未用依赖 + 死 CSS + 配置残留

**Independent Test**: `pnpm typecheck` + `node scripts/check-i18n.mjs`（双 bundle 一致无孤儿）+ FR-012 浏览器回归

- [x] T009 [P] [US2] 删 9 孤儿组件：`components/ops/{instance-table,log-viewer-panel,task-def-list,task-search-bar}.tsx` + `components/settings-sheet.tsx` + `components/workspace/views/lineage-graph.tsx` + `components/ui/{sheet,skeleton,separator}.tsx`
- [x] T010 [US2] 删孤儿 i18n（`messages/zh-CN.json` + `en-US.json` 双 bundle 同步）：整删 10 命名空间 `agent`/`agentRail`/`approvalCard`/`chat`/`cockpit`/`diagnosis`/`diagnosisCard`/`findings`/`fixActions`/`resultTable`；`instanceTable` 仅保留 4 state key（`stateRunning`/`stateSuccess`/`stateFailed`/`stateStopped`，`run-logs-tabs.tsx:174-177` 消费）删其余 26；`settings` 删 `settings-sheet.tsx` 后重扫确认是否变孤儿。**⚠️ 保留 origin 新增活跃 `workflowInstanceDetail`/`instanceLog`**
- [x] T011 [P] [US2] 删 5 未用依赖：`package.json` 移除 `@phosphor-icons/react`/`@remixicon/react`/`dompurify`/`marked`/`morphdom` → `pnpm install`
- [x] T012 [P] [US2] 删死 CSS：`app/globals.css` 的 `.markdown-body` + `.dw-textarea-thumb` 规则块
- [x] T013 [P] [US2] 删配置残留：`.env.local`(`AGENT_URL`/`CHAT_MOCK`) + `scripts/check-i18n.mjs` 的 `chat`/`mock` allowlist + `lib/syntax-palette.ts` 的 `CHAT_SHIKI_THEME`
- [x] T014 [US2] 验证：`pnpm typecheck` + `node scripts/check-i18n.mjs` + **FR-012 浏览器回归**（run logs 流 / DAG 实例视图正常 / lineage placeholder 不破）

**Checkpoint**: US2 完成，前端孤儿清零、i18n 无孤儿

---

## Phase 5: User Story 3 — MVP 执行桩 + create_task 残留（Priority: P2）

**Goal**: 删路由冲突桩 + createAndOnline 死代码（E 已解耦，恢复 FR-004）

**Independent Test**: 单进程启动 `/internal/worker/exec` 仅一处注册 + `grep createAndOnline src/main` 零命中

- [x] T015 [P] [US3] 删 `dataweave-api/interfaces/ApiMvpWorkerExecController.java`（`WorkerExecService:32` 的 `"worker-exec"` 是线程名误报，非引用）
- [~] T016 [US3] ~~删 `createAndOnline`~~ **已撤销（统筹裁定 2026-06-28）**：见下 FR-004 改判。
- [~] T017 [US3] ~~迁移测试 fixture~~ **已撤销**：保留 `createAndOnline` 即保留现有 fixture，无需迁移。
- [~] T018 [US3] ~~验证 createAndOnline 零命中~~ **已撤销**：路由冲突桩验证并入 T015（已完成）。

**Checkpoint**: US3 路由冲突桩（T015）已消除；`createAndOnline` 按改判后的 FR-004 **保留**（理由见下）。

> **统筹改判 US3 / FR-004（2026-06-28，全局设计者裁定）**：执行期 011 回报“删 `createAndOnline` 须迁移/丢弃血缘测试夹具（T017 复杂）”，叠加产品新方向——**设计期血缘后期将在任务发布/创建时基于图库（neo4j）重做**——裁定 `createAndOnline`/`recordLineage`/`buildEdges`（含 A×B 交叉校验）为**即将被 neo4j 版替换的过渡品**：① 生产零调用、不主动有害；② 删它须为一个即将重写的子系统迁移测试、并丢掉 neo4j 版需参考的 A×B 语义；③ 故 **defer 不删**，随 neo4j 血缘重做一并退役。另记两条 followup：**(a)** 「建任务即建血缘」在 `push` 路径未落血缘（真写路径 `ProjectSyncService.push` 不调 `recordDesignTimeIo`，受红线阻断，不在本特性修）；**(b)** 血缘最终实现 = 发布期 + neo4j 图库。

---

## Phase 6: User Story 5 — OpsService.tasks 迁移（Priority: P3）

**Goal**: 前端 log-panel 迁移到 /api/ops/instances 后删废弃端点

**Independent Test**: log-panel tab 标题 taskId→name 正确 + `grep '/api/ops/tasks'` 精确列表零命中

- [x] T019 [US5] `frontend/lib/types.ts` 新增 `InstanceRow` 接口（对齐 `OpsContracts.java:21-24` 字段：`id, taskDefId, taskDefName, workflowInstanceId, runMode, state,...`）
- [x] T020 [US5] 迁移 `components/workspace/log-panel.tsx:74`：`useApi<TaskDef[]>("/api/ops/tasks")` → `useApi<Page<InstanceRow>>("/api/ops/instances")`（**解 Page 信封取 `.items`**）+ `m.set(r.taskDefId, r.taskDefName)`。无需 `runMode`（无 TEST tab）
- [x] T021 [US5] 删 `OpsController.tasks()`(:98 `@Deprecated`) + `OpsService.tasks()`(`@Deprecated`) + `types.ts` 的 `TaskDef`（若仅此处用）。**⚠️ 不删 `OpsController:269` `/tasks/{taskDefId}/latest-instance`（现行，task-editor-pane 在用）**
- [x] T022 [US5] 验证：`pnpm typecheck` + log-panel 功能保真（浏览器）+ `grep -rn '"/api/ops/tasks"' frontend/`（精确列表）零命中、latest-instance 保留

**Checkpoint**: US5 完成，废弃端点删除、log-panel 迁移到位

---

## Phase 7: User Story 4a — freeze_task 退役（Priority: P3）

**Goal**: 退役被 freeze_node 取代的 freeze_task MCP 工具

**Independent Test**: MCP tools.list 不含 freeze_task + `grep freeze_task|setFrozen` 零命中

- [x] T023 [US4] 前置（FR-015）：grep MCP 客户端代码 + 查调用记录，确认无外部 `freeze_task` 调用方；有则暂缓升级告知
- [x] T024 [US4] 删 `McpToolRegistry.java` freeze_task 注册块(:595-619 + 注释:590-594)
- [x] T025 [US4] 删 setFrozen 链：`DataOpsBridge.setFrozen`(@Deprecated) + `DataOpsBridgeRealImpl.setFrozen` + `DataOpsBridgeStub.setFrozen` + `OpsService.setFrozen` + `OpsServiceDataCenterTest` 的 setFrozen 用例
- [x] T026 [US4] 验证：`./dev-install.sh -q` + `./mvnw test` + MCP `tools.list` 不含 freeze_task + `grep -rn "freeze_task\|setFrozen" backend/` 零命中。**无 policy_rule 连带**（种子漂移，data.sql 无 FREEZE_TASK）

**Checkpoint**: US4a 完成，freeze_task 退役

---

## Phase 8: User Story 4b — alert 骨架移除（Priority: P3）

**Goal**: 移除零引用的 alert 整模块 + 6 处连带

**Independent Test**: 启动 `@EnableJdbcRepositories` 不报错 + `grep com.dataweave.alert` 零命中

- [x] T027 [US4] 删 `backend/dataweave-alert/` 整模块（`AlertRule`/`AlertRuleRepository`/`NotificationChannel`/`NotificationChannelRepository`/`NotificationSender`/`infrastructure/LogNotificationChannel`）
- [x] T028 [US4] 连带 6 处（缺一启动崩）：`backend/pom.xml` 删 `<module>dataweave-alert</module>`(:39) + `dataweave-api/pom.xml` 删 dependency(:41) + `DataWeaveApiApplication.java:28` `@EnableJdbcRepositories` 去 `"com.dataweave.alert.domain"`(**必须**) + `schema.sql` 删 alert 表(:732/747 + DROP :15/38) + `data.sql` 删 alert seed(:557-561) + 删 RESTART(:647/648)。**⚠️ 保留 `data.sql:452/454` `data_quality.alerts`（业务 SQL）**
- [x] T029 [US4] 验证：`./dev-install.sh -q` + 启动检查（`@EnableJdbcRepositories` 不抛异常）+ `grep -rn "com.dataweave.alert" backend/` 零命中

**Checkpoint**: US4b 完成，alert 骨架移除

---

## Phase 9: User Story 6 — specs 归档（Priority: P3）

**Goal**: 已落地的 12 个 spec 目录归档

**Independent Test**: `specs/` active 仅含 `011-weft-cleanup`

- [x] T030 [US6] 移动 12 目录到 `specs/archive/`（**逐个点名，不用 glob**——编号撞车）：`001-distributed-cron-trigger`、`002-ops-dag-viewer`、`003-instance-dag-viewer`、`004-dag-node-detail-panel`、`005-dag-dialog-consolidation`、`005-weft-pivot`、`006-weft-ai-teardown`、`006-workflow-instance-ops`、`007-weft-file-contract`、`008-weft-pull-push-api`、`009-weft-cli-runtime`、`010-weft-mcp-tools`
- [x] T031 [US6] 验证：`specs/` active 仅含 `011-weft-cleanup` + `archive/` 含 12 目录 + `.specify/feature.json` 指向 `specs/011-weft-cleanup`（CLAUDE.md 无 specs/NNN 链接，无需更新）

**Checkpoint**: US6 完成，active 规格区净化

---

## Phase 10: Polish & Cross-Cutting（全套收口）

- [ ] T032 全套构建：`cd backend && ./dev-install.sh` + `cd frontend && pnpm typecheck` + `node frontend/scripts/check-i18n.mjs` + `cd backend && ./mvnw test`（绿基线上）
- [ ] T033 [P] FR-012 浏览器回归全套：run logs SSE / DAG 实例视图 / lineage placeholder / Workspace 多标签 / log-panel tab 标题
- [ ] T034 [P] 引用归零总 grep：ChatFile/AgentReply/ApiMvpWorkerExec/createAndOnline/com.dataweave.alert/freeze_task/setFrozen/`/api/ops/tasks`（精确列表）全零命中
- [ ] T035 [P] 量化核对：`git diff --stat main..HEAD` 净减约 2900 行（SC-006）
- [ ] T036 连带配置终检：所有 FR-011 连带（agent_chat_file/alert seed/policy_rule）已清，无悬空

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 无依赖，立即开始
- **Phase 2 (Foundational 测试基线)**: 🔴 BLOCKS 全部 Phase 3+（绿基线前不得删除——否则测试红无法区分是删除导致还是原本红）
- **Phase 3-9 (US1-US6)**: 全部依赖 Phase 2 绿基线；彼此相对独立，建议按优先级 P1→P2→P3 顺序
- **Phase 10 (Polish)**: 依赖所有 US 完成

### Story 内部依赖

- US5（Phase 6）：T019(types.ts) → T020(log-panel) → T021(删后端)（前端先迁移再删后端）
- US4b（Phase 8）：T027(删模块) → T028(连带 6 处) 同步（删模块与改 pom/App 必须同批，否则启动崩）
- US4a（Phase 7）：T023(前置验证) → T024/T025(删)（先确认无客户端调用再删）

### 并行机会

- Phase 1: T002 独立
- US1: T004/T005 并行（不同文件簇）
- US2: T009/T011/T012/T013 并行（不同文件）
- US4a/US4b（Phase 7/8）: freeze 与 alert 互不依赖，可并行
- Polish: T033/T034/T035 并行

---

## Implementation Strategy

### MVP First（US1 + US2）

1. Phase 1 Setup + Phase 2 绿基线（等别的 AI）
2. Phase 3 (US1) + Phase 4 (US2)——最大体量的死代码（~2700 行），ROI 最高
3. STOP 验证：编译/typecheck/浏览器回归
4. 继续剩余 US

### 增量交付

每 phase 后独立验证（编译/grep/浏览器），绿了再下一个。删除型任务每批隔离验证是安全网。

---

## Notes

- 实现期开始前**复查 main 是否仍 = e568c38**（moving target）；若推进需 rebase + 重核行号
- 测试基线（Phase 2）由别的 AI 修；长时间未修 011 出手（`SchedulingParameterIntegrationTest` context 级联）
- 每个 US 验证含 `grep` 归零 + 编译/浏览器回归（宪法 IV 红线守护）
- `[P]` = 不同文件无依赖可并行；`[US]` = user story 映射
