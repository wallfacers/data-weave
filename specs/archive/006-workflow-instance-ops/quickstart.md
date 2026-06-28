# Quickstart Validation Guide: 工作流实例运维操作

**Date**: 2026-06-26

## 前置条件

```bash
# 后端 (H2 模式，无需 Docker)
cd backend
./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2 &

# 前端
cd frontend
pnpm dev
```

- 后端: `http://localhost:8000`
- 前端: `http://localhost:4000`
- 登录: 使用测试用户（JWT 由 AuthProvider 自动注入）

---

## 验证场景

### VS-1: 工作流实例详情展示 env 字段

1. 打开前端 → Ops 视图 → "实例" 标签 → "工作流实例" 子标签
2. 确认实例列表中出现 `env` 列，显示 PROD 或 DEV
3. 点击某行进入 DAG 弹窗，确认详情中展示环境信息

**验收标准**: env 字段在列表和详情中均可见

### VS-2: 停止运行中的工作流实例

1. 在实例列表中找到状态为 RUNNING 的工作流实例
2. 点击行进入 DAG 弹窗 → 点击"停止"按钮
3. 确认弹窗提示确认 → 确认后实例状态变为 STOPPED
4. 确认 DAG 中所有非终态节点变为 STOPPED

**验收标准**: 实例和子任务全部变为 STOPPED，已完成的节点不受影响

### VS-3: 重跑失败的工作流实例（两种模式）

1. 找到状态为 FAILED 的工作流实例（需有部分 SUCCESS 节点）
2. 点击"重跑全部"→ 确认所有节点（含原本 SUCCESS 的）变为 WAITING
3. 等待运行到再次失败 → 这次点击"从失败点恢复"
4. 确认仅 FAILED 节点和下游节点变为 WAITING，原本 SUCCESS 的节点未被重置

**验收标准**: "重跑全部"重置所有节点；"恢复"保留已成功的节点

### VS-4: 运行中实例拒绝重跑

1. 找到状态为 RUNNING 的工作流实例
2. 尝试点击"重跑"按钮（应不可见或禁用）
3. 直接调用 API: `curl -X POST http://localhost:8000/api/ops/instances/{id}/rerun -H "Authorization: Bearer $TOKEN"`
4. 确认返回错误：`code:2, msg: "仅终态实例可以重跑"`

**验收标准**: 后端拒绝非终态实例的重跑请求

### VS-5: 置成功单个任务节点

1. 找到包含 FAILED 任务节点的工作流实例
2. 在 DAG 弹窗中点击失败节点 → 侧面板出现
3. 点击"置成功"按钮 → 确认后该节点变为 SUCCESS
4. 确认下游 WAITING 节点开始被调度

**验收标准**: 单个节点变为 SUCCESS，下游被唤醒

### VS-6: 暂停/恢复工作流实例

1. 找到状态为 RUNNING 的工作流实例
2. 点击"暂停"→ 确认实例变为 PAUSED，NOT_RUN 节点变为 PAUSED
3. 点击"恢复"→ 确认实例变为 RUNNING，PAUSED 节点变为 NOT_RUN

**验收标准**: 暂停/恢复状态转换正确，已运行节点不受影响

### VS-7: 批量操作上限

1. 在任务实例面板中启用多选
2. 尝试选中超过 100 个实例
3. 确认批量操作栏显示提示："最多选中 100 个实例"
4. 选中 ≤100 个 → 批量操作按钮可正常使用

**验收标准**: 超过 100 个时操作被禁用并有提示

### VS-8: DEV 环境仅显示停止

1. 找到 env=DEV 的工作流实例（或通过画布试运行创建一个）
2. 确认操作按钮仅显示"停止"，不显示重跑/置成功/暂停/恢复
3. 直接调用 API: `curl -X POST http://localhost:8000/api/ops/instances/{dev_id}/rerun ...`
4. 确认返回错误：`code:2, msg: "DEV 环境实例不支持此操作"`

**验收标准**: DEV 实例仅暴露停止操作，前后端双重校验

### VS-9: 审计日志验证

1. 执行任意写操作（停止/重跑/置成功）
2. 查询数据库: `SELECT action_type, outcome, created_at FROM agent_action ORDER BY created_at DESC LIMIT 5`
3. 确认操作记录存在，包含正确的 action_type 和 EXECUTED outcome

**验收标准**: 每个写操作都有审计记录

### VS-10: 并发操作冲突

1. 准备一个 RUNNING 状态的工作流实例
2. 两个浏览器 Tab 同时对该实例执行"停止"和"重跑"（重跑会被拒绝因为是 RUNNING，停止应成功）
3. 更实际的测试: 两个 Tab 同时对实例执行"停止"
4. 确认其中一方收到并发冲突错误提示

**验收标准**: 并发操作无静默数据不一致，失败方有明确错误提示

---

## 浏览器验证门 (必须)

```bash
# 使用 Playwright 验证关键流程
cd frontend
npx playwright test --grep "ops-instance" --project=chromium
```

验证要点:
- 操作按钮可见且可点击（非空白）
- 操作确认对话框正确弹出
- 操作后状态实时更新（通过 SSE 或轮询）
- 无 console error
