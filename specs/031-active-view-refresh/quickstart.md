# Quickstart: 验证激活页自动无感刷新 + 统一手动刷新

## 前置
```bash
# 后端（H2 零依赖即可）
cd backend && ./dev-install.sh && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
# 前端（在 031 worktree 内）
cd /home/wallfacers/project/dw-031-active-view-refresh/frontend && pnpm install && pnpm dev   # :4000
```
登录 admin/admin 拿 JWT；深链 `/?open=metrics` 等直达视图。

## 自动化验证
```bash
cd frontend
pnpm typecheck                      # 零错误
pnpm vitest run lib/workspace/__tests__/use-live-data.test.ts
```
单测须覆盖（对应 SC/FR）：
- 激活 + 开关开 + 可见 → 按 interval 轮询（fake timers 推进，断言 fetch 次数）。
- 失活 / 整窗隐藏 → 停止，**后台请求数=0**（SC-003 / FR-003 / FR-005）。
- 切回 active 边沿 → 立即刷新一次（FR-004，1s 内即起）。
- 在途时 tick → 跳过（不堆叠，FR-009）。
- 手动 refresh + 在途 → 合并为一次（不并发，FR-008 / SC-005）。
- 刷新失败 → 保留上次 data + `stale=true`，下周期重试（FR-010 / SC-006）。
- 卸载/path 变化 → 丢弃迟到结果（不 setState-after-unmount）。

## 浏览器走查（无感 & 一致性）
1. **自动无感更新（SC-001/SC-002）**：打开 metrics，制造后端指标变化（跑一次任务/注入），不触碰页面 → ≤30s 内数字与「最后更新时间」自动更新；先滚到中部并观察，刷新瞬间**不闪全屏、不跳动、滚动位置不变**。
2. **后台暂停（SC-003）**：开 metrics + reports 两 tab，停在 metrics，用 DevTools Network 过滤 → reports（后台）在此期间**无任何刷新请求**；切到 reports → **立即**发一次请求并随后按周期刷新。
3. **整窗可见性（FR-005）**：停在 metrics，切到别的应用/最小化 → 轮询停止；切回 → 立即刷新一次。
4. **表格保持分页/筛选（FR-002）**：freshness 翻到第 3 页 + 设一个筛选 → 等自动刷新 → **仍在第 3 页、筛选仍在**，数据原地更新，不回第一页、不重置滚动。
5. **手动刷新（US3）**：任一视图点刷新按钮 → 图标旋转（disabled）、数据立即更新、「最后更新时间」刷新；刷新中再点不产生并发。
6. **自动刷新开关会话内（FR-014）**：关掉某页开关 → 不再自动刷新，但手动仍可点；**重开该 tab 或 Ctrl+Shift+R 刷新整页** → 开关回到「开」。
7. **失败非打断（FR-010）**：断开后端 → 自动刷新失败时页面**保留上次数据**、出现非打断的「数据可能过时」提示、无全屏错误；恢复后端 → 下周期自动恢复。
8. **跨视图一致性（SC-004）**：逐一对比 metrics/reports/freshness/ops/quality/alerts 的刷新控件 → 图标、相对位置、交互一致（设计走查过 DESIGN.md）。

## 完成判据
- `pnpm typecheck` + vitest 全绿；上述 8 项浏览器走查全过；新增 i18n 键 zh-CN/en-US 一致（CI 校验）。
