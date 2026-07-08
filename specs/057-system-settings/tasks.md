---

description: "Task list for feature implementation"
---

# Tasks: 全局系统设置——AI Agent 配置统收

**Input**: Design documents from `/specs/057-system-settings/`（plan.md / spec.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: 包含（CLAUDE.md 强制新特性须有测试；按「先写测试→红→实现」TDD 推进，053 既有测试随签名变更同步更新）。

**Organization**: 按用户故事分组（US1 后端全局化 / US2 前端配置 tab+左右布局 / US3 测试连接+脱敏），每故事可独立实现与测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 归属用户故事（US1/US2/US3）；Setup/Foundational/Polish 阶段无 story 标签
- 描述含确切文件路径

## Path Conventions

Web app：`backend/`（Java 25 / Spring Boot 4，四模块 DDD）+ `frontend/`（Next.js 16 / React 19 / shadcn base）。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 建立绿色起点，确认改动前基线可编译。

- [x] T001 Verify clean baseline before any change: 后端 `cd backend && ./mvnw -q compile` 零错误 + 前端 `cd frontend && pnpm typecheck` 零错误（绿色起点；后续每步按 CLAUDE.md 再验证）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 把 AI Agent 配置的**数据底座**从「按项目」改造为「租户全局单例」——US1 的行为层与 US3 的测试/脱敏都建在其上。

**⚠️ CRITICAL**: US1 行为层（service/enricher/controller）不得在本阶段完成前开工。

- [x] T002 [P] DDL：`lineage_agent_config` 去 `project_id` 列、UNIQUE `uk_lineage_agent_config_tp` 改为 `(tenant_id, deleted)`；`schema_version` 0.12.0 → **0.13.0**（文件头注释 + `INSERT INTO schema_version` 行，三处同步）—— `backend/dataweave-api/src/main/resources/schema.sql`（§~L1195 + §L15 注释 + §schema_version INSERT）。`lineage_agent_call` **不动**（保留 project_id 审计溯源）。
- [x] T003 domain record 去 `projectId` 字段（含构造位置同步）—— `backend/dataweave-master/src/main/java/com/dataweave/master/domain/lineage/LineageAgentConfig.java`
- [x] T004 repository 改造：新增 `findActiveByTenant(tenantId)`；`update/insert` 去 `projectId` 参数；`map(rs)` 去 `project_id` 读取；**`insertCall/findCalls` 保留 `projectId`**（审计按项目/任务溯源，FR-011）—— `backend/dataweave-master/src/main/java/com/dataweave/master/infrastructure/lineage/AgentConfigRepository.java`（依赖 T003）

**Checkpoint**: 数据底座就绪（全局单例存储）—— US1 行为层可开工。

---

## Phase 3: User Story 1 - 全局唯一的 AI Agent 配置 (Priority: P1) 🎯 MVP

**Goal**: 全租户一份全局 AI Agent 配置，血缘智能抽取在任一项目都读这份全局配置；端点迁至 `/api/settings/agent-config`（去 projectId、admin 门）。

**Independent Test**: 经 `/api/settings/agent-config` 配好并启用一份全局配置后，在一个从未单独配过的项目 push 一个需 AI 抽取的脚本任务 → 血缘图谱短时内出现「AI 推断」边（全局配置对该项目生效）。

> ⚠️ US1 把端点从 `/api/lineage/agent-config` 迁走后，旧「血缘工具栏 AI Agent 弹窗」会立即失效。**US1 与 US2 必须一起交付**（中间不部署），由 US2 首个任务移除旧入口、接上新 UI，避免向用户暴露失效弹窗。

### Tests for User Story 1（先写→红→实现）

- [x] T005 [P] [US1] 扩展/重写 controller 契约测试为全局语义：`GET/PUT /api/settings/agent-config`（无 projectId）、`POST /test`、`GET /calls`；admin 门（非 admin 拒绝，对齐 `/api/users`）—— `backend/dataweave-api/src/test/java/com/dataweave/api/LineageAgentConfigControllerIT.java`（原 053 项目级用例改全局）
- [x] T006 [P] [US1] service 单例测试：tenant 单例 upsert（同租户第二条=update）/ 脱敏 `sk-…xxxx` / apiKey 留空=不改 / 缺失或 enabled=0 → 旁路 —— `backend/dataweave-master/src/test/java/com/dataweave/master/application/lineage/agent/AgentLineageConfigServiceTest.java`（新建或扩展）

