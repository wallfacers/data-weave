# Implementation Plan: 加载状态统一转圈动画

**Branch**: `035-loading-spinner` | **Date**: 2026-07-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/035-loading-spinner/spec.md`

## Summary

将项目中所有纯文字"加载中"状态统一替换为带旋转图标的居中加载组件。shadcn/ui 无内置 Spinner，项目已有 `LoadingState` 组件（未使用）和 `useMinSpin` hook。核心工作是改造 `LoadingState` 增加无障碍支持，然后在 ~14 个文件中用它替换现有的纯文字加载状态。

## Technical Context

**Language/Version**: TypeScript 5, React 19, Next.js 16 (App Router, Turbopack)

**Primary Dependencies**: `@hugeicons/core-free-icons` (RefreshIcon), `@hugeicons/react` (HugeiconsIcon), Tailwind CSS 4 (`animate-spin`, `motion-safe:`), next-intl (i18n), shadcn/ui (headless — 无 spinner)

**Storage**: N/A（纯前端展示层变更）

**Testing**: vitest（如有）+ 浏览器实跑验证

**Target Platform**: Web 浏览器（Chrome/Edge/Firefox）

**Project Type**: Web 前端 (Next.js SPA)

**Performance Goals**: 加载动画不应增加可感知的渲染延迟；`useMinSpin` 保证最小可见时长 1000ms 不造成阻塞

**Constraints**: 
- 不改变现有数据获取逻辑（`useApi`/`useLiveData`/DataTable loading）
- 不改变 i18n 翻译键结构
- 图标使用已有 `RefreshIcon`，不引入新依赖
- 必须支持 `prefers-reduced-motion`（Tailwind `motion-safe:` 前缀）

**Scale/Scope**: ~14 个文件中的 ~18 处纯文字加载状态

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 状态 | 说明 |
|------|------|------|
| I. Files-First | N/A | 纯前端 UI 变更，不涉及文件定义 |
| II. Server Source of Truth | N/A | 不涉及数据同步 |
| III. Two-Legged Debugging | N/A | 不涉及 CLI/运行时 |
| IV. AI Lives in Local Agent | N/A | 不涉及 AI/agent 架构 |
| V. Reuse the Kernel | N/A | 不涉及调度/策略引擎内核 |

**结论**: 本功能为纯前端 UI 润色，不触及 Weft 平台架构的任何宪法原则。无违规。

## Project Structure

### Documentation (this feature)

```text
specs/035-loading-spinner/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 output
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
frontend/
├── components/
│   ├── workspace/shared/
│   │   └── loading-state.tsx        # [MODIFY] 改造为带 props + 无障碍的规范组件
│   ├── workspace/views/
│   │   ├── view-status.tsx          # [MODIFY] 使用 LoadingState 替代纯文字
│   │   ├── alerts-view.tsx          # [MODIFY] 替换文字为 LoadingState
│   │   ├── asset-catalog-view.tsx   # [MODIFY] 替换文字为 LoadingState
│   │   ├── fleet-view.tsx           # 通过 ViewStatus 间接修复
│   │   ├── lineage-view.tsx         # [MODIFY] 替换文字为 LoadingState
│   │   ├── metrics-view.tsx         # 通过 ViewStatus 间接修复
│   │   ├── metric-marketplace-view.tsx  # [MODIFY] 替换文字为 LoadingState
│   │   ├── quality-view.tsx         # [MODIFY] 替换文字为 LoadingState
│   │   ├── reports-view.tsx         # 通过 ViewStatus 间接修复
│   │   ├── workflow-canvas-view.tsx # 通过 ViewStatus 间接修复
│   │   ├── workflow-instance-detail.tsx  # [MODIFY] 替换文字为 LoadingState
│   │   ├── lineage/lineage-tree.tsx # [MODIFY] 替换文字为 LoadingState
│   │   └── asset/subscriptions-dialog.tsx  # [MODIFY] 替换文字为 LoadingState
│   ├── workspace/
│   │   ├── detail-panel-shell.tsx   # [MODIFY] 用 LoadingState 替代内联 spinner
│   │   ├── dag-dialog.tsx           # [MODIFY] 用 LoadingState 替代内联 spinner
│   │   └── log-panel.tsx            # 仅图标按钮，不加文字（保持不变）
│   ├── app-shell.tsx                # [MODIFY] 替换文字为 LoadingState
│   ├── code-editor.tsx              # [MODIFY] 替换文字为 LoadingState
│   └── ui/
│       └── data-table.tsx           # [MODIFY] 替换文字为 LoadingState
└── hooks/
    └── use-min-spin.ts             # 保持不变（已满足需求）
