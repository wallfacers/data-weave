## 1. 内容区浮起卡

- [x] 1.1 `workspace.tsx` 内容容器 className 改为 `relative mx-3 mb-3 flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-sidebar shadow-lg`，与左 Agent 面板同规格
- [x] 1.2 `cd frontend && pnpm typecheck` 通过

## 2. 回退早期 surface-raised 方向

- [x] 2.1 还原 `DESIGN.md`、`app/globals.css`（移除 `--surface-raised` token 与「双层表面」章节）
- [x] 2.2 还原 `components/ui/card.tsx`（移除 `CardPanel`）及各视图套用（cockpit / freshness / instance-table / task-def-list）

## 3. 验证（Browser Verification Gate）

- [x] 3.1 浏览器亮色态真跑：内容卡 `bg-sidebar` 与左 Agent 面板同底（lab 98.6%），白数据卡浮起，console 无 error
- [x] 3.2 浏览器暗色态真跑：左右同深 taupe 底，层次成立
- [x] 3.3 三方案（同材质连接 / 描边白卡 / 顶部连接条）真实 app 截图比对，定方案 2 + `bg-sidebar`
- [x] 3.4 验证产物存 `tmp/` 并已清理，不入 git
