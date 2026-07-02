# Tasks: 统一技术债收口（Weft 掉头松散端一次性清理）

**Feature**: `014-tech-debt-closure` | **Branch**: `014-tech-debt-closure`
**Input**: [spec.md](./spec.md) · [plan.md](./plan.md) · [research.md](./research.md) · [data-model.md](./data-model.md) · [quickstart.md](./quickstart.md)

> 本特性是清理/自洽性收口：零新增运行期能力、零 API 契约、零 DB schema、零内核改动。主体在 `frontend/`（US1+US2），辅以后端一处目录删除（US3）与两处文档（US4）。
> 无新增单测代码（清理类特性）；验证 = `pnpm typecheck` + i18n 键平价 + 浏览器中英 locale 目检 + 后端 `./dev-install.sh` 构建通过。**证伪式核验：禁信"全绿"自述，真跑真看真数。**
> 实现交外部 AI——每个任务自包含、带确切路径与判据，可无额外上下文执行。

---

## Phase 1：Setup

- [ ] T001 确认工作分支为 `014-tech-debt-closure`（`git branch --show-current`）；阅读 `frontend/DESIGN.md` 并记下采纳的前端栈门约束（base-style `render` 非 `asChild`、hugeicons、语义 token、`gap-*`/`size-*`、无手写 `dark:`）——US1/US2 触前端，改前必读。

---

## Phase 2：Foundational（阻塞性前置：定位全部触点）

> 仅一项——US1 的精确触点台账。先全量定位再动手，避免漏删/误删。

- [ ] T002 在 `frontend/` 全仓 grep 举手台符号定位所有触点，产出删/改/核查台账：
  ```bash
  grep -rn "useOpsAlertsStore\|ops-alerts-store\|ops-alert-card\|OpsAlert\|__MOCK_OPS_ALERT__\|useMockAlertInjector\|举手台" frontend/ | grep -v "/specs/"
  ```
  对每个命中分类：① 举手台专属代码（删）；② ops-view 内的 rail/mock 注入（改）；③ 其它文件的引用（逐一判定：真引用举手台符号→清理；偶然同词→不动）。台账用于 US1 各任务。

**Checkpoint**：触点台账完整，US1 可据此精确执行。

---

## Phase 3：User Story 1 — Agent 举手台移除 (P1) 🎯 MVP

**Goal**：彻底移除 ops 视图右栏服务端-AI 时代残留的"Agent 举手台"告警 rail 及其全部支撑代码与悬空引用，ops 视图诚实呈现真实能力、布局完整。

**Independent Test**：浏览器开 ops 视图右栏无举手台、布局完整无空洞；`pnpm typecheck` 零错；全仓对已删举手台符号零引用。

- [x] T003 [P] [US1] 删除举手台告警卡片组件 `frontend/components/workspace/views/ops/ops-alert-card.tsx`（整文件）。
- [x] T004 [P] [US1] 删除举手台告警状态 store `frontend/lib/workspace/ops-alerts-store.ts`（整文件，含 `OpsAlert` 类型导出）。
- [x] T005 [US1] 改 `frontend/components/workspace/views/ops-view.tsx`：移除右栏举手台 rail 渲染块（含对 `ops-alert-card`/`useOpsAlertsStore` 的 import 与 `alerts` 订阅）、移除 `useMockAlertInjector` 及 `window.__MOCK_OPS_ALERT__` mock 注入逻辑（`useOpsAlertsStore.getState().push(...)`）；移除右栏后调整布局容器使主舞台自然回填（保 INV-1：不伤顶条今日大盘/主舞台 Tab，保 FR-004 布局完整）。
- [x] T006 [US1] 按 T002 台账逐一核查并清理其余文件的举手台悬空引用——候选 `frontend/components/workspace/views/ops/backfill-dialog.tsx`、`frontend/components/side-panel/side-panel.tsx`、`frontend/components/workspace/views/workflow-canvas-view.tsx`、`frontend/app/layout.tsx`：真引用举手台 store/类型/mock 的→删除该引用及其死代码；grep 偶然同词（如无关的 "alert" 文案）→不动。判据：清理后无悬空 import/类型/调用。
- [x] T007 [US1] 验证 US1 闭合：`cd frontend && pnpm typecheck`（期望 0 error）；再跑 `grep -rn "useOpsAlertsStore\|ops-alerts-store\|ops-alert-card\|__MOCK_OPS_ALERT__\|useMockAlertInjector" frontend/ | grep -v "/specs/"`（期望无输出）。任一不达标→回到 T005/T006 修，禁跳过。

**Checkpoint**：US1 独立可交付——举手台代码清零、typecheck 绿、零悬空引用。

---

## Phase 4：User Story 2 — ops 实例面板硬编码中文 i18n 收口 (P1) 🎯 MVP

