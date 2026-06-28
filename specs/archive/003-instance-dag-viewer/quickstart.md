# Quickstart: 运营中心实例列表切换与 DAG 查看

**Feature**: 003-instance-dag-viewer

## Prerequisites

- PostgreSQL running (`docker compose up -d`)
- Backend built (`cd backend && ./dev-install.sh`)
- Frontend deps installed (`cd frontend && pnpm install`)

## Quick Test Flow

### 1. Start Backend

```bash
cd backend
./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run
# Backend at :8000
```

### 2. Seed Test Data

Ensure `data.sql` has workflow instances with task instances. Run a manual workflow trigger to create instances:

```bash
curl -X POST http://localhost:8000/api/workflows/1/run
```

### 3. Test Workflow Instance List API

```bash
# List all workflow instances
curl http://localhost:8000/api/ops/workflow-instances?page=1&size=10

# Filter by state
curl "http://localhost:8000/api/ops/workflow-instances?state=RUNNING&page=1&size=10"

# Filter by bizDate range
curl "http://localhost:8000/api/ops/workflow-instances?bizDateFrom=2026-06-01&bizDateTo=2026-06-26&page=1&size=10"
```

### 4. Test Instance DAG API

```bash
# Get instance DAG (replace UUID with actual)
curl http://localhost:8000/api/ops/workflow-instances/<uuid>/dag
```

### 5. Test Resolved Code API

```bash
# Get resolved code for a task instance (replace UUID with actual)
curl http://localhost:8000/api/ops/task-instances/<uuid>/resolved-code

# Get resolved config
curl http://localhost:8000/api/ops/task-instances/<uuid>/resolved-config
```

### 6. Start Frontend & Verify

```bash
cd frontend
pnpm dev
# Open http://localhost:4000/?open=ops
```

In the ops view:
1. Instance tab should show "任务实例 | 任务流实例" toggle
2. Click "任务流实例" → list of workflow instances loads
3. Click a row → Instance DAG dialog opens with runtime node states
4. Click a DAG node → side panel with "实际代码" / "实际配置" tabs
5. Verify parameter substitution in actual code

## Test with H2 (No Docker)

```bash
cd backend
./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
```

Same API endpoints work against H2 in-memory database.

## Key Files to Check During Development

| What | Where |
|------|-------|
| Backend list query | `OpsService.queryWorkflowInstances()` |
| Backend DAG endpoint | `OpsController.getWorkflowInstanceDag()` |
| Backend resolved code | `OpsController.getResolvedCode()` |
| Frontend toggle | `ops-view.tsx` instance tab |
| Frontend WF instance list | `workflow-instances-panel.tsx` |
| Frontend DAG dialog | `instance-dag-dialog.tsx` |
| Frontend side panel | `instance-detail-side-panel.tsx` |
| Frontend types | `lib/types.ts` |
