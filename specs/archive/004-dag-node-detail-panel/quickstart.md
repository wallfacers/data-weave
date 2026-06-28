# Quickstart: DAG 节点详情侧面板

**Created**: 2026-06-26

## 前置条件

- 分支 `004-dag-node-detail-panel`
- 依赖 002-ops-dag-viewer（DAG 弹框）已实现
- PostgreSQL（`docker compose up -d`）或 H2 profile

## 本地开发

### 1. 启动后端

```bash
cd backend
# PostgreSQL 模式（推荐）
docker compose up -d
./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run

# 或 H2 模式（零外部依赖）
./dev-install.sh && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
```

### 2. 启动前端

```bash
cd frontend
pnpm dev
# → http://localhost:4000
```

### 3. 验证步骤

1. 确保数据库中有已发布（ONLINE）的任务流，且其 DAG 中至少包含一个 TASK 节点。
2. 打开 Ops 中心（`http://localhost:4000/?open=ops`）。
3. 在周期任务流或手动任务流列表中，找到 ONLINE 状态的任务流，点击"查看 DAG"。
4. 在 DAG 弹框的只读画布上，**点击**任意任务节点 → 右侧面板滑出，展示节点详情。
5. **右击**任务节点 → 上下文菜单出现 → 点击"查看任务详情" → 右侧面板展示相同内容。
6. 拖拽面板左侧分割线 → 面板宽度在 1/5~1/3 之间变化。
7. 点击画布空白区域或面板关闭按钮 → 面板关闭。

## 关键文件

| 文件 | 作用 |
|------|------|
| `frontend/components/workspace/dag-viewer-dialog.tsx` | DAG 弹框布局改造（flex-row 分栏） |
| `frontend/components/workspace/dag-renderer.tsx` | 允许 readOnly 模式下触发 onNodeClick |
| `frontend/components/workspace/node-detail-panel.tsx` | 右侧详情面板（新建） |
| `frontend/lib/workspace/node-detail-store.ts` | Zustand 面板状态管理（新建） |
| `backend/.../interfaces/OpsController.java` | 新增节点详情端点 |
| `backend/.../application/WorkflowService.java` | DagNodeDto 加 taskVersionNo，readPublishedDag 补传 |

## 测试

```bash
# 后端
cd backend
./mvnw -pl dataweave-api test -Dtest="NodeDetailEndpointTest"
./mvnw -pl dataweave-master test -Dtest="WorkflowServiceNodeDetailTest"

# 前端
cd frontend
pnpm vitest run --reporter=verbose

# 浏览器验证（必须）
# 按 §3 步骤在浏览器中完成交互验证
```