**Goal**：把 ops 区漏迁的硬编码中文 UI 文案完整迁入 next-intl 双语言 bundle，随 UI locale 切换、键平价。

**Independent Test**：英文 locale 下 ops 实例面板批量动作与提示文案全英文、无中文残留；键平价校验通过；ops 区无遗漏硬编码中文 UI 串。

> 与 US1 无文件冲突（US2 改实例面板 + messages bundle，US1 改 ops-view/删举手台），可与 US1 并行。

- [ ] T008 [US2] 对 ops 区做硬编码中文 UI 文案**全量 sweep**，产出待迁清单：扫描 `frontend/components/workspace/views/ops/` 全部文件 + `frontend/components/workspace/views/ops-view.tsx`，识别所有未走 `t()` 的静态 UI 文案——含 JSX 文本、按钮文案，以及 `title`/`aria-label`/`placeholder` 等非正文文本属性；排除注释行与数据术语（cron/DAG/SLA/lineage/OOM 保持英文）。已知基线 5 处：`workflow-instances-panel.tsx` 的「最多选中 100 个实例」「批量重跑」「批量置成功」「批量停止」、`periodic-instances-panel.tsx` 的「最多选中 100 个实例（当前 {ids.length} 个）」。清单须覆盖但不限于这 5 处。
- [x] T009 [US2] 在 `frontend/messages/zh-CN.json` 与 `frontend/messages/en-US.json` 的 ops 命名空间下，为 T008 清单每条文案新增键（**两 bundle 同键集**）：中文用原文案，英文用对应翻译；含动态数量的（选择上限"当前 N 个"）用 ICU `{count}` 占位，英文语序自然（如 `Selected {count} of max 100`）。数据术语保持英文不译。
- [x] T010 [US2] 改 `frontend/components/workspace/views/ops/workflow-instances-panel.tsx`：将 4 处硬编码中文（含 T008 在本文件 sweep 出的任何额外项）替换为 `t("...")` 引用，键对应 T009 新增项。
- [ ] T011 [US2] 改 `frontend/components/workspace/views/ops/periodic-instances-panel.tsx`：将「最多选中 100 个实例（当前 {ids.length} 个）」（含 T008 在本文件 sweep 出的任何额外项）替换为 `t("...", { count: ids.length })` ICU 引用，键对应 T009 新增项。
- [ ] T012 [US2] 若 T008 在 ops 区其它文件（如 `ops-view.tsx`/`backfill-dialog.tsx` 等留存文件）扫出额外硬编码中文，一并替换为 `t()` 引用并补 T009 键。（US1 待删的举手台文件不在此列——随删消失。）
- [ ] T013 [US2] 验证 US2 闭合：`cd frontend && pnpm typecheck`（0 error，确认每处 `t("key")` 可静态解析）；`pnpm lint`（含 i18n 键平价 CI，期望两 bundle 同键集通过）；人工 re-sweep ops 区确认无残留硬编码中文 UI 串。任一不达标→修，禁弱化校验。

**Checkpoint**：US2 独立可交付——ops 文案随 locale 切换、键平价、零中文残留。

---

## Phase 5：User Story 3 — dataweave-alert 死目录清理 (P2)

**Goal**：删除后端 alert 死目录（未跟踪构建残骸），后端构建不受影响。

**Independent Test**：目录删除后 `./dev-install.sh` 一次构建通过，根 pom modules 不变。

> 与 US1/US2/US4 无交集，可全程并行。

- [ ] T014 [P] [US3] 删除 `backend/dataweave-alert/` 整目录（`rm -rf backend/dataweave-alert`）。前置核查（已在 research 确认，执行前快速复核）：`git ls-files backend/dataweave-alert/` 为空、`grep -c "dataweave-alert" backend/pom.xml` 为 0——确保是未跟踪残骸且非根模块成员。
- [ ] T015 [US3] 验证 US3 闭合：`cd backend && ./dev-install.sh`（期望 BUILD SUCCESS，master/worker/api 全编译安装；WSL2 慢则按 CLAUDE.md `setsid` 脱离 + 单次秒回轮询，禁前台 sleep 循环）；`grep "<module>" backend/pom.xml` 确认仍为 master/worker/api 三项不变。

**Checkpoint**：alert 目录消失、后端构建一次通过、modules 不变。

---

## Phase 6：User Story 4 — push 血缘缺口文档化 (P3)

**Goal**：把"push 不落表级血缘=有意延迟"明确写进架构文档与代码导航，杜绝被误当 bug。无代码改动。

**Independent Test**：架构文档血缘小节有明确记载；CLAUDE.md 血缘导航行有注记；零运行期代码 diff。

> 与 US1/US2/US3 无交集，可全程并行。

