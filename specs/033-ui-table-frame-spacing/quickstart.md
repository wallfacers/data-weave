# Quickstart / 验证指南：表格边框 frame + 设置间距

前置：后端 `:8000`、前端 `:4000` 已起（见 [README.md](../../README.md) / CLAUDE.md Build & Run）。本特性纯前端，后端可用默认或 H2 profile。

> Turbopack 全局 CSS 陈旧缓存坑：改动后边框不显时，先 `rm -rf frontend/.next` 重启 `pnpm dev`，别怀疑特异度（[[turbopack-global-css-stale-hmr]]）。

## 1. 类型/静态门

```bash
cd frontend && pnpm typecheck        # 零错误
pnpm design:lint                      # DESIGN.md 结构校验通过（增补段不破结构）
```

## 2. 单元/DOM 测试（契约 A/B 自动断言）

```bash
cd frontend && pnpm test              # 覆盖 ui-visual-contract.md 契约 A1/A3、B1/B2、C1
```

预期：`DataTable` 根渲染含 `border rounded-xl bg-card overflow-hidden`；`SettingsView` 三 Tab 表格 `getBoundingClientRect().left` 与 Tab 条一致。

## 3. 浏览器验证门（契约 A2/A4/A5、B3/B4、SC-005/007）

登录后打开工作区，逐项目测：

### 3.1 系统设置间距（故事1）

1. 打开「系统设置」→ 依次点「用户 / 角色 / 项目」三个 Tab。
2. **验 B2/B4**：三 Tab 切换时，表格左上角不跳动；Tab 条、标题、表格左边缘对齐成一条竖线。
3. **验 B1**：DevTools 选中内容区，确认无"外层 padding 内再套 padding"的双层留白。

### 3.2 全站表格边框（故事2）

逐一打开并确认表格被**单一边框卡片**包裹，且边框/圆角/内留白与「系统设置·项目 Tab」参照一致（SC-005）：

- 系统设置（用户/角色/项目）—— 参照基准
- 运维 → 周期实例 / 周期工作流 / 手动工作流 / 工作流实例 / 回填
- 数据源
- 新鲜度

对每张表：
- **验 A2**：搜索/筛选工具条与分页控件都在边框**之内**。
- **验 A4**：切到空结果筛选（无数据）与加载瞬间，边框仍在、不塌陷、不重影。
- **验 A5**：列多到横向滚动时，滚动区被圆角边框正确裁剪、不撑破。
- **验 A6/SC-006**：无"卡片边框 + 表格边框"双层边框（重点看设置页删 Card 后是否仍单框）。

### 3.3 双主题（SC-007）

切换浅色 / 深色主题，各表边框均清晰可辨（暗色 `border` 为 `oklch(1 0 0 /10%)`，应可见不刺眼）。

## 4. 通过标准

- typecheck / design:lint / test 全绿。
- 8 个含表格视图 100% 呈现统一边框 frame（SC-004）。
- 设置三 Tab 间距一致、切换零跳动（SC-001/002/003）。
- 全站无双层边框（SC-006）；亮/暗主题边框均可辨（SC-007）。

参照 [contracts/ui-visual-contract.md](contracts/ui-visual-contract.md) 逐条断言核对。
