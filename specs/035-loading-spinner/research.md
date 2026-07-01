# Research: 加载状态统一转圈动画

**Feature**: 035-loading-spinner | **Date**: 2026-07-01

## Research Summary

本次为纯前端 UI 润色功能，研究重点是现状摸底和方案确认，无需后端/API/数据模型调研。

## Key Findings

### 1. shadcn/ui 无 Spinner 组件

**结论**: shadcn/ui 是 headless 组件库，不提供 spinner/loading 动画组件。项目无需引入新依赖。

### 2. 项目已有旋转动画方案

项目已建立 `RefreshIcon`（@hugeicons/core-free-icons）+ Tailwind `animate-spin` 为标准旋转动画方案，在 `detail-panel-shell`、`dag-dialog`、`view-refresh-control` 中有现成使用。

### 3. LoadingState 组件已定义但未使用

- 文件: `frontend/components/workspace/shared/loading-state.tsx`
- 状态: 已导出，但 **零引用**（无任何文件 import 它）
- 当前实现:
  ```tsx
  <div className="flex flex-col items-center justify-center gap-2 py-12 text-muted-foreground">
    <HugeiconsIcon icon={RefreshIcon} className="size-5 animate-spin" />
    <span className="text-xs">Loading…</span>
  </div>
  ```
- 问题: 硬编码英文 "Loading…"（未 i18n），无 props 配置，无无障碍支持

### 4. `useMinSpin` hook 已满足需求

- 文件: `frontend/hooks/use-min-spin.ts`
- 功能: 将可能瞬间完成的 `active` 布尔量兜底为至少旋转 `minMs`（默认 1000ms）
- 使用方: `view-refresh-control.tsx`、`event-center-view.tsx`
- 结论: 直接复用，无需修改

### 5. 加载状态分布统计

| 类别 | 数量 | 说明 |
|------|------|------|
| 纯文字加载（无动画） | ~18 处 | 分布 14 个文件中 |
| 已有 spinner + 文字 | 3 处 | detail-panel-shell (2 模式), dag-dialog, code-block |
| 仅图标无文字 | 3 处 | view-refresh-control, event-center-view, sonner toast |
| 通过 ViewStatus 间接使用 | 4 视图 | fleet, metrics, reports, workflow-canvas |

### 6. 无障碍方案

- **`prefers-reduced-motion`**: Tailwind 提供 `motion-safe:animate-spin` 前缀，仅在用户未开启减少动画时旋转；同时也支持 `motion-reduce:` 作为备选
- **屏幕阅读器**: 使用 `role="status"` + `aria-label="加载中"` 让屏幕阅读器在状态变化时播报
- 实现在 `LoadingState` 组件内一次完成，所有调用方自动获得

## Rejected Alternatives

### 引入新 spinner 库（如 react-spinners、lucide-react Loader2）
- **拒绝原因**: 项目已有 `RefreshIcon` + `animate-spin` 方案且在多个位置使用，引入新依赖会增加 bundle 大小且造成视觉不一致。CLAUDE.md 要求图标使用 hugeicons。

### 纯 CSS 手写 spinner（不用图标）
- **拒绝原因**: 与项目图标体系不一致，且已有 `RefreshIcon` 语义清晰（旋转 = 刷新/加载中）。

## Decisions

1. **改造现有 `LoadingState`** 而非创建新组件——减少碎片化
2. **`LoadingState` 内部使用 `useMinSpin`**——所有调用方自动获得防闪烁能力
3. **保留原有硬编码 "Loading…" 为 fallback**，接受 `text` prop 支持 i18n
4. **DataTable 的 loading 处理保持特殊**——它已有 `loading && !result` vs `loading && result`（stale）的两层逻辑，替换内部渲染但保留外层判断
5. **不改变数据获取 hooks**（`useApi`、`useLiveData`）——这些 hooks 的 `loading` 签名和行为不变
