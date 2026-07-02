# Contract: 复用优先工作流校验清单

**约束对象**：任何在 `frontend/` 新建/改造页面的实现者（开发者 + AI agent）。
**满足时机**：实现任一界面原语**之前**（查目录）与**之后**（自查/评审）。这是 FR-002 的可执行形态，也是本特性 SC-001/002/006 的达成手段。

## 实现前（MUST，先查后写）

- [ ] 打开 `frontend/DESIGN.md` 的 `## 公共组件目录`，确认所需原语（Tabs/表格/下拉/弹框/日期/加载/刷新/滚动/卡片）是否已有规范组件。
- [ ] 命中 → **直接复用该规范组件**，按其"关键 props/变体"配置；不得手写同类原语。
- [ ] 未命中 → 才允许实现新功能组件，且计划在同一改动内**回填目录条目**（满足 `catalog-entry.schema.md`）。

## 实现后（MUST，自查/评审）

- [ ] **无重复造轮子**：本次改动未手写目录已覆盖的原语（无页面级一次性 Tabs/表格边框/加载文字/下拉浮层）。（FR-005）
- [ ] **卡片内边距用 token**：主内容卡片内边距走 `--card-spacing`，无手填 `p-5`/`20px` 类魔法值。（FR-010）
- [ ] **加载/刷新居中**：加载态用 `LoadingState`（垂直+水平居中转圈）；刷新入口用 `ViewRefreshControl` 且位置与其它页一致。（FR-012）
- [ ] **日期口径**：日期主键默认业务日期（`yyyy-MM-dd`）；需时间才用 `useFormatDateTime` 带时间变体。（FR-011）
- [ ] **Tabs 选对变体**：closable 主 tab → `TabStrip`；页内非 closable 子 tab → 下划线 `Tabs`；无手写下划线/写死等分。（FR-006）
- [ ] **语义 token + 明暗一致**：颜色/间距用语义 token（`bg-primary`/`gap-*`），无手写 `dark:` 覆盖，明暗主题外观一致。（FR-013）
- [ ] **base-style 规则**：自定义 trigger 用 `render` 非 `asChild`；`Button` 作 `<a>` 加 `nativeButton={false}`；图标用 `HugeiconsIcon`。
- [ ] **减少动画降级**：动画走 `motion-safe:`，`prefers-reduced-motion` 时有静态降级 + 辅助技术可读状态。（FR-014）
- [ ] **门禁**：`pnpm typecheck` 零错误；改到 DESIGN.md/token 则 `pnpm design:lint` 通过；跨页明暗抽查外观一致。

## 例外（新建组件的正当性判定）

当确需目录未覆盖的组件时，实现者 MUST 能回答："为何既有条目都不适用？" 若答案是"只是样式微调"→ 属于滥用例外，应改配置既有组件；若确为新原语 → 新建 + 回填目录。（防 Edge Case "约定被绕过"）
