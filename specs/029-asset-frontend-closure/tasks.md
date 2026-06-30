# Tasks: 资产目录 / 指标市场前端收口

**Input**: Design documents from `specs/029-asset-frontend-closure/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/
**Worktree**: 全部任务在隔离 worktree `/home/wallfacers/project/dw-029-asset-frontend` 内执行（分支 `029-asset-frontend`）。**勿回 main 工作副本**（外部 agent 占用）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无依赖,可并行
- **[Story]**: US1/US2/US3/US4，对应 spec 用户故事
- 路径相对 worktree 根

## 测试策略（项目规范对齐）

项目**无 jsdom/RTL 组件测试基建**（现有 vitest 全为 `lib/` 纯逻辑单测）。本特性**不引入** RTL：
- **vitest 单测**：把决策逻辑抽成纯函数（三态分流、PATCH-diff、搜索查询构建、订阅判定、上架载荷）单测。
- **浏览器手验**：Dialog/视图交互行为由 `quickstart.md` 14 步闭环覆盖（即本特性的集成测试）。
- 测试**先写令其失败**，再实现令其通过。

---

## Phase 1: Setup

- [X] T001 在 worktree `frontend/` 跑基线绿：`pnpm install` → `pnpm typecheck` → `pnpm test`，并确认 shadcn `dialog/select/input/checkbox/pagination/badge` 均已存在（无需 add）。记录基线通过。

---

## Phase 2: Foundational（阻塞 US1–US3 写操作,必先完成）

**⚠️ 完成前任何写侧用户故事不能开工。**

- [X] T002 [P] 写单测（先行,失败）`frontend/lib/gate-outcome.test.ts`：覆盖 `outcome=EXECUTED→done / PENDING_APPROVAL→pending / REJECTED 或 code≠0→失败取后端 message`，以及已知 `errorCode` 映射（`catalog.duplicate_asset`/`catalog.asset_invalid`/`catalog.listing_invalid`/`catalog.reuse_cycle`/`catalog.reuse_invalid`/`catalog.not_certifiable`/`catalog.forbidden_sensitivity`）。
- [X] T003 实现 `frontend/lib/gate-outcome.ts`：纯函数 `resolveGate(res, t) → {kind, message}`，令 T002 通过。**严守三态如实**（FR-012/SC-005，不把 PENDING 当成功）。
- [X] T004 扩展 `frontend/lib/catalog-api.ts`：新增 `updateAsset(id,patch)`(PATCH)、`reconcileAsset(id)`、`unsubscribe(subId)`、`listSubscriptions()`、`listMetric(body)`、`delistMetric(id)`；`AssetSearchParams` 增 `status?`/`qualityMin?`；核对 `searchListings` 已含 `certification?`/`page?`。镜像 contracts/。（单文件,非 [P]）
- [X] T005 [P] 新建通用确认 Dialog `frontend/components/workspace/views/shared/confirm-dialog.tsx`（title/description/confirmLabel/onConfirm，复用于下线/对账/下架/退订）。

**Checkpoint**: 客户端能力 + 三态 helper + 确认框就绪,用户故事可开工。

---

## Phase 3: User Story 1 - 资产全生命周期 (P1) 🎯 MVP

**Goal**: 在资产目录界面完成 编目→编辑→下线→对账 全过程。
**Independent Test**: quickstart 步骤 1–5；新建+重复拒绝+部分编辑+对账状态翻转+下线全可单独验证。

### Tests（先行,失败）

- [X] T006 [P] [US1] 单测 `frontend/lib/asset-patch.test.ts`：PATCH-diff 工具——仅含与初值不同的键；显式清空（→null/空）与未触键（不出现）区分正确。

### Implementation

- [X] T007 [US1] 实现 `frontend/lib/asset-patch.ts`：`diffPatch(initial, current) → Partial`，令 T006 通过（编辑只提交改动键）。
- [X] T008 [P] [US1] 新建 `frontend/components/workspace/views/asset/asset-form-dialog.tsx`：创建+编辑共用 Dialog；数据源 Select（`lib/datasource-api`）；字段按 data-model（qualifiedName 必填、sensitivity Select、tags、lineageTableRef 等）；编辑用 `diffPatch`；提交经 `createAsset`/`updateAsset` + `resolveGate`。
- [X] T009 [US1] `asset-catalog-view.tsx` 列表头加「编目资产」按钮 → 开 create 模式 dialog → 成功刷新列表/total；`catalog.duplicate_asset`/`catalog.asset_invalid` 经 resolveGate 显具体提示。
- [X] T010 [US1] `asset-catalog-view.tsx` 详情面板加「编辑」→ 开 edit 模式 dialog（预填 selected）。
- [X] T011 [US1] `asset-catalog-view.tsx` 详情面板加「下线」「对账」→ `confirm-dialog` → `retireAsset`/`reconcileAsset` + resolveGate → 成功重新 `fetchAsset` 回填新 status（对账提示判据：lineageTableRef 缺失→STALE）。
- [X] T012 [P] [US1] i18n：`frontend/messages/zh-CN.json` + `en-US.json` 补 `assetCatalog` 下创建/编辑/下线/对账/确认/已知错误 key（两 bundle 同集；数据术语英文）。

**Checkpoint**: US1 独立可用 = MVP。

---

## Phase 4: User Story 2 - 指标上架 / 下架 (P1)

**Goal**: 上架既有指标定义到市场、下架；复用成环专门提示。
**Independent Test**: quickstart 步骤 6–9。

### Tests（先行,失败）

- [X] T013 [P] [US2] 单测 `frontend/lib/metric-listing.test.ts`：从 `MetricCard` 选择构建上架载荷 `{metricId, metricType, metricCode, description, freshnessInfo}` 正确（metricId 必填）。

### Implementation

- [X] T014 [US2] 实现 `frontend/lib/metric-listing.ts`：上架载荷构建器,令 T013 通过。
- [X] T015 [P] [US2] 新建 `frontend/components/workspace/views/metric/metric-listing-dialog.tsx`：拉 `GET /api/metrics`（MetricCard）做指标定义 Select + metricType/description/freshnessInfo；提交经 `listMetric` + resolveGate。
- [X] T016 [US2] `metric-marketplace-view.tsx` 列表头加「上架指标」按钮 → 开 listing dialog → 成功刷新列表。
- [X] T017 [P] [US2] 新建 `frontend/components/workspace/views/metric/metric-reuse-dialog.tsx`：替换现 `window.prompt`；consumerType Select（METRIC/TASK/ASSET）+ consumerRef 输入；提交经 `reuseMetric` + resolveGate，`catalog.reuse_cycle` → 专门「会形成循环依赖」提示（FR-007）。
- [X] T018 [US2] `metric-marketplace-view.tsx` 详情面板加「下架」→ `confirm-dialog` → `delistMetric` + resolveGate → 刷新；把现 `doReuse` 改为打开 reuse dialog。
- [X] T019 [P] [US2] i18n：补 `metricMarketplace` 下上架/下架/复用/consumerType/防环错误 key（两 bundle 同集）。

**Checkpoint**: US1+US2 各自独立可用。

---

## Phase 5: User Story 3 - 订阅生命周期 (P2)

**Goal**: 查看「我的订阅」聚合清单并退订；资产详情内联订阅态。
**Independent Test**: quickstart 步骤 10–11。

### Tests（先行,失败）

- [X] T020 [P] [US3] 单测 `frontend/lib/subscriptions.test.ts`：从订阅列表判定「当前资产是否已订阅」+ 取其 subId 的纯逻辑正确。

### Implementation

- [X] T021 [US3] 实现 `frontend/lib/subscriptions.ts`：`findAssetSubscription(list, assetId)` 等纯逻辑,令 T020 通过。
- [X] T022 [P] [US3] 新建 `frontend/components/workspace/views/asset/subscriptions-dialog.tsx`：`listSubscriptions` 聚合清单 + 退订（`confirm-dialog` → `unsubscribe` + resolveGate）。
- [X] T023 [US3] `asset-catalog-view.tsx` 列表头加「我的订阅」按钮 → 开 subscriptions-dialog（**不**新增顶层 ViewType,clarify 定档）。
- [X] T024 [US3] `asset-catalog-view.tsx` 详情面板：用 `findAssetSubscription` 呈现内联订阅态（已订阅→显示退订入口；未订阅→现有订阅按钮）；打开详情时顺带 `listSubscriptions` 比对。
- [X] T025 [P] [US3] i18n：补 `assetCatalog` 下「我的订阅」/退订/订阅态 key（两 bundle 同集）。

**Checkpoint**: US1–US3 各自独立可用。

---

## Phase 6: User Story 4 - 检索体验补全 (P2)

**Goal**: 分面真过滤（owner/tag/sensitivity/certification；status 仅只读展示）、分页、质量过滤被动透传+静态声明。
**Independent Test**: quickstart 步骤 12–14。

### Tests（先行,失败）

- [ ] T026 [P] [US4] 单测 `frontend/lib/asset-search-query.test.ts`：搜索查询构建器——keyword/sensitivity/owner/tag/qualityMin/page 组装与切换（点选/取消）逻辑正确。**不含 status**（后端无 status 入参,analyze F1）。

### Implementation

- [ ] T027 [US4] 实现 `frontend/lib/asset-search-query.ts`：查询状态构建/切换纯逻辑,令 T026 通过。
- [ ] T028 [US4] `asset-catalog-view.tsx`：**owner/tag** 分面改为可点选真过滤（sensitivity 现已可点；toggle + 已选高亮),经 builder 传 `searchAssets`（现 owner 仅显示→改为可过滤）。**`status` 分面仅只读展示计数,不可点选**（后端搜索无 status 入参且恒排除 RETIRED,analyze F1）。
- [ ] T029 [US4] `asset-catalog-view.tsx`：接 `components/ui/pagination` 接管 page,显示 total + `truncated`「结果已截断」,替换写死 page=1/size=20。
- [ ] T030 [US4] `asset-catalog-view.tsx`：加质量分数下限输入,透传 `qualityMin`;控件旁常驻静态声明「质量数据来自 022 评分卡、当前环境可能为空」（clarify Q2）。注释点明**后端 v1 对 `qualityMin` 为 no-op**（缺 022 评分卡表,`AssetSearchService` 不施加该过滤,analyze F2）,纯前端入口 + 声明,避免后人误判生效。
- [ ] T031 [US4] `metric-marketplace-view.tsx`：certification 分面可点过滤（NONE/CERTIFIED）+ 接分页。
- [ ] T032 [P] [US4] i18n：补分面标签（owner/tag/status/certification）、分页、质量声明 key（两 bundle 同集）。

**Checkpoint**: 全部用户故事独立可用。

---

## Phase 7: Polish & 验收

- [ ] T033 [P] i18n 两 bundle key 集一致校验：跑 i18n:lint（或等价校验）确保每个 `t("key")` 双语可解析,修齐差异。
- [ ] T034 全闸门绿：worktree `frontend/` 跑 `pnpm typecheck` + `pnpm test` + `pnpm design:lint`,零错误（vitest 须 Tests run>0 真跑,非 0 用例假绿）。
- [ ] T035 浏览器手验：按 `quickstart.md` 14 步真跑闭环（admin/admin 注 JWT,catalog+marketplace tab,后端 h2 profile）。失败如实记录+修。
- [ ] T036 零后端/schema 验证：`git diff --stat main..029-asset-frontend` 仅含 `frontend/` + `specs/029-asset-frontend-closure/`（FR-013/SC-007）。

---

## Dependencies & Execution Order

### Phase 依赖
- Setup(P1) → Foundational(P2) **阻塞所有写故事** → US1/US2/US3/US4 → Polish。
- US4（纯检索）技术上不依赖 P2 的写 helper,但仍建议 P2 后做以共享视图改造节奏。

### 用户故事依赖与**共享文件约束**（重要）
- US1/US3/US4 都改 **同一个** `asset-catalog-view.tsx`；US2/US4 都改 `metric-marketplace-view.tsx`。
- 故**跨故事不可并行编辑这两个视图主文件**；按优先级顺序 P1→P2 串行做,天然无冲突。
- 跨故事可并行的是**不同文件**：各 Dialog 子组件（`views/asset/*`、`views/metric/*`、`shared/*`）、各 `lib/*` 纯逻辑 + 其单测、i18n（注意 messages 两文件也是共享文件,标 [P] 的 i18n 任务实际需串行写或合并,见下）。

### [P] 说明
- 标 [P] 的任务 = 不同文件、无未完成依赖。
- **例外**：`messages/{zh-CN,en-US}.json` 被多个 [P] i18n 任务触及（共享文件）→ 若并行需各自不同 key 段并最后合并；稳妥起见按故事顺序串行追加。

---

## Implementation Strategy

### MVP（仅 US1）
1. Phase 1 Setup → 2. Phase 2 Foundational（关键,阻塞）→ 3. Phase 3 US1 → 4. **停下验证** quickstart 步骤 1–5 独立闭环 → 5. 可演示。

### 增量交付
P2 Foundational 就绪后,US1（MVP）→ US2 → US3 → US4,每个故事独立测试、独立增值,互不破坏。

### 并行机会
- P2 内 T002/T005 可并行；各故事内不同文件的 Dialog/util/test [P] 可并行。
- 共享视图主文件与 messages 串行（见上）。

---

## Notes
- 提交粒度：每任务或逻辑组提交一次；commit message 末尾带项目 Co-Authored-By。
- 严守：闸门零旁路、三态如实、零后端改动、i18n 两 bundle 同集、不用 `…` 表进行中。
- 测试先失败再实现；vitest 假绿（0 用例/build-cache 跳过）须警惕（见 CLAUDE.md / maven-build-cache 同理的前端注意）。