### Implementation for User Story 1

- [x] T007 [US1] service 去 projectId：`get/getActive/isEnabledFor/upsert` 改 `(tenantId[, userId, req])`（tenant 单例语义；校验/加密/脱敏逻辑不变）—— `backend/dataweave-master/src/main/java/com/dataweave/master/application/lineage/agent/AgentLineageConfigService.java`（依赖 T004）
- [x] T008 [P] [US1] enricher 调用点 `:162` 改读全局：`configService.getActive(ev.tenantId())`（projectId 仅用于审计落 `lineage_agent_call`）—— `backend/dataweave-master/src/main/java/com/dataweave/master/application/lineage/agent/LineageAgentEnricher.java`
- [x] T009 [P] [US1] extractor 调用点 `:72` 改读全局：`configService.getActive(source.tenantId())`—— `backend/dataweave-master/src/main/java/com/dataweave/master/application/lineage/agent/AgentLineageExtractor.java`
- [x] T010 [US1] controller 迁移：`@RequestMapping("/api/settings/agent-config")`；各方法去 `projectId` 参数与 `ProjectScope.require`，改用 `TenantContext.tenantId()`；admin 鉴权复用 `/api/users` 同款门（R1，实现时确认机制并补 T005 的非 admin 拒绝用例）；`/test` + `/calls` 同步迁—— `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/LineageAgentConfigController.java`（依赖 T007）
- [x] T011 [US1] PG 数据迁移脚本（R2）：择一最近更新的每租户配置提升为全局、其余软删留痕，不静默丢失 —— `backend/scripts/migrate-agent-config-global.sql`（H2/dev 由 DDL 重建无需此脚本；附 README 说明）
- [x] T012 [US1] 后端编译 + US1 测试绿：`cd backend && ./dev-install.sh` 后跑 `AgentLineageConfigServiceTest` + `LineageAgentConfigControllerIT` + `AgentLineageExtractorTest` + `LineageAgentEnricherIT` 全绿（零回归 vs 053）

**Checkpoint**: US1 独立可用——全局配置经 API 配好即对所有项目生效；enricher/extractor 读全局；admin 门生效。⚠️ 此时旧工具栏弹窗已失效，须紧接着完成 US2。

---

## Phase 4: User Story 2 - 系统设置新增「配置」Tab：左导航 + 右内容 (Priority: P1)

**Goal**: `SettingsView` 顶部新增「配置」tab（既有 用户/角色/项目 不动，tab 条顺手迁合规 `Tabs` 组件）；该 tab 内部左导航+右内容（数据开发风格），AI Agent 为首个分区，外壳可复用。

**Independent Test**: 具 `project:manage` 权限进入系统设置 → 看到「配置」tab → 切入见左导航「AI Agent」分区 → 选中右侧呈现内联表单 → 完成一次保存。

### Tests for User Story 2（先写→红→实现）

- [x] T013 [P] [US2] 注册表纯函数测试：`CONFIG_SECTIONS` 的 `id` 唯一性 + `filterVisibleSections(permissions)` 过滤正确（仿 `nav-groups.test.ts`）—— `frontend/lib/workspace/settings/config-sections.test.ts`

### Implementation for User Story 2

