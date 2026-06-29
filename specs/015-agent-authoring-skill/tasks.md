---
description: "Task list for 015 Weft 任务创作 Skill + dev-loop 体验收口"
---

# Tasks: Weft 任务创作 Skill + dev-loop 体验收口

**Input**: Design documents from `specs/015-agent-authoring-skill/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md
**Tests**: 本特性 spec 明确要求测试（FR-025/026/027，"无测试=未完成"）——含测试任务。
**Organization**: 按用户故事分组，独立可测、可增量交付。

## Format: `[ID] [P?] [Story] Description`
- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1–US5（映射 spec 用户故事）；Setup/Foundational/Polish 无故事标签

## Path Conventions
- CLI（Go）: `cli/`；后端（Java）: `backend/dataweave-api/...`；Skill: `.claude/skills/weft-task-authoring/`；文档/治理: `docs/`、`CLAUDE.md`、`.specify/memory/constitution.md`。无前端改动。

---

## Phase 1: Setup（共享脚手架）

**Purpose**: 建 Skill 包骨架，为后续填充就位

- [ ] T001 [P] 创建 Skill 包骨架目录 `.claude/skills/weft-task-authoring/`（空 `SKILL.md`、`file-contract.md`、`examples/` 子目录），命名 kebab-case 对齐既有 `openspec-*`/`speckit-*`
- [ ] T002 [P] 在 `cli/README.md` 与 `specs/015-agent-authoring-skill/quickstart.md` 校核 golden path 前置（H2 后端启动、`cd cli && ./build.sh`），无代码改动仅 sanity

**Checkpoint**: Skill 包目录存在，前置可启动

---

## Phase 2: Foundational（阻塞性前置 —— US1/US2 依赖统一认证）

**Purpose**: dw 认证合并为单一凭据。US2 的 Skill 正文将文档化"单一 `DW_TOKEN` Bearer"，US1 golden path 也以统一认证为前提——故为基础层而非 US3。

⚠️ **本阶段完成前，US1/US2 不应开始**

- [ ] T003 合并 dw 认证（CLI 侧）：在 `cli/client/client.go:101–102` 统一单一 Bearer 头注入；移除 `cli/main.go:152–163` 手拼 `X-DW-Token`，task/logs 改走 `cli/client` 统一逻辑（FR-015，contracts/auth-merge-contract.md）
- [ ] T004 合并 dw 认证（后端侧）：`backend/.../infrastructure/JwtAuthFilter.java:32–42` 令 `/api/cli/*` 接受统一 Bearer；`backend/.../interfaces/CliController.java:106–122 requireToken()` 接受统一凭据，**过渡期保留 `X-DW-Token` 双接受**；不触 PolicyEngine 授权/审计、不改 API 体契约（FR-015/FR-028）
- [ ] T005 [P] 后端测试：`@ActiveProfiles("h2")` 断言统一 Bearer 对 CLI 端点与 项目/ops 端点均 200；缺/错凭据 401/403 语义不变；写操作仍过 PolicyEngine + agent_action 审计（回归）（FR-026）

**Checkpoint**: 单一凭据贯通 CLI 与全部端点，授权/审计零回归

---

## Phase 3: User Story 1 - golden path 脊梁 (Priority: P1) 🎯 MVP

**Goal**: 一条文档化、双层验证的"从零写任务→push 上线"路径成立
**Independent Test**: 从干净工作副本跑 CI 确定性 E2E 全绿；真 LLM 验收仪式可按指南复现，全程零依赖已删引导

- [ ] T006 [US1] 在 `.claude/skills/weft-task-authoring/SKILL.md` 写 dev-loop 正文（pull→写/改→`dw run`→`dw diff`→`dw push`→`dw run --test`）作为 golden path 单一真相，并在 `quickstart.md` 交叉引用（FR-001/FR-003）
- [ ] T007 [US1] CI 确定性 E2E：在 `cli/`（复用 `cli/sync/sync_e2e_test.go` mock/httptest 地基 + 真后端 `@ActiveProfiles("h2")`）实现脚本化 dw 命令链跑完整 golden path，断言每步退出码/输出，**无 LLM key**、进 CI、从干净态一次跑通（FR-002/FR-025）
- [ ] T008 [US1] 在 `quickstart.md` 文档化真 LLM 验收仪式（workhorse + 开发者提供配置，加载 Skill 真驱动；**不进 CI**，无 key 环境靠 T007 保持绿）（FR-025a）

**Checkpoint**: golden path 双层验证就绪，MVP 可演示

---

## Phase 4: User Story 2 - Skill 让文件契约对 agent 无歧义 (Priority: P1)

**Goal**: agent 仅凭 Skill 即可无歧义、首次即合法地仿写任务/任务流
**Independent Test**: 给 agent 自然语言诉求，仅凭 Skill 产出的文件首次即结构合法、被 `dw run`/`dw push` 接受

- [ ] T009 [P] [US2] 写 `SKILL.md` frontmatter（`name: weft-task-authoring`、`description` 触发语、`allowed-tools: Bash,Read,Write,Edit,Grep`）按 contracts/skill-contract.md（FR-004/FR-006/FR-013）
- [ ] T010 [US2] 写 `SKILL.md` 正文知识：文件契约结构语义、params 取值约定 + 占位符 `{{...}}` 语法、datasource 逻辑名查法、flow nodes/edges 一致性、GateResult 三态读法（含删除/高危→挂起≠失败）（FR-007/008/009/010/011；数据术语保持英文）
- [ ] T011 [P] [US2] 写 `.claude/skills/weft-task-authoring/file-contract.md` 一页速查
- [ ] T012 [P] [US2] 建 `examples/`：`sample-task.task.yaml`+`.sql`、`sample-flow.flow.yaml`、`datasources.local.yaml` 样例（FR-012）
- [ ] T013 [US2] Skill 一致性 lint（Go test 或脚本）：解析 `SKILL.md` 引用的 `dw <subcommand>/--flag`，比对 `cli/main.go` 实际命令表，不匹配即 fail（FR-027）
- [ ] T014 [US2] 测试：`examples/` 任务/任务流被 `dw run`/`dw push` 接受（FR-012 验证）

**Checkpoint**: Skill 知识完整、示例可跑、防漂移 lint 就位

---

## Phase 5: User Story 3 - dw CLI golden-path 毛糙处硬化 (Priority: P2)

**Goal**: golden path 上的环境/退出码/数据源/基线毛糙处可自定位修复
**Independent Test**: 各失败场景下错误信息可定位；环境失败与任务失败退出码可区分

- [ ] T015 [P] [US3] 退出码拆分：`cli/client/client.go:30–37` 新增 `ExitEnvironment`(7)；`cli/run/local.go:85–95` 环境错（缺 JVM/worker classpath）判 7、任务执行失败透传 runner 原始码，0 成功语义不变（FR-016，contracts/dw-runtime-contract.md）
- [ ] T016 [P] [US3] datasource 提前校验：`cli/run/datasource.go:29–49 LoadDatasources()` 加载即校验必填字段（如 `jdbcUrl`），缺字段运行前报可定位错（含数据源名/字段名）（FR-017）
- [ ] T017 [P] [US3] baseline 过期提示：`cli/sync/push.go:46–49` 把服务端 `project.sync.stale`(409) 渲染为可读提示（说明基线过期 + 推荐 `dw pull`/`dw push --force`），服务端不动（FR-018）
- [ ] T018 [US3] Go 测试：退出码细分（环境错=7、任务失败透传）、datasource 缺字段校验、baseline 过期提示文案（FR-026）

**Checkpoint**: dw golden-path 体验毛糙处闭合，错误可定位

---

## Phase 6: User Story 4 - MCP 重新定位 + 治理修订 (Priority: P2)

**Goal**: 文档与治理固化"创作主路径=Skill+dw、MCP=可选面"，MCP 行为零变更
**Independent Test**: 文档可读到定位与取舍；任一 MCP 工具行为与前一致

- [ ] T019 [P] [US4] `docs/architecture.md` agent 接入小节：定位创作主路径=Skill+dw、MCP=自动化/查询可选面，写明"创作走 Skill 避免常驻上下文膨胀"取舍（FR-020）
- [ ] T020 [P] [US4] 更新 `CLAUDE.md:69–74` MCP & CLI 小节同口径定位（含认证已合并的更新）
- [ ] T021 [US4] 修订 `.specify/memory/constitution.md` 原则 IV 措辞：「operating the platform through MCP」→「through MCP and/or the dw CLI」，记 Skill 为本地 agent 知识层；守不可让渡内核三条不变（FR-021a）
- [ ] T022 [US4] 回归：确认 `McpToolRegistry.java` 零改动、MCP 工具行为/契约与本特性前一致（FR-021）

**Checkpoint**: 治理与现实对齐，MCP 零回归

---

## Phase 7: User Story 5 - 残留清理 (Priority: P3)

**Goal**: 删断脚本与残留 spec，仓库诚实反映 Skill+BYO-agent 形态
**Independent Test**: 断脚本/残留 spec 不复存在；后端完整构建过；悬空引用为 0

- [ ] T023 [P] [US5] `git rm deploy/workhorse/serve-local.sh deploy/workhorse/merge-config.py`，清理指向它的悬空引用；本地 gitignore 二进制/配置不动（FR-022）
- [ ] T024 [P] [US5] `git rm openspec/specs/workhorse-supervisor/spec.md`（残留 spec，不留 archive）（FR-023）
- [ ] T025 [US5] 验证：`cd backend && ./dev-install.sh` 完整构建通过；全仓 `grep` 对 `serve-local.sh`/`merge-config.py`/`workhorse-supervisor` 悬空引用为 0（排除 specs/015 自身）（FR-024）

**Checkpoint**: 残留清零、构建绿

---

## Phase 8: Polish & Cross-Cutting

- [ ] T026 [P] 更新 `cli/README.md`：统一认证（单一 `DW_TOKEN` Bearer）+ 新退出码 7 说明
- [ ] T027 端到端复跑 quickstart 双层（有 key 时含真 LLM 仪式）+ 最终 Constitution 复检（5 原则）+ 键平价/构建全绿确认

---

## Dependencies & Execution Order

- **Phase 1 Setup** → **Phase 2 Foundational（认证合并）**：阻塞 US1/US2。
- **US1（P1, MVP）**：依赖 Foundational；T007 CI E2E 复用既有测试地基。
- **US2（P1）**：依赖 Foundational（Skill 文档化统一认证）；与 US1 可在 Foundational 后并行推进（US1 偏路径/测试、US2 偏知识内容）。
- **US3（P2）**：独立于 US1/US2，可并行；但 T018 测试与 T007 E2E 断言的退出码需一致（US3 落地后 T007 断言更新为 7/透传）。
- **US4（P2）**：独立，纯文档/治理；可随时并行。
- **US5（P3）**：独立，纯清理；可随时并行。
- **Phase 8 Polish**：所有故事完成后收尾。

### 并行机会
- Foundational 内：T005 [P] 与 T003/T004 实现并行（测试可与实现交错）。
- US2 内：T009/T011/T012 [P] 三件并行（frontmatter/速查/示例不同文件）。
- US3 内：T015/T016/T017 [P] 三件并行（local.go/datasource.go/push.go 不同文件）。
- 跨故事：US3、US4、US5 三条 P2/P3 故事彼此独立，可并行于 US1/US2 之后。

### MVP 范围
**Phase 1 + Phase 2（认证合并）+ Phase 3（US1 golden path）** = 可演示、可断言的 golden path 增量。US2 紧随其后补齐 Skill 知识，使真 LLM 仪式完整。

---

## Implementation Strategy

1. **先打地基**：认证合并（Foundational）+ Skill 骨架，解锁 golden path。
2. **MVP**：US1 双层 golden path（CI E2E 先绿）。
3. **补知识**：US2 Skill 正文 + 示例 + 防漂移 lint，使真 LLM 仪式可跑。
4. **并行收口**：US3（dw 硬化）/US4（MCP 定位+修宪）/US5（残留清理）三条独立故事并行。
5. **Polish**：README + 端到端复跑 + Constitution 复检。

> WSL2 长跑：后端测试用 `setsid` 脱离 + 单次轮询（见 CLAUDE.md 硬规则）；H2 共享内存库净库测试用独立库名（见记忆 h2-shared-mem-db-test-pollution）。