```

**Structure Decision**: 单前端项目，改动集中在 `components/` 下的文本加载状态。核心改动一个组件（`LoadingState`），其余全是替换调用点。

## Phase 0: Research ✅

**Output**: [research.md](./research.md)

关键发现:
1. shadcn/ui 无 Spinner 组件 — 使用现有 `RefreshIcon` + `animate-spin` 方案
2. `LoadingState` 组件已定义但零引用 — 改造后统一使用
3. `useMinSpin` hook 已满足最小显示时长需求 — 直接复用
4. ~18 处纯文字加载状态分布在 14 个文件中
5. 无障碍方案: `motion-safe:animate-spin` + `role="status"` + `aria-label`

## Phase 1: Design

### Component API: LoadingState

改造 `frontend/components/workspace/shared/loading-state.tsx`:

```typescript
interface LoadingStateProps {
  /** 加载文字，默认通过 i18n 获取 "加载中"（fallback: "Loading…"） */
  text?: string
  /** 显示模式 */
  variant?: "centered" | "overlay"
  /** overlay 模式下是否覆盖整个父容器（默认 flex-1 居中） */
  fullHeight?: boolean
}
```

**模式说明**:
- `centered`（默认）: `flex items-center justify-center` + 垂直布局（图标在上，文字在下），用于无数据时的全区域居中
- `overlay`: `absolute inset-0 bg-card/60` 半透明遮罩 + 居中，用于有旧数据时的覆盖层

**内置行为**:
- 内部调用 `useMinSpin(active, 1000)` → 保证至少旋转 1 秒
- 图标: `RefreshIcon` + `motion-safe:animate-spin`（响应 `prefers-reduced-motion`）
- 无障碍: `role="status"` + `aria-label={text}`
- 使用 `useTranslations` 获取默认文字（fallback: "Loading…"）

### 调用方替换策略

**策略 A — 全文替换**（无数据时全区域居中）:
```tsx
// Before
if (loading && !data) return <p>{t("loading")}</p>
// After
if (loading && !data) return <LoadingState />
```

**策略 B — 通过 ViewStatus**（间接修复 4 视图）:
修改 `ViewStatus` 内部使用 `LoadingState` 替代 `<p>`，fleet/metrics/reports/workflow-canvas 自动受益。

**策略 C — DataTable**（表格内居中）:
```tsx
// Before
{loading && !result ? <p>{t("loading")}</p> : ...}
// After
{loading && !result ? <LoadingState /> : ...}
```

**策略 D — 覆盖层**（有旧数据后台刷新）:
```tsx
// Before (detail-panel-shell 内联写法)
{loading && hasData && <div className="absolute inset-0 ..."><RefreshIcon .../></div>}
// After
{loading && hasData && <LoadingState variant="overlay" />}
```

**策略 E — 内联文字替换**（asset-catalog、metric-marketplace 的总数标签）:
```tsx
// Before
{loading ? t("loading") : t("totalCount", { count })}
// After
{loading ? <span className="inline-flex items-center gap-1"><SpinnerSmall />{t("loading")}</span> : t("totalCount", { count })}
```
注: 内联场景图标用 `size-3.5` 而非 `size-5`，与文字 `text-sm` 比例协调。

### 设计原则检查

- [x] base-style: 无 `asChild` 用法，无需特殊处理
- [x] Icons: 使用 hugeicons `RefreshIcon`
- [x] shadcn: 使用语义 token（`text-muted-foreground`、`bg-card`）
- [x] i18n: 接受 `text` prop，默认用 `useTranslations`，不硬编码中文
- [x] DESIGN.md: 不引入新的设计 token 或视觉变量

### Skipped Artifacts

- **data-model.md**: N/A — 本功能不涉及数据模型
- **contracts/**: N/A — 本功能不涉及 API 契约
- **quickstart.md**: N/A — UI 润色无需运行指引

## Phase 2: Tasks

将由 `/speckit-tasks` 命令生成。

## Complexity Tracking

> 无宪法违规，无需记录。
