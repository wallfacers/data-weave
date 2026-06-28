# Implementation Plan: 统一技术债收口（Weft 掉头松散端一次性清理）

**Branch**: `014-tech-debt-closure` | **Date**: 2026-06-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/014-tech-debt-closure/spec.md`

## Summary

把 Weft 掉头后刻意 defer 的四项松散端一次性正规收口：① 移除 ops 视图里仅靠 mock 喂数的服务端-AI 时代残留"Agent 举手台"告警 rail（含 store/卡片/右栏/mock 注入器及全部悬空引用）；② 把 ops 实例面板漏迁的硬编码中文 UI 文案完整迁入 next-intl 双语言文案集（含 ICU 动态插值、键平价）；③ 删除 `backend/dataweave-alert/` 死目录（未跟踪的构建残骸）；④ 把"push 路径不落表级血缘=有意延迟（发布期 neo4j 重做）"明确写进架构文档与代码导航注记。

技术取向：纯清理 + 自洽性收口，**零新增运行期能力、零 API 契约改动、零 DB schema 改动、零内核改动**。主体改动面在 `frontend/`（US1+US2），辅以一处后端目录删除（US3）与两处文档（US4）。验证靠前端 typecheck + i18n 键平价 + 浏览器中英 locale 目检 + 后端 dev-install 构建通过。

## Technical Context

**Language/Version**: TypeScript / React 19（前端主体）；Java 25（仅 US3 删目录后构建验证，无 Java 代码改动）

**Primary Dependencies**: Next.js 16 (App Router)、next-intl、zustand、shadcn/ui (base style)、hugeicons

**Storage**: N/A（不触 DB、不改 schema、不改同步 API）

**Testing**: `pnpm typecheck`（零错门）、i18n 键平价校验（既有 CI 检查：两 bundle 同键集 + 每处 `t("key")` 可静态解析）、浏览器人工验证（admin/admin JWT 注入 localStorage，深链 `/?open=ops`，中英 locale 各验一遍）；后端 `./dev-install.sh` 构建通过（US3）

**Target Platform**: Web（前端 :4000）+ 后端构建链（:8000 不需启动）

**Project Type**: Web application（frontend + backend 双项目，本特性主体落在 frontend）

**Performance Goals**: N/A（无运行期性能目标，清理类特性）

**Constraints**: 不引入新运行期能力；不改 pull/push/diff 同步 API 契约与传输形态；不动调度内核、权限/审计内核、文件契约语义；i18n 遵守三规则①（静态 UI 文案归 next-intl，按 UI locale）+ 数据术语保持英文；前端栈门（base-style `render` 非 `asChild`、hugeicons、语义 token、`gap-*`/`size-*`，改前读 DESIGN.md）

**Scale/Scope**: 4 个 User Story；改动文件量小（前端约 6–10 个文件 + 2 份文案 bundle + 2 份文档；后端 1 个目录删除）；已知硬编码中文 5 处 + 全量 sweep 补漏

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

本特性是 A–E 落地后的清理收尾，对照 Weft Constitution Principles I–V：

- **I. Files-First**：不涉及——不改文件契约的磁盘表示，不动任务/工作流定义序列化。✅ 不违反。
- **II. Server is Source of Truth**：不涉及——不改 pull/push/diff 语义、不改版本快照、不碰隔离。US4 仅文档化"push 不落血缘"这一既有事实，不改 push 行为。✅ 不违反。
- **III. Two-Legged Debugging (NON-NEGOTIABLE)**：不涉及——不动 CLI 本地 runtime 与 TEST 提交。✅ 不违反。
- **IV. AI Lives in the Local Agent (NON-NEGOTIABLE)**：**正向强化**——US1 移除的"Agent 举手台"正是服务端-AI 时代"proactive-notify/findings"链路的前端残肢（Principle IV 要求"无 active code 或依赖残留"）。本特性把这块残留清干净，使 IV 更彻底闭合。移除 MUST NOT 损伤运行期可观测性（ops overview / metrics / 运行日志 / DAG 实例视图）——举手台是右栏告警 rail，与这些主舞台能力解耦，移除不波及。✅ 符合且强化。
- **V. Reuse the Kernel**：不涉及——不重写任何内核；不新增写操作（无需过 PolicyEngine）。✅ 不违反。

**附加约束**：Round-trip integrity 不受影响（不改同步）；No Legacy Migration 不涉及。

**结论**：无违反、无需 Complexity Tracking。US1 对 Principle IV 是正向收口。门槛通过。

## Project Structure

### Documentation (this feature)

```text
specs/014-tech-debt-closure/
├── plan.md              # 本文件
├── research.md          # Phase 0：4 项现状勘察结论 + 决策记录
├── data-model.md        # Phase 1：清理对象清单（非持久实体）
├── quickstart.md        # Phase 1：验收复跑步骤（中英 locale 目检 + 构建验证）
├── checklists/
│   └── requirements.md  # spec 质量清单（已建）
└── tasks.md             # Phase 2（speckit-tasks 生成，非本步产物）
```

无 `contracts/`：本特性零 API 变更，无端点契约可写。

### Source Code (repository root)

```text
frontend/
├── components/workspace/views/
│   ├── ops-view.tsx                      # US1 改：移除右栏举手台 rail + useMockAlertInjector + __MOCK_OPS_ALERT__
│   └── ops/
│       ├── ops-alert-card.tsx            # US1 删：举手台告警卡片
│       ├── workflow-instances-panel.tsx  # US2 改：4 处硬编码中文 → t()
│       ├── periodic-instances-panel.tsx  # US2 改：1 处含动态数量 → t() + ICU {count}
│       └── backfill-dialog.tsx           # US1 核查：清理对举手台 store/类型的悬空引用（若有）
├── lib/workspace/
│   └── ops-alerts-store.ts               # US1 删：举手台告警状态 store
├── components/side-panel/side-panel.tsx  # US1 核查：悬空引用清理（若有）
├── app/layout.tsx                        # US1 核查：悬空引用清理（若有）
└── messages/
    ├── zh-CN.json                        # US2 改：补 ops 命名空间漏迁键
    └── en-US.json                        # US2 改：同键集英文文案

