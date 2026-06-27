# Tasks: Weft 子特性 E —— MCP 工具重塑

**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Branch**: `010-weft-mcp-tools`

复用约束(硬):C(`ProjectSyncService`)/`PolicyEngine`/`GatedActionService`/`/mcp` 框架**零修改**;风险定级靠 `policy_rules` 数据 seed(不改 PolicyEngine 代码);写零旁路闸门;读零跨租户。i18n 走 `Messages.get`(agent locale)。测试禁 `_skipped`/注释 `@Test`;真跑 `-Dmaven.build.cache.enabled=false`。

## Phase 1: Setup

- [ ] T001 盘点现存 `McpToolRegistry.registerTools()` 全部工具,标注保留/移除/重塑(对照 data-model 工具矩阵),确认无 A 阶段残留 AI 工具(query_diagnosis 等)
- [ ] T002 [P] 加配置项 `mcp.auth.tenant-id` / `mcp.auth.user-id`(随 `mcp.auth.token`),application 配置 + 默认值(本地开发),启动期缺失校验

## Phase 2: Foundational —— MCP 身份（阻塞所有 US，E1 关键)

- [ ] T003 `McpTool`/`McpContext` 扩 `tenantId()`/`userId()`,handler 可取身份
- [ ] T004 `McpAuthFilter` 校验 token 后解析绑定身份(MVP 从配置),放行时把 tenant/user 置入 exchange 属性
- [ ] T005 `McpController` 分发工具前 `TenantContext.set(tenantId,userId)`,`finally` clear;身份缺失 → `mcp.tenant_required` 错误码(双 bundle)
- [ ] T006 [P] `McpTenantIdentityTest`:有身份正常;无 token 401;缺身份配置返回 `mcp.tenant_required`;并发调用 thread-local 不串

## Phase 3: User Story 1 — 只读读平台态 (P1)

**Independent Test**: 每个只读工具返回与 REST/域服务同源数据;越权/跨租户拒。

- [ ] T007 [US1] `query_task_definitions` 隔离回补:`taskDefRepository.findAll()` → 按 `TenantContext.tenantId()` 过滤(repo 缺 `findByTenantId` 则增量补,不改既有签名)— FR-007
- [ ] T008 [P] [US1] `query_task_instances`/`query_fleet`/`query_metric`/`query_lineage` 同样补租户过滤
- [ ] T009 [US1] 新增 `instance_logs` 只读工具:复用 `OpsService` 日志读取,tenant-scoped,读运行日志快照 — FR-001
- [ ] T010 [P] [US1] `McpReadSameSourceTest`:只读工具与对应 REST 抽样同源口径;`McpTenantIsolationTest`:租户 A 取租户 B 资源被拒(含既有 query_*)— SC-003/005

## Phase 4: User Story 2 — 经 MCP 推回定义 (P1)

**Independent Test**: project_push 经闸门;纯增改 L1 落库+审计;含删除 L2 PENDING 0 落库;无效定义拒。

- [ ] T011 [US2] seed `policy_rules` 两条:`PROJECT_PUSH`=L1、`PROJECT_PUSH_DESTRUCTIVE`=L2(data.sql / 迁移),i18n-exempt 中文 — FR-006(E3)
- [ ] T012 [US2] `DefaultPlatformActionExecutor` 新增 `PROJECT_PUSH`/`PROJECT_PUSH_DESTRUCTIVE` case:解码 payload → `ProjectSyncService.push(projectId,tenantId,userId,PushCommand)`(`@Transactional` 全有或全无)— FR-005(E4)
- [ ] T013 [US2] `McpToolRegistry` 新增 `project_pull` 工具(复用 `ProjectSyncService.pull`,tenant-scoped)— FR-004
- [ ] T014 [US2] 新增 `project_diff` 工具(复用 `ProjectSyncService.diff`,只读)— FR-004
- [ ] T015 [US2] 新增 `project_push` 工具:先 `diff` 算 removed → 选 actionType(removed 非空或 force → DESTRUCTIVE)→ 构造 `ActionRequest`(payload=files+baseline+force)→ `gatedActionService.submit` → 返回 EXECUTED/PENDING/REJECTED — FR-003/005/006
- [ ] T016 [P] [US2] `McpProjectPushGateTest`:纯增改 → L1 EXECUTED+审计+落库+newBaseline;含删除/force → L2 PENDING 且断言 0 落库;无效定义 → `project.sync.*` 拒不部分落库 — SC-002/004
- [ ] T017 [US2] `McpProjectPushParityTest`:project_push(MCP)与 C `ProjectSyncService.push` 直调对同输入语义等价(round-trip 一致)— SC-004

## Phase 5: User Story 3 — 受控运行与诊断 (P2)

**Independent Test**: task_rerun/node_exec 经闸门+审计,高风险进审批;node_exec 安全解析不弱化;读日志闭环。

- [ ] T018 [US3] `task_rerun` 保留 + 补 tenant-scoped(校验实例属本租户);维持既有闸门 — FR-005
- [ ] T019 [US3] `node_exec` 保留 + tenant-scoped;确认命令串安全解析(重定向/分隔/子命令 → ≥L2)不弱化 — FR-009
- [ ] T020 [P] [US3] `McpControlledRunTest`:task_rerun/node_exec 经闸门+审计;node_exec 危险命令升级/拒;越权拒

## Phase 6: 移除旧工具 + Polish

- [ ] T021 移除 `create_task` 工具及其 `@io` 内联血缘编码路径(定义写入一律走 project_push)— FR-002a
- [ ] T022 [P] 移除/重塑 `batch_*` 内联创建类工具;`McpToolsListNoLegacyTest`:tools/list 0 个 create_task、0 个 AI 残留工具 — SC-001
- [ ] T023 [P] 工具描述 + 闸门反馈 i18n:`Messages.get`(agent locale);新增错误码 `mcp.tenant_required` 等双 bundle 对齐 — FR-010
- [ ] T024 [P] CLAUDE.md「Adding an MCP tool」/导航补 project_*/身份注入说明 — 由架构(我)统一改,避免与 D 抢 CLAUDE.md
- [ ] T025 全量验证:`./mvnw -q -pl dataweave-api,dataweave-master test -Dmaven.build.cache.enabled=false` 绿;复用的 C/PolicyEngine 测试 + ops 观测端点不回归(SC-006);quickstart.md MCP 流手验

## Dependencies & 并行

- Setup(T001-002)→ **身份 Foundational(T003-006,阻塞一切隔离/写入)**→ 各 US。
- US1(读+隔离回补)、US2(push)、US3(受控运行)在身份就绪后大体可并行;T011/T012 是 push 执行链前置,T015 依赖之。
- **MVP = 身份 + US1 + US2**(读 + 推回定义,AI 闭环核心);US3 为运行态排障补充。
- 移除旧工具(T021-022)宜在新 project_push 就绪后,确保写入路径不断档。

## 交叉面(与 D 对账)

- E 的 `project_push` 与 D 的 `dw push` 共用 C 同一 `ProjectSyncService` + 同一写闸门 —— 集成由架构对账语义一致,禁各自实现。
- T024 改 CLAUDE.md 与 D 的 T027 都由架构(我)串行改。