- [x] T016 [P] [US4] 在 `docs/architecture.md` 血缘小节增补一段：明确"`push` 路径当前**不建**表级血缘（现 JDBC 二部图血缘仅设计时 `createAndOnline`/`recordDesignTimeIo` 建立）；这是**有意延迟**，理由是血缘体系将于发布期改用图数据库 neo4j 重做，届时连同 push 路径血缘一并实现，现补 JDBC 过渡实现会被推倒重写；归宿=发布期 neo4j"。
- [ ] T017 [P] [US4] 在 `CLAUDE.md` 的血缘导航行（"Table lineage (build-as-you-create)" 那行）加一句注记：标明"push 路径不落血缘（有意延迟，发布期 neo4j 重做）——详见 docs/architecture.md 血缘小节 + specs/014"。
- [ ] T018 [US4] 验证 US4 闭合：`git diff --stat` 确认本 US 仅触 `docs/architecture.md` 与 `CLAUDE.md`、无任何 `.java`/`.ts`/`.go` 运行期代码改动（FR-014）。

**Checkpoint**：缺口可见、可追溯、有定论；零代码改动。

---

## Phase 7：整体收尾验收（Polish & Cross-Cutting）

> 跑 [quickstart.md](./quickstart.md) 全量复跑。证伪式：真跑真看。

- [ ] T019 前端整体验收：`cd frontend && pnpm typecheck`（0 error）+ `pnpm lint`（i18n 键平价通过）+ 全仓举手台符号零引用复核（SC-002/SC-004）。
- [ ] T020 浏览器中英 locale 双验（SC-001/SC-003）：admin/admin 拿 JWT 注入 `localStorage['dw.auth.token']`，深链 `/?open=ops`——① 右栏举手台消失、顶条+主舞台布局完整；② 任务流实例面板多选触发批量动作区，中文 locale 显示原中文、英文 locale 全英文无中文残留、动态数量正确嵌入。截图留证。
- [ ] T021 后端构建验收（SC-005）：`cd backend && ./dev-install.sh` 一次通过、根 pom modules 仅 master/worker/api（WSL2 脱离规则同 T015）。
- [ ] T022 文档与红线终检（SC-006/SC-007）：架构文档+CLAUDE.md 血缘缺口记载到位；`git diff` 通览确认全程零 DB schema 变更、零同步 API（pull/push/diff）契约改动、零调度/权限内核改动、零文件契约语义改动。
- [ ] T023 更新记忆与收口：在记忆库记录本次技术债收口结论（举手台移除/alert 死目录删除/ops i18n 补齐/血缘缺口 defer neo4j 的裁定），链接相关既有记忆。

---

## Dependencies & 执行顺序

```
Phase 1 (T001 Setup)
        ↓
Phase 2 (T002 触点台账) ── 仅 US1 依赖
        ↓
┌─────────────────────────────────────────────┐
│ US1 (T003-T007)   US2 (T008-T013)            │  ← MVP，两者无文件冲突可并行
│ US3 (T014-T015)   US4 (T016-T018)            │  ← 与 MVP 及彼此全并行
└─────────────────────────────────────────────┘
        ↓
Phase 7 (T019-T023 整体验收+收口) ── 依赖以上全部
```

- **US1 内部**：T003/T004（删文件，[P] 并行）→ T005（改 ops-view）→ T006（清理引用）→ T007（验证）。
- **US2 内部**：T008（sweep）→ T009（补键）→ T010/T011/T012（替换引用）→ T013（验证）。
- **US3 内部**：T014（删）→ T015（验证）。
- **US4 内部**：T016/T017（[P] 两文档并行）→ T018（验证）。
- **跨 US**：US1/US2/US3/US4 相互无依赖（US2 只在 US1 待删的举手台文件内不取文案——T012 已排除）。Phase 7 依赖全部完成。

## Parallel Execution 示例

- 启动即可并行：T003 + T004（US1 删文件）、T014（US3 删目录）、T016 + T017（US4 文档）——分属不同文件、互不依赖。
- US1 与 US2 可由两个外部 agent 并行推进（US1 动 ops-view/举手台文件，US2 动实例面板/messages bundle，无重叠）。

## Implementation Strategy

- **MVP = US1 + US2**：用户可见的自洽（死 UI 清掉 + ops i18n 完整）。先交付这两条即达本特性核心价值。
- **增量收尾 = US3 + US4**：低风险、可并行、随时插入。
- **证伪式收口（我兜底）**：外部 AI 实现后，我亲跑 typecheck/键平价/浏览器中英目检/dev-install，读真实输出，禁信自述"全绿"；US1 重点验"零悬空引用 + 布局完整不伤可观测性"，US2 重点验"英文 locale 零中文残留 + 键平价"，US4 重点验"零代码 diff"。