- [x] T014 [P] [US2] 注册表：`ConfigSection` 类型 + `CONFIG_SECTIONS`（首项 `ai-agent`，titleKey `settingsView.configSectionAiAgent`，icon 用合适 hugeicons）+ `filterVisibleSections` —— `frontend/lib/workspace/settings/config-sections.ts`
- [x] T015 [P] [US2] i18n：`settingsView` 命名空间加「配置」tab + `configSectionAiAgent` + 表单文案（尽量复用既有 `lineageAgent.*` key）；zh-CN / en-US 双 bundle 同步 —— `frontend/messages/zh-CN.json` 与 `frontend/messages/en-US.json`
- [x] T016 [US2] 左导航组件：`DwScroll` 内分区列表、选中态 `bg-muted font-medium text-foreground`、未选中 `text-muted-foreground hover:text-foreground hover:bg-muted/50`、hugeicons + 本地化标题、**无手写分割线** —— `frontend/components/workspace/views/settings/config-nav.tsx`
- [x] T017 [US2] AI Agent 分区内联表单：复用 `AgentConfigPanel` 字段/test/save 逻辑（非 Dialog）；`DropdownSelect`(协议) / `Input`(baseUrl,model,apiKey password) / `Input type=number`(timeoutMs,rateLimitPerMin,maxColumns 大范围→Input，非 Stepper) / `Switch`(enabled) / 测试结果反馈（语义色+hugeicons） —— `frontend/components/workspace/views/settings/ai-agent-config-section.tsx`
- [x] T018 [US2] 可复用外壳：左可拖拽调宽卡片（`motion`+localStorage，复用 `DataDevIdeShell` catalog 宽度模式）+ 右圆角卡片内容区（`DwScroll`+`--card-spacing`，`flex h-full gap-3 p-3`，**无区域分割线**）；自管 `activeSectionId`（localStorage 记忆），右侧渲染选中分区 `component` —— `frontend/components/workspace/views/settings/config-shell.tsx`（依赖 T014/T016/T017）
- [x] T019 [US2] settings-view 改造：手写下划线 tab 条迁到 `Tabs` 组件（下划线式，DESIGN 合规，R3）+ 加第 4 个「配置」tab → `<ConfigShell/>`；既有 用户/角色/项目 三个 tab **内容/行为不变** —— `frontend/components/workspace/views/settings-view.tsx`
- [x] T020 [P] [US2] lineage-api retarget：`getAgentConfig/putAgentConfig/testAgentConfig` 改路径 `/api/settings/agent-config`、去 projectId 参数与类型；`getCalls` 同步 —— `frontend/lib/lineage-api.ts`
- [x] T021 [US2] 移除旧入口（FR-015，承接 US1 端点迁移）：删 `agent-config-panel.tsx`；从 `lineage-toolbar.tsx` 移除其触发按钮；单一真相源 = 系统设置 → 配置 → AI Agent —— `frontend/components/workspace/views/lineage/agent-config-panel.tsx`（删）、`frontend/components/workspace/views/lineage/lineage-toolbar.tsx`（改）
- [x] T022 [US2] 前端 typecheck + 浏览器验证：`cd frontend && pnpm typecheck` 零错误；浏览器验证配置 tab 渲染、左导航选中切换、可拖拽调宽、表单 test/save、脱敏回显（quickstart 步骤 1-5）

**Checkpoint**: US1 + US2 一起交付完成——全局配置在系统设置 UI 可管、所有项目生效、旧入口已收口、无失效弹窗暴露。

---

## Phase 5: User Story 3 - 连通性测试与凭据脱敏 (Priority: P2)

**Goal**: 测试连接（成功带延迟/失败带脱敏原因）、凭据脱敏回显、留空=不改的端到端验收 + 未配置空态引导。（/test 端点与脱敏机制复用 053，US1 已迁至全局；本阶段聚焦验收与 UX 保障。）

**Independent Test**: 在 AI Agent 分区填配置 → 测试连接即时返回成功(延迟)/失败(脱敏原因)；保存含凭据后重开 → 凭据掩码呈现、明文不可见；编辑留空保存 → 原密钥不变。

### Tests & UX for User Story 3

- [x] T023 [P] [US3] 端到端验收测试：`POST /api/settings/agent-config/test` 成功(ok+latencyMs)/失败(ok=false+脱敏 note)；GET 脱敏 `apiKeyMasked`；PUT apiKey 留空=保留旧密文 —— 扩展 `LineageAgentConfigControllerIT.java`（或新增 `AgentConfigTestEndpointIT.java`）
- [x] T024 [US3] 空态引导：AI Agent 分区尚未配置时，右侧呈现明确空态 +「去配置」引导（spec Edge Case），非空白 —— `frontend/components/workspace/views/settings/ai-agent-config-section.tsx`
- [ ] T025 [US3] 浏览器验收 US3 场景：测试连接成功/失败 + 延迟、脱敏重开、留空不改、未配置空态（quickstart 步骤 4-5 + 空态）

**Checkpoint**: US3 保障层完成——配置「可用且敢用」，测试/脱敏/留空语义端到端通过。

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 跨故事收口、合规自查、回归。

