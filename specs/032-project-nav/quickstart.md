# Quickstart: 企业项目左侧导航

**Feature**: 032-project-nav | **Date**: 2026-07-01 | **Worktree**: `/home/wallfacers/project/dw-032-project-nav`

## 前置

```bash
# 后端（H2 零依赖即可验收前端；需真项目数据则用 PG）
cd /home/wallfacers/project/dw-032-project-nav/backend
./dev-install.sh && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2   # :8000

# 前端（worktree 内）
cd /home/wallfacers/project/dw-032-project-nav/frontend
pnpm install && pnpm dev                                                                       # :4000
```

> 长命令在 WSL2 必须 `setsid` 脱离 [[wsl2-long-command-detach]]。

## 开发后自检（每次改动）

```bash
cd /home/wallfacers/project/dw-032-project-nav/frontend
pnpm typecheck          # 零错误再继续
pnpm test               # vitest：nav-groups 覆盖不变量 + 高亮映射 + project-context 默认/恢复
pnpm design:lint        # 设计系统校验（改了 visuals 时）
```

## 单元测试要点（vitest）

1. **分组覆盖不变量**：`flatten(NAV_GROUPS.items) ∪ CONTEXT_DETAIL_VIEWS === keys(VIEW_META)`，且无重复（SC-003）。
2. **高亮映射**：入口视图 → 自身+分组高亮；`instance-log`/`workflow-instance-detail` → `ops` 模块高亮；未知 → 无高亮（FR-006/007）。
3. **project-context**：`loadProjects` 后 `currentProjectId` 取列表首个（FR-019）；持久化值有效则恢复（FR-015）；空列表 → `empty` 态（FR-017）。
4. **切项目副作用**：`setProject` 触发 `closeMany` 关闭带 params 的详情标签，保留功能标签（FR-018）。
5. **i18n key 集一致**：`leftNav.*` 在 zh-CN/en-US 双语齐全（CI 既有校验）。

## 浏览器验收（鉴权见 [[browser-verification-jwt-login]]）

admin/admin 登录取 JWT 注入 `localStorage dw.auth.token`，访问 `http://localhost:4000`。

| 验收 | 步骤 | 期望（对应 AC/SC） |
|------|------|---------------------|
| 全景可见 | 进入应用 | 最左侧常驻导航，7 分组目录可见，无需点菜单（US1-AC1 / SC-001） |
| 点击打开 | 点「运维监控 › 运营中心」 | 工作区打开/激活 ops 视图（US1-AC2 / FR-004） |
| 不重复开 | 再点同项 | 激活既有标签，不新建（US1-AC3 / SC-006） |
| 高亮同步 | 切换工作区标签 | 左侧高亮项 ≤1s 同步当前功能（US3 / SC-004） |
| 详情归属 | 打开某实例日志 | 高亮落在 ops 模块，无误导高亮（US3-AC3 / FR-007） |
| 项目切换 | 顶部切到另一项目 | 资产目录/运营中心重取为新项目数据 ≤2s；带旧参数详情标签自动关（US2-AC2 / FR-018 / SC-007） |
| 首次默认 | 清 `dw.project.current` 后刷新 | 默认选中列表首个项目（US2-AC4 / FR-019） |
| 项目记忆 | 切项目后刷新 | 恢复上次所选项目（US2-AC3 / FR-015 / SC-008） |
| 收起 icon rail | 点收起 | 导航变仅图标窄条，图标仍可一键直达；刷新保留（US4 / FR-009） |
| 三入口一致 | 同一功能分别用导航/「+」菜单/深链 `?open=` 打开 | 至多一个标签（FR-008 / SC-006） |

## 跨特性对齐（合入前必做）

本特性动 `app-shell.tsx` + workspace 外壳，与 030（tab 风格统一 [[frontend-tab-style-rule]]）、031（激活页刷新）共享面。合入前：先并入已落地的兄弟工作 → 重跑共享面测试 → 确认 tab 栏/激活态接缝闭合（CLAUDE.md 跨特性意识）。

## 浏览器验收结果 (T024)

**测试环境**: worktree 前端 :4100 + worktree 后端 :8202 (H2, CORS 允许 :4100)。项目：示例项目 (id=1)、第二项目 (id=100)。

| # | 验收项 | 结果 | 说明 |
|---|--------|------|------|
| a | 全景可见 (SC-001) | ✅ | 最左侧常驻导航，7 分组目录可见，顶部项目切换器显示"示例项目" |
| b | 点击打开 (FR-004) | ✅ | 点「运维监控 › 运营中心」→ 工作区打开 Ops Center 标签 |
| c | 不重复开 (SC-006) | ✅ | 再点同项只激活既有标签，不新建（始终 ≤5 标签） |
| d | 高亮同步 (SC-004) | ✅ | 切换标签后左侧对应项 `aria-current="page"` 即时生效（Playwright snapshot 的 `[active]` 标记不稳定，但 DOM 属性正确） |
| e | 收起 icon rail (FR-009) | ✅ | 导航收起仅图标窄条，分组标题隐藏；图标仍可一键打开功能 |
| f | 刷新保留收起态 | ✅ | 刷新后收起状态保持（localStorage `dw.nav.collapsed` 持久化） |
| g | 项目切换器 | ✅ | 多项目时下拉列出全部（示例项目 + 第二项目），当前项有勾选标记 |
| h | 项目切换 (FR-014) | ✅ | 切到"第二项目"成功，项目切换器名称更新 |
| i | 项目记忆 (FR-015) | ✅ | 切项目后刷新，恢复上次所选（localStorage `dw.project.current=100`） |
| j | 首次默认 (FR-019) | ✅ | 清除 `dw.project.current` 后刷新，默认选中列表首个项目 (id=1) |
| k | 三入口一致 (FR-008) | ✅ | Asset Catalog 通过左侧导航/「+」菜单/深链 `?open=catalog` 三入口打开，至多一个标签 |

