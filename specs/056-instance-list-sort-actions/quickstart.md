# Quickstart: 实例列表排序 + 操作按钮状态化

## Backend: Quick Test

```bash
# 1. 按计划触发时间降序（默认）
curl "http://localhost:8000/api/ops/workflow-instances?projectId=1&page=1&size=5&sort=scheduledFireTime:desc"

# 2. 按 bizDate 升序
curl "http://localhost:8000/api/ops/workflow-instances?projectId=1&page=1&size=5&sort=bizDate:asc"

# 3. 按 startedAt 降序（查看最近运行的实例）
curl "http://localhost:8000/api/ops/instances?projectId=1&page=1&size=5&sort=startedAt:desc"

# 4. 不传 sort — 回退到现有优先级排序（FAILED 优先）
curl "http://localhost:8000/api/ops/instances?projectId=1&page=1&size=5"

# 5. 无效字段 — 安全回退，不报错
curl "http://localhost:8000/api/ops/workflow-instances?projectId=1&sort=invalidField:desc"
```

## Frontend: Quick Verify

```
1. pnpm dev → http://localhost:4000
2. 左侧导航 → Ops Center → Workflow Instances Tab
3. 验证：列表默认按 Scheduled 列倒序（最新在前）
4. 点击 "Started At" 列头 → ▲ 升序排列
5. 再次点击 → ▼ 降序排列
6. 切换到 Task Instances Tab → 重复验证
7. 查看 RUNNING 状态行 → 重跑按钮灰色不可点击
8. 查看 FAILED 状态行 → 所有按钮可用
9. 选中 1 条 RUNNING + 1 条 FAILED → 批量 Rerun 按钮禁用 + Tooltip
```