- [x] T026 [P] i18n CI 自查：zh-CN / en-US 双 bundle key 集合完全一致、所有 `t("key")` 静态可解析（CI 已校验，本地 `pnpm typecheck` 复核）
- [x] T027 [P] 设计合规自查：走 `specs/037-shared-ui-kit/contracts/reuse-first-checklist.md`「实现前/后」清单 + `pnpm design:lint`；确认顶部 tab 用 `Tabs` 组件、左导航是 nav rail（非 Tabs）、`DwScroll`/`--card-spacing`/语义 token、无区域分割线、无裸色值/`dark:`
- [x] T028 后端全量回归：`cd backend && ./dev-install.sh` + 受影响模块测试全绿（agent/lineage 系列 + grounding 不退化）；零回归 vs 053/055
- [x] T029 前端全量回归：`cd frontend && pnpm typecheck` + `pnpm test`（vitest）全绿
- [ ] T030 跑 `quickstart.md` 端到端 9 步手测全过（含 admin/非 admin 可见性、全局生效、禁用旁路、旧入口已移除）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始。
- **Foundational (Phase 2)**: 依赖 Phase 1；**阻塞** US1 行为层。
- **US1 (Phase 3, MVP)**: 依赖 Phase 2（数据底座）。
- **US2 (Phase 4)**: 依赖 US1 的 API 契约（契约优先可并行起手纯前端件：注册表/外壳/nav/i18n）；**US2 必须与 US1 一起交付**（US1 迁走旧端点会使旧工具栏弹窗失效，US2 首任务 T021 移除旧入口接新 UI，中间不部署）。
- **US3 (Phase 5)**: 依赖 US1（/test 全局端点）+ US2（表单 UX）。
- **Polish (Phase 6)**: 依赖所有用户故事完成。

### User Story Dependencies

- **US1 (P1, MVP)**: Phase 2 完成后即可起手；不依赖其他故事。可独立验证（API 配置 + push 看 AI 边）。
- **US2 (P1)**: 纯前端件（T014/T015/T016/T017/T020）可与 US1 并行；集成（T018/T019/T021/T022）需 US1 API 就绪。与 US1 成对交付。
- **US3 (P2)**: 依赖 US1+US2；验收导向。

### Within Each User Story

- 先写测试→红→实现（053 既有测试随签名变更同步更新）。
- Foundational: DDL → domain record → repository（顺序）。
- US1: service → (enricher ∥ extractor) → controller → 迁移脚本 → 测试绿。
- US2: 注册表/i18n/api(并行) → nav → section → shell → settings-view → 移除旧入口 → 验证。

### Parallel Opportunities

- Phase 2: T002(SQL) 与 T003(record) 可并行（不同文件）；T004 依赖 T003。
- US1: T005 ∥ T006（测试，不同文件）；T008 ∥ T009（enricher/extractor 调用点，不同文件）。
- US2: T014 ∥ T015 ∥ T020（注册表/i18n/lineage-api，不同文件）；T013 测试可与 T014 并行。
- US3: T023 测试可与 T024 空态并行。

---

## Parallel Example: User Story 2

```text
# 并行起手纯前端件（不依赖 US1 API）：
Task: "T014 注册表 config-sections.ts"
Task: "T015 i18n messages/{zh-CN,en-US}.json"
Task: "T020 lineage-api.ts retarget"

# 并行测试 + 组件：
Task: "T013 注册表测试 config-sections.test.ts"
Task: "T016 左导航 config-nav.tsx"
```

---

## Implementation Strategy

### MVP First (US1 + US2 成对)

1. Phase 1 基线绿 → Phase 2 数据底座（全局单例存储）。
2. Phase 3 US1：全局配置 API + enricher/extractor 读全局 + admin 门。
3. **立刻** Phase 4 US2：系统设置 UI + 移除旧入口（与 US1 成对交付，避免失效弹窗）。
4. **STOP 验证**：US1+US2 端到端——系统设置配好一份全局配置，任一项目 push 出 AI 边。
5. （可在此 demo/部署。）

### Incremental Delivery

1. Setup + Foundational → 全局存储就绪。
2. US1 + US2（成对）→ 全局配置可管可用（MVP）。
3. US3 → 测试/脱敏保障层。
4. Polish → 合规自查 + 全量回归 + quickstart 验收。

---

## Notes

- [P] = 不同文件、无未完成任务依赖。
- [Story] 标签映射到 spec.md 用户故事，可追溯。
- **US1 ⇄ US2 成对交付**（端点迁移与旧 UI 移除耦合，中间不部署）。
- 每个任务或逻辑组后提交；checkpoint 处停下做独立验证。
- 后端每步 `./mvnw -q -pl <module> compile` 零错误；前端每步 `pnpm typecheck` 零错误（CLAUDE.md）。
- 避免：模糊任务、同文件冲突、破坏故事独立性的跨故事依赖。
