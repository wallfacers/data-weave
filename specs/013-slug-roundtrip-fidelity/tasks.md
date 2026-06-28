# Tasks: 文件契约 slug 唯一性与中文名健壮性修复（round-trip 保真）

**Feature**: 013-slug-roundtrip-fidelity | **Branch**: `013-slug-roundtrip-fidelity`
**Input**: [spec.md](./spec.md) · [plan.md](./plan.md) · [research.md](./research.md) · [data-model.md](./data-model.md) · [contracts/](./contracts/) · [quickstart.md](./quickstart.md)

**测试策略（硬约束）**：测试优先 + 必证伪。先写会**真碰撞**的中文命名夹具测试，再实现到绿。禁 `_skipped`/注释 `@Test`/弱化断言；后端真跑 `-Dmaven.build.cache.enabled=false` 并读 `surefire-reports` 真数、确认 `Skipped:0`。

**MVP**：US1（拉取无损）+ US2（差异忠实不失明）。US3 闭环、US4 已实现。

---

## Phase 1: Setup

- [ ] T001 确认在 `013-slug-roundtrip-fidelity` 分支、工作树含 US4 既有改动（`cli/sync/resolve.go`、`cli/sync/sync_e2e_test.go`）；`cd backend && ./dev-install.sh` 基线可编译

---

## Phase 2: Foundational（阻塞所有 US —— slug 单一真相源）

> 这是 US1/US2/US3 的共同地基：派生+退化+哈希回落+确定性唯一性守卫，单一真相源。

- [ ] T002 在 `backend/dataweave-master/src/test/java/com/dataweave/master/filecontract/naming/SlugRulesTest.java` 写**先行失败**单测：① 退化判定基于无 `[a-z0-9]`（`抽取-拉取订单分区`→退化、`-`→退化、`--`→退化、纯中文→退化、`指标-GMV`→非退化）；② 退化回落 `e<hash>` 确定性（同名两次相等）；③ 同目录多名同 `effective` 时 `uniquify` 按 id 升序产稳定唯一后缀；④ 大小写碰撞沿用守卫；⑤ 输出仅 `[a-z0-9_-]`、非保留名
- [ ] T003 在 `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/naming/EntityNaming.java`（新建，与 `SlugRules` 同包）实现单一真相源：`slugOf(name)`（沿用现有 lower+`[^a-z0-9_-]+`→`_`+压缩+去边）、`isDegenerate(base)=不含[a-z0-9]`、`fallbackHash(name)=e+hex(SHA-256(name))[:N]`、`effectiveSlug(name)`、`uniquify(effective, idForOrdering, siblingTakenSet)` 确定性消歧。复用/扩展 `SlugRules.checkCaseCollisions` 思路
- [ ] T004 跑 `cd backend && ./mvnw -pl dataweave-master test -Dmaven.build.cache.enabled=false -Dtest=SlugRulesTest`，读 surefire 真数到全绿、`Skipped:0`

**Checkpoint**: slug 工具确定性、退化健壮、消歧唯一 —— 地基成立

---

## Phase 3: User Story 1 - 拉取对任意命名无损 (P1) 🎯 MVP

**Goal**: 拉取后服务端实体数 = 本地实体文件数，中文碰撞名各有独立文件，零静默丢失。
**Independent Test**: 中文碰撞夹具 pull → `task.yaml` 数 == 服务端任务数，每实体唯一文件。

- [ ] T005 [US1] 在 `backend/dataweave-master/src/test/java/com/dataweave/master/application/ProjectSyncRoundtripTest.java`（新建，`@ActiveProfiles("h2")`）写**先行失败**测试 `pullIsLossless_withCjkCollidingNames`：构造/复用含多个「类别-中文」同退化名任务（同目录）的项目，断言 pull bundle 的 `*.task.yaml` 数 == 服务端实体数、无 `-.task.yaml` 坍缩、每实体可唯一定位
- [ ] T006 [US1] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/ProjectSyncService.java` 把 `slugify()`（L357）改为委托 `EntityNaming.effectiveSlug`；`taskSlugs`/`workflowSlugs` 装配（L481/485 区域）改为在**同目录兄弟集合**内经 `EntityNaming.uniquify`（按实体 id 升序）产出唯一 slug 填入 map
- [ ] T007 [US1] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/mapping/ProjectMapper.java` 把 `deriveTaskSlug`/`deriveWorkflowSlug`（L431/438）收敛为调用 `EntityNaming`，删除本地正则副本；确认导出装配（L92/104/110/122/144）经 `export.taskSlugs()/workflowSlugs()` 取的是已消歧的唯一 slug
- [ ] T008 [US1] 扩展到目录（CatalogNode 目录名）与标签（Tag）slug：同样经 `EntityNaming` + 同目录唯一性守卫（FR-009），避免目录/标签碰撞坍缩
- [ ] T009 [US1] 跑 `ProjectSyncRoundtripTest#pullIsLossless_withCjkCollidingNames` 到绿（`-Dmaven.build.cache.enabled=false`），确认实体数守恒