backend/
└── dataweave-alert/                      # US3 删：整目录（未跟踪构建残骸）

docs/architecture.md                      # US4 改：血缘小节增"push 不落血缘=有意延迟"
CLAUDE.md                                 # US4 改：血缘导航行加注记
```

**Structure Decision**: Web application 双项目结构。本特性主体落在 `frontend/`（US1 删除+引用清理、US2 i18n 迁移），辅以 `backend/` 一处目录删除（US3）与两处仓库文档（US4）。不新增任何目录或模块。US1/US2 各自独立可测可交付（MVP=US1+US2），US3/US4 为低风险收尾，彼此无依赖、可并行。

## Phase 0：现状勘察结论（research.md）

四项现状已实地勘察（grep/ls/git 核实），无 NEEDS CLARIFICATION，两项决策性裁定已由项目主拍板。详见 [research.md](./research.md)。要点：

- 举手台数据源 = 仅 `window.__MOCK_OPS_ALERT__` mock，后端无真告警源 → 裁定移除。
- `dataweave-alert/` = 0 个 git-tracked 文件、`target/` 被 gitignore、不在根 pom modules → 安全删除。
- 硬编码中文 = 已定位 5 处（须再全量 sweep 防漏，含非正文文本属性）。
- push 不落血缘 = 既有事实，血缘改 neo4j 方向已定 → 裁定 defer + 文档化。

## Phase 1：设计产物

- **data-model.md**：本特性无持久实体，"模型"= 清理对象清单 + 各对象的判定依据与处置（删/迁/文档化）。
- **quickstart.md**：验收复跑步骤——前端 typecheck + i18n 键平价 + 浏览器中英 locale 目检（举手台消失 + 批量文案正确）+ 后端 dev-install 构建通过。
- **agent context**：运行 `update-agent-context.sh claude`（无新技术栈引入，主要是保持索引一致）。

## 复杂度追踪

无 Constitution 违反，无需填写。

## 后续步骤

- **speckit-tasks** 生成依赖有序任务表（按 US 分组，US1/US2 为 MVP）。
- 实现交外部 AI，我（总设计/评审）兜底收尾：证伪式核验（typecheck/键平价真跑、浏览器中英目检、dev-install 构建真过），禁信"全绿"自述。
