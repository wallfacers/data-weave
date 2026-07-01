# Contract: 项目列表（复用，无新端点）

**Feature**: 032-project-nav | **Date**: 2026-07-01

本特性**不新增后端端点**。项目切换器复用既有 `GET /api/projects`（`ProjectController.list`）。本文档固化前端消费契约。

---

## GET /api/projects

列出当前租户下的项目。租户由 JWT 经 `TenantContext.tenantId()` 解析，服务端强隔离（前端不传 tenant）。

### Request

- **Method**: `GET`
- **Path**: `/api/projects`
- **Auth**: `Authorization: Bearer <jwt>`（既有鉴权）
- **Query**: 本特性使用**无参**形态（拿当前租户全量项目）。
  - （既有可选参数 `search/status/ownerId/page/size` 触发分页形态，本期不使用。）

### Response 200 —— 无参形态（本期消费）

`ApiResponse<Project[]>`：

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    { "id": 1, "tenantId": 1, "code": "default", "name": "默认项目", "ownerId": 1, "status": "ACTIVE",
      "createdAt": "2026-06-01T10:00:00", "updatedAt": "2026-06-01T10:00:00" }
  ]
}
```

前端只读取 `id`、`name`、`code`、`status`（见 data-model `ProjectVO`）。

### 前端契约约定

- **排序**：以服务端返回顺序为「稳定排序」基准；FR-019 默认项目 = `data[0]`。若需确定性，前端可按 `id` 升序兜底排序（不改变集合）。
- **空列表**（`data: []`）：切换器进入 `empty` 态，导航顶部给空态提示，不报错（FR-017）。
- **单元素**：切换器只读/禁用态展示该项目名（FR-017）。
- **错误/401**：沿用既有 api 客户端错误处理；切换器 `error` 态，不阻塞导航其余部分（功能目录仍可用）。

### 隔离保证（宪法 II）

- 返回集已按 `tenant_id` 过滤，前端无法获得/选择本租户外项目。
- 选中某 `projectId` 后，各数据 API 仍各自按 `tenant_id`(+`project_id`) 隔离；越权由后端拒绝，前端切项目不绕过任何隔离。

### 后续项（非本期）

- 成员级过滤（仅返回用户为成员的项目）：需后端在 `list` 加 `project_members` JOIN；前端契约不变（D1）。

---

## 前端封装

`lib/project-api.ts`

```ts
import { get } from "@/lib/api"            // 既有 api 客户端
import type { ProjectVO } from "@/lib/types"

export async function listProjects(): Promise<ProjectVO[]> {
  return get<ProjectVO[]>("/api/projects")  // 无参 → 租户全量
}
```

> 取数维度传递：现有 `catalog-api`/`datasource-api` 的 `projectId` 实参改为从 `useProjectContext.getState().currentProjectId` 读取（默认兜底保持兼容），不改各端点自身契约。
