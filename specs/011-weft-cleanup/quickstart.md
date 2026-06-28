# Quickstart: 清理后验证清单

**Feature**: 011-weft-cleanup | **Date**: 2026-06-28

净化任务无新功能上手。本文件是清理完成的验证手册——每批删完 + 全部完成后按此核对，确认零行为回归（FR-010）与宪法 IV 红线未被触碰。

## 全局构建验证（SC-001）

```bash
# 后端（PostgreSQL 默认；或 -Dspring-boot.run.profiles=h2 免 Docker）
cd backend && ./dev-install.sh                 # 零错误

# 前端
cd frontend && pnpm typecheck                  # 零错误

# i18n 双 bundle 一致 + 无孤儿（SC-005）
node scripts/check-i18n.mjs

# 测试
cd backend && ./mvnw test -q
cd frontend && pnpm test
```

## 浏览器回归（SC-007，宪法 IV 红线 / FR-012）

启动 backend（`:8000`）+ frontend（`:4000`），浏览器验证（admin/admin 登录注入 JWT）：

- [ ] **run logs 流**：实例日志 SSE 正常推送
- [ ] **DAG 实例视图**：节点/边渲染正常
- [ ] **lineage 视图**：placeholder 状态不被破坏（删 `lineage-graph.tsx` 孤儿组件后）
- [ ] **Workspace 多标签**：开/关/pin 正常
- [ ] **log-panel tab 标题**（批次 4 后）：`taskId → name` 正确显示

## 引用归零（SC-002）

```bash
grep -rn "ChatFile\|AgentReply" backend/                  # 批次1
grep -rn "ApiMvpWorkerExec" backend/                      # 批次3
grep -rn "com.dataweave.alert" backend/                   # 批次6
grep -rn "freeze_task\|setFrozen" backend/                # 批次5
grep -rn "/api/ops/tasks" frontend/ backend/              # 批次4
# 前端孤儿组件名
grep -rn "InstanceTable\|LogViewerPanel\|TaskDefList\|TaskSearchBar\|SettingsTrigger" frontend/components/
```
所有 grep 应**零命中**（排除已删文件）。

## 路由不重复（SC-003，批次 3）

单进程模式（`scheduler.mode=all-in-one`）启动，确认 `POST /internal/worker/exec` 仅 `WorkerExecController` 一处注册（MVP 桩 `ApiMvpWorkerExecController` 已删）。

## alert 启动检查（批次 6）

`@EnableJdbcRepositories` 移除 `com.dataweave.alert.domain` 后，应用启动**不报** Spring Data 扫描异常。

## specs 归档（SC-004，批次 7）

- `specs/` active 区仅含 `011-weft-cleanup`
- `specs/archive/` 含 `001-010`
- `.specify/feature.json` 指向 `011`

## 量化核对（SC-006）

```bash
git diff --stat main..HEAD   # 净减少约 2800 行（主代码 + 测试 + 骨架）
```

## 成功标准总表

| SC | 标准 | 验证方式 |
|----|------|---------|
| SC-001 | 构建零错误 | 上方构建命令 |
| SC-002 | 孤儿引用归零 | 上方 grep |
| SC-003 | 路由不重复 | 启动检查 |
| SC-004 | specs 归档 | 目录检查 |
| SC-005 | i18n 无孤儿 | check-i18n.mjs |
| SC-006 | ~2800 行减少 | git diff --stat |
| SC-007 | observability 零回归 | 浏览器回归 |
