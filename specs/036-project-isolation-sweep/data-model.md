# Data Model: 项目级数据隔离全盘收口 (Phase 1)

## 请求级上下文 (Foundation)

### ProjectScope（概念实体，请求级）
| 字段 | 类型 | 来源 | 说明 |
|------|------|------|------|
| tenantId | Long | JWT claim | 已有 |
| userId | Long | JWT claim | 已有 |
| username | String | JWT claim | 已有 |
| **projectId** | Long | 请求头/参数 + 服务端成员校验 | **本特性新增** |
| **role**（可选缓存） | enum OWNER/EDITOR/VIEWER | project_member 解析 | US4 使用 |

- 承载于 `TenantContext`（ThreadLocal），`JwtAuthFilter`/`McpAuthFilter` set、`finally` clear。
- 校验入口：`require(projectId)` → 属当前 tenant 且用户为 `project_member` 才通过；否则抛 `BizException`。

## Schema 补列（C 路，仅改 `schema.sql`）

新增 `project_id BIGINT` 列（+ 索引 `idx_<table>_tenant_project`），回填存量到租户默认项目：

| 表 | 现状 | 变更 |
|----|------|------|
| `alert_rule` | 仅 tenant_id | +project_id +idx |
| `alert_event` | 仅 tenant_id | +project_id +idx |
| `alert_channel` | 仅 tenant_id | +project_id +idx |
| `alert_route` | 仅 tenant_id | +project_id +idx |
| `quality_rule` | 无隔离列 | +project_id +idx |
| `quality_check_run` | 无隔离列 | +project_id +idx |
| `cron_fire` | 无隔离列 | +project_id +idx（仅追加 WHERE 过滤，不改 join/lock） |
| `sla_baseline` | 无隔离列 | +project_id +idx |

- **回填**：`UPDATE <t> SET project_id = (该租户最早创建的项目 id) WHERE project_id IS NULL`，幂等；确保无孤儿。
- **schema_version**：当前 `0.4.0` → 升至 `0.5.0`（新增隔离列，MINOR）；库内 INSERT / 文件头注释 / 项目版本三处恒等。
- **方言**：PG + H2 均 `IF NOT EXISTS`；自增主键用 `GeneratedKeyHolder`（禁 H2 旧 `CALL IDENTITY`）。

## 角色/权限（D 路，表已就位）
- `project_member(tenant_id, project_id, user_id, role)` → 解析用户在当前项目的角色。
- `roles`/`role_permission`/`permissions` → 角色到权限集。
- 权限集驱动：前端菜单/视图可见性 + 后端写端点授权。

## 隔离过滤模式（各域统一）
- 读：repo 方法 `findByTenantIdAndProjectId...(ctx.tenantId, ctx.projectId, ...)`，消除 `findAll()`。
- 写：注入 `projectId = ctx.projectId()`。
- 联合日期：`... AND biz_date BETWEEN ? AND ?`（A/B 运行态与指标）。

## 前端数据形态
- 受隔离请求统一附 `projectId = useProjectContext.currentProjectId()`。
- 详情 tab `params` 透传 `projectId`；切项目关闭失效参数化 tab（复用 032 FR-018）。
- 无数据 → 明确空态，禁借显他项目/他日期。
- **bizDate 按项目独立**：切项目重置为 T-1，返回原项目恢复上次日期（前端维护 `Map<projectId, bizDate>`）。