**Checkpoint**: US1 可独立交付 —— 拉取无损（SC-001/FR-001/FR-006）

---

## Phase 4: User Story 2 - 差异预览忠实、绝不失明 (P1) 🎯 MVP

**Goal**: 零改动报无差异（且确未丢实体）；删一个恰报一个；绝不因碰撞失明。
**Independent Test**: 刚 pull 零改动 → diff 无差异且步骤证未丢；删一文件 → 恰报该项删除。

- [ ] T010 [US2] 在 `ProjectSyncRoundtripTest.java` 加**先行失败**测试 `diffIsFaithful_notBlind`：零改动工作副本 diff 报无差异**且**实体数守恒（非失明式"一致"）；删除一个中文碰撞任务文件后 diff 恰好列该任务为 removed、其余不误列；改一个内容恰报 modified
- [ ] T011 [US2] 在 `ProjectSyncService.java` 的 diff/push 身份匹配处（L276-340，task 与 workflow 两侧）统一改用 `EntityNaming` + 同目录兄弟集 `uniquify` 产出的唯一身份键，使服务端侧不再对称坍缩；保证 INV-4（导出 slug == 身份 slug，同函数产出）
- [ ] T012 [US2] 跑 `diffIsFaithful_notBlind` 到绿，确认 diff 对碰撞夹具忠实、不失明（SC-003/FR-005）

**Checkpoint**: US1+US2 = MVP 成立 —— 拉取无损 + 差异安全闸可信

---

## Phase 5: User Story 3 - 拉取→推送→再拉取身份稳定 (P2)

**Goal**: pull→(无改动)push→再 pull 到干净目录，两次文件树语义等价，不误删在线工作流引用节点。
**Independent Test**: round-trip 后 `diff -rq` 两副本无差异；push 不误触删除守卫。

- [ ] T013 [US3] 在 `ProjectSyncRoundtripTest.java` 加**先行失败**测试 `roundtripIsStable_noFalseRemoval`：含在线工作流引用全部中文节点的项目，pull→无改动 push→再 pull，断言两次实体集合+内容语义等价、push 阶段无引用节点被误判 removed、不误触删除守卫（SC-002/SC-005/FR-007）
- [ ] T014 [US3] 按测试暴露问题校正 `EntityNaming.uniquify` 的排序稳定性与 INV-3/INV-5（跨 pull 完全相同 slug 集合）；跑该测试到绿

**Checkpoint**: 往返闭合稳定

---

## Phase 6: User Story 4 - CLI 按 code 拉取可用 (P3，已实现，验证收口)

**Goal**: `dw pull <code>` 可用（items 契约）。
**Independent Test**: `go test ./sync` 绿 + 真端点 `dw pull demo` 成功。

- [ ] T015 [US4] 验证 `cli/sync/resolve.go` 读 `data.items[]`、`cli/sync/sync_e2e_test.go` mock 用真契约 `items`（已改）；`cd cli && go test ./sync/` 绿
- [ ] T016 [US4] 真端点回归：起 H2 后端，`dw pull demo` 成功解析并拉取（按 quickstart 步骤 1）

---

## Phase 7: Polish & 收尾验收

- [ ] T017 复跑 quickstart 全量 E2E 闭环（H2 后端 + 真 dw）：`dw pull demo` → `*.task.yaml` 数 == 服务端 20、无 `-.task.yaml`；`dw diff` 零差异；pull→push→pull `diff -rq` 无差异
- [ ] T018 全量回归：`cd backend && ./mvnw -pl dataweave-master test -Dmaven.build.cache.enabled=false` 读 surefire 真数全绿、`Skipped:0`；确认无既有测试因 slug 变更回归
- [ ] T019 更新内存 `weft-slug-collision-roundtrip-break`：标注 013 修复落地、根因/修法/验证结论；必要时更新 MEMORY.md 行
- [ ] T020 收口提交（不推送，等用户确认）：spec/plan/tasks + 实现 + 测试；提交信息含根因与验收证据

---

## Dependencies & 执行顺序

- **Setup(T001) → Foundational(T002-T004) → US1(T005-T009) → US2(T010-T012) → US3(T013-T014)**；US4(T015-T016) 独立可并行（已实现）；Polish(T017-T020) 最后。
- Foundational（EntityNaming 单一真相源）是 US1/US2/US3 的硬前置。
- US2 依赖 US1 的导出唯一性（INV-4 要求导出与身份同源）；US3 依赖 US1+US2。

## Parallel 机会

- T002（测试）与 T003（实现）严格测试优先：T002 先红再 T003。
- T015/T016（US4 验证）可与 Foundational 并行（互不触文件）。
- T008（目录/标签扩展）可在 T006/T007 之后并行于 US2 起步。

## MVP 与增量交付

1. **MVP = Phase 2+3+4**（Foundational + US1 + US2）：拉取无损 + 差异不失明 —— 已解除数据丢失与安全闸失明两个致命点，可演示可发布。
2. 增量 +US3：往返闭合稳定（完整 round-trip 保真）。
3. US4 已随特性落地。
