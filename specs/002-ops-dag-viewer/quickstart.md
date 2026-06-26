# Quickstart: 运维任务流 DAG 查看器

**Feature**: 002-ops-dag-viewer | **Date**: 2026-06-26

## 本地开发启动

```bash
# 1. 后端 (已有分支无需额外操作，新增端点编译即可)
cd backend
docker compose up -d                     # PostgreSQL + Redis
./mvnw install -DskipTests               # 首次或领域层变更后
./mvnw -pl dataweave-api spring-boot:run # 默认 mock mode 即可

# 2. 前端
cd frontend
pnpm install
pnpm dev                                 # http://localhost:4000
```

## 验证流程

### 前提条件
- 至少有一个状态为 ONLINE 的周期任务流或手动任务流
- 该任务流在发布时包含至少 1 个节点

### 手动验证步骤

1. 打开浏览器 → `http://localhost:4000`
2. 进入 Ops 中心 (Tab: Ops)
3. 切换到"周期任务流"或"手动任务流"子 Tab
4. 找到 ONLINE 状态的任务流行
5. 确认该行存在"查看 DAG"按钮
6. 点击"查看 DAG"按钮
7. 弹框打开，展示发布版 DAG：
   - 节点按发布时记录的坐标布局
   - 连线展示依赖关系（强依赖实线，弱依赖虚线）
   - 鼠标滚轮缩放，拖拽画布平移
8. 按 `Escape` / 点击遮罩 / 点击关闭按钮 → 弹框关闭

### DRAFT 任务流验证
1. 找到 DRAFT 状态的任务流 → 确认无"查看 DAG"按钮（或置灰）

### 空 DAG 验证
1. 发布一个无节点的任务流
2. 点击"查看 DAG" → 弹框展示空状态提示

### 错误重试验证
1. 打开浏览器 DevTools Network 面板
2. 点击"查看 DAG"后立即阻断请求（offline 模式或 block URL）
3. 确认弹框内显示错误信息 + 重试按钮
4. 恢复网络，点击重试 → DAG 正常加载

## 关键文件

| 文件 | 变更类型 | 职责 |
|------|---------|------|
| `WorkflowController.java` | 修改 | 新增 `GET /api/workflows/{id}/published-dag` |
| `WorkflowService.java` | 修改 | 新增 `readPublishedDag()` |
| `dag-viewer-dialog.tsx` | **新增** | 只读 DAG 弹框组件 |
| `periodic-workflows-panel.tsx` | 修改 | 按钮 onClick → 打开弹框 |
| `manual-workflows-panel.tsx` | 修改 | 按钮 onClick → 打开弹框 |
| `nodes/task-node.tsx` | 提取 | 从 workflow-canvas-view 提取 TaskNode |
| `nodes/virtual-node.tsx` | 提取 | 从 workflow-canvas-view 提取 VirtualNode |

## 测试

```bash
# 后端单元测试
cd backend
./mvnw -pl dataweave-master test -Dtest="WorkflowServiceTest#readPublishedDag"
./mvnw -pl dataweave-api test -Dtest="WorkflowControllerTest#readPublishedDag"

# 前端组件测试
cd frontend
pnpm vitest run -- dag-viewer-dialog

# E2E 浏览器验证 (Playwright)
# 见 CLAUDE.md 浏览器验证闸门
```
