# Quickstart: 监督席

**Feature**: 064-supervisor-desk
**Date**: 2026-07-11

## 本地开发启动

```bash
# 1. 后端（H2 模式，零外部依赖）
cd backend
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2

# 2. 前端
cd frontend
pnpm dev
# → http://localhost:4000
```

## 验证监督席页面

1. 浏览器打开 `http://localhost:4000`，登录 (admin/admin)
2. 左侧导航 → 运维 (ops) → 监督席 (incidents)
3. 应看到两个 Tab：「信号流」+「工单队列」
4. 无 seed 数据时显示空态

## 注入测试信号

```bash
# 通过 MCP 或直接 API 调用注入测试信号
curl -X POST http://localhost:8000/api/events/test-signal \
  -H "Content-Type: application/json" \
  -d '{
    "type": "TASK_FAILED",
    "severity": "HIGH",
    "taskId": 100,
    "taskName": "FLINK Streaming Savepoint (062)",
    "failureReason": "EXIT_CODE_-1"
  }'
```

## 关键文件

| 文件 | 作用 |
|---|---|
| `frontend/components/workspace/views/incidents-view.tsx` | 主视图（Tabs 切换） |
| `frontend/components/workspace/views/incident/signal-stream-panel.tsx` | 信号流面板 (NEW) |
| `frontend/components/workspace/incident-timeline-dialog.tsx` | 时间线 Dialog (NEW) |
| `frontend/components/workspace/views/incident/actions.tsx` | TimelineDrawer→删除，SuppressDialog/NoteInput 保留 |
| `frontend/components/workspace/views/incident/triage.tsx` | Badge 组件名替换 |
| `backend/.../Incident.java` | +healByType/healByRefId |
| `backend/.../IncidentService.java` | openOrAttach 存愈合条件; healByTask 精确匹配 |
| `backend/.../IncidentSignalListener.java` | signature 改用原始 failureReason |
| `backend/.../schema.sql` | DDL +2 列，版本 0.11.0 |

## 验证清单

- [ ] `pnpm typecheck` 零错误
- [ ] 信号流 Tab 使用 `DwScroll`，无 `overflow-auto`
- [ ] 工单卡片使用 `Card` + `--card-spacing`，无硬编码 `p-4`
- [ ] Tab 使用 `<Tabs>` + `<TabsList>` + `<TabsTrigger>`，无手写 `role="tablist"`
- [ ] 时间线抽屉使用 `Dialog` + `DetailPanelShell`，无 `fixed right-0`
- [ ] Badge 使用语义变体（`destructive`/`warning`/`info`/`success`），无手写颜色 class
- [ ] 加载态使用 `LoadingState`，无手写"加载中..."
- [ ] `./mvnw -pl dataweave-master test` 全绿
- [ ] `pnpm vitest run` 全绿
- [ ] i18n zh-CN/en-US 双文件 key 集一致
