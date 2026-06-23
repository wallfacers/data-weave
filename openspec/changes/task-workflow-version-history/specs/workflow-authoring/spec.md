## MODIFIED Requirements

### Requirement: 工作流定义 CRUD

系统 SHALL 提供工作流定义的完整 CRUD 能力。用户 MUST 能创建草稿工作流、按名称/状态分页搜索、查看详情、编辑（仅 `DRAFT` 或 `has_draft_change` 态可改调度配置）、软删除、下线。创建的工作流初始 `status=DRAFT`、`current_version_no=0`、`has_draft_change=1`。所有写操作 MUST 经 `GatedActionService` 闸门并留痕。

`GET /api/workflows/{id}` 的响应结构 MUST 为 `WorkflowDetail { workflow: WorkflowDef, versions: WorkflowDefVersion[] }`，`versions` 按 `version_no DESC` 排序，与任务 `TaskDetail` 对称。

#### Scenario: 创建草稿工作流
- **WHEN** 用户 POST `/api/workflows` 提交名称与调度配置
- **THEN** 系统创建一条 `workflow_def`，`status=DRAFT`、`current_version_no=0`、`has_draft_change=1`，返回新 id

#### Scenario: 分页搜索工作流
- **WHEN** 用户 GET `/api/workflows?keyword=&status=&page=0&size=20`
- **THEN** 系统返回匹配的分页结果（content + totalElements + totalPages）

#### Scenario: 软删除工作流
- **WHEN** 用户 DELETE `/api/workflows/{id}`
- **THEN** 系统将该工作流 `deleted=1`，后续搜索/读取不再返回，且不物理删除其历史版本

#### Scenario: 获取工作流详情含版本列表
- **WHEN** 用户 GET `/api/workflows/1`
- **THEN** 系统返回 `{ "workflow": {...}, "versions": [v3, v2, v1] }` 结构
