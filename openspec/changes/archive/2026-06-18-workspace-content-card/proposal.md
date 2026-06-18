## Why

Workspace 顶部 tab 条（驾驶舱/任务流/数据新鲜度/业务报表）下方的视图内容区是**全透明**的，直接铺在页面背景上，与激活 tab（`bg-muted` 圆角药丸）在视觉上毫无关系。用户看不出「tab 和它的内容是一个整体」，缺少「卡片联动感」。

## What Changes

- Workspace 内容区（tab 条下方的视图容器）从透明改为一张**浮起卡**：`bg-sidebar + border + shadow-lg + rounded-lg`，左右下留边（`mx-3 mb-3`）。
- 该卡与**左侧 Agent 对话面板**（`agent-rail` 同样 `bg-sidebar + border + shadow-lg + rounded-lg`）成「同材质的一对」—— 左对话卡 + 右内容卡，亮色同为暖味近白（`--sidebar` = `oklch(0.988 …)`），暗色同为深 taupe，统一抬升层。
- 激活 tab 下方的视图因此读成「这张卡的内容」，内部白色数据卡（`bg-card`）浮在 `bg-sidebar` 卡面上，层次清楚。
- **tab 本身样式不动**；不引入 Chrome 式弧形角。

## Capabilities

### New Capabilities
- `workspace-content-card`: Workspace 内容区作为浮起卡的约定 —— 底色/边框/阴影/圆角规格、与左侧 Agent 面板的同材质配对关系、tab↔内容的视觉联动。

### Modified Capabilities
<!-- 无 spec 级行为变更；tab 行为、视图挂载策略均不变。 -->

## Impact

- **组件**：`frontend/components/workspace/workspace.tsx`（内容容器 className）。
- **依赖关系**：视觉上与 `frontend/components/agent-rail.tsx` 的面板规格对齐（同 `bg-sidebar` 浮起卡），后者样式不改。
- **约束**：遵守 `DESIGN.md` 无分割线规则、语义 token（`bg-sidebar`，不手写裸色）。
- **验证**：`pnpm typecheck` + Browser Verification Gate（亮/暗两态真跑，确认内容卡与左面板同底、console 无 error）。