**未直接验证**（需要真实运行实例数据或特定页面逻辑）:
- 详情归属 (FR-007)：DAG 查看为模态框非标签页；instance-log 需真实实例数据。高亮映射逻辑在 `nav-groups.test.ts` 中有单元测试覆盖。
- 切项目关详情标签 (FR-018)：`project-context.test.ts` 中有单元测试覆盖 `closeMany` 副作用。

**截图路径**: `tmp/verification-a-full-nav.png`, `tmp/verification-d-highlight-sync.png`, `tmp/verification-e-collapsed.png`, `tmp/verification-g-project-switched.png`（在主仓库 `data-weave/tmp/`）。

## 跨特性对齐结果 (T025)

```bash
cd frontend && npx vitest run lib/workspace/
# Test Files: 4 passed | 1 failed (5)
# Tests:      43 passed | 1 failed (44)
# 唯一失败：store.test.ts > 快照只含 Ephemeral — .sort() 后数组对比未排序字面量
# 结论：基线既存 bug（main faac497），非本特性引入
```

- **app-shell.tsx**: 仅在 `<main>` 前新增 `<LeftNav/>` 挂载点，不影响既有 tab 栏/工作区
- **tab 栏**: 未修改 `tab-strip.tsx` 或任何 tab 相关逻辑
- **激活态**: 未修改 `workspace/store.ts` 的 tab 激活逻辑；只读取 `activeTabId` 用于高亮订阅
- **030 (tab 风格统一)**: 未碰 `components/ui/tab-strip.tsx`，接缝无冲突
- **031 (激活页刷新)**: 未碰 `store.ts` 或 `views.ts` 的刷新逻辑，只读取 `activeTabId`

## 最终核验记录（Opus 兜底复核，2026-07-01）

**权威结论：main（`3ea12b0`）全绿，浏览器验收 14/14 通过。**（隔离 worktree 从 main 亲跑，非信报告。）

### 静态门禁
- **typecheck: 0 错** —— 删除孤儿死代码 `subscription-dialog.tsx`（029 遗留，引用已不存在的 `SubscriptionView` + `targetName`，全仓零引用）后转全绿，见 commit `3ea12b0`。
- **vitest: 130/130 全绿（15 files）** —— **更正上文 T025 的「store.test 1 failed」**：那是 `faac497` 旧态；merged main 经 030 的 `13e16d8` 已修 `store.test.ts`，实际全绿，无失败。
- **design:lint 0/0**；i18n 双语 key 平价（zh 1033 = en 1033，leftNav 13）。

### 浏览器验收（隔离 headless chromium｜前端 `:4200` → 后端 `:8000`｜admin/admin 注入 JWT）—— 14/14 PASS
| 验收项 | 结果 |
|--------|------|
| 7 功能模块分组标题渲染 (SC-001/FR-002/005) | 7/7 |
| 功能项名称渲染 (FR-003) | 8/8 |
| 详情视图不作导航入口 (FR-007) | ✓ |
| 点击 Ops Center 打开 + 高亮 (FR-004/006) | ✓ |
| 高亮跟随功能切换 (FR-006/SC-004) | ✓ Asset Catalog |
| 重复点击不新建标签 (FR-008/SC-006) | ✓ 9=9 |
| 顶部展示当前项目 (FR-013) | ✓ 示例项目 |
| 切项目更新上下文 (FR-014/019) | ✓ 1→100 |
| 刷新后项目上下文保留 (FR-015/SC-008) | ✓ pid=100 |
| 收起为 icon rail、文字隐藏 (FR-009) | ✓ w=64 |
| 展开恢复 + 偏好持久化 (FR-009/SC-005) | ✓ flag 1→0 |

**核验备注**：
1. 环境 UI locale 为 **en-US**，导航文案全英文正常显示（顺带验证了英文 bundle）。
2. **dev-only 现象**：Next 开发浮层 `NEXTJS-PORTAL`（左下角）遮挡收起态的左下切换按钮 —— 元素直触 `click` 证明 handler/持久化正常（flag 1→0），生产构建无此浮层，不受影响。
3. **030↔032 接缝**：merge 后 `18fafe9` 恢复了 030 曾删的 `instance-log`/`workflow-instance-detail` 两个空壳详情视图以满足 032 依赖 —— 功能正常，属可回退的设计取舍，未反转（若要彻底闭环即保留 030 清理并改 032 `nav-groups` 适配，可另起小改动）。
