# Research: 项目级数据隔离全盘收口 (Phase 0)

## 现状基线（并行代码扫描产出）

### 已隔离 ✅
- `task_def`/`workflow_def`/`*_instance`/`datasources`/`catalog_node`/`tag`/指标定义表 均含 `tenant_id + project_id` 列。
- `ProjectSyncService.requireOwnedProject()` 已做项目守卫；`DatasourceRepository.findByTenantIdAndProjectIdAndDeleted`、`TaskDefRepository.findByProjectId` 等隔离方法已存在。
- 前端 `useProjectContext`（`lib/project-context.ts`）+ `ProjectSwitcher`（`left-nav.tsx`）已就位，`catalog-api`/`datasource-api` 已带 projectId。

### 泄漏/缺口 ❌（本特性问题域）
| 缺口 | 位置 | 级别 |
|------|------|------|
| `TenantContext` 无 projectId | `TenantContext.java:9-18`；`JwtAuthFilter:100-118` 不解析 | 🔴 地基 |
| Ops 实例裸查 | `OpsService.instances():192-198` `findAll()`；`OpsController:90-150` 无 project 参数 | 🔴 A |
| 指标裸查 | `MetricsController:39-44`；`MetricService.listLatest()` 全表 | 🔴 B |
| 血缘硬编码 | `LineageService.lineageOf():37-39` 写死 `1L,1L` | 🔴 B |
| 告警仅租户隔离 | `alert_rule/event/channel/route` 缺 `project_id`（schema:760-846） | 🟠 C |
| 质量无隔离列 | `quality_rule:988-1009`/`quality_check_run:1015-1036` 无 `project_id` | 🟠 C |
| cron_fire/sla_baseline 无隔离列 | schema:605-630 | 🟠 C（判定豁免） |
| 菜单/视图零权限过滤 | `left-nav.tsx:119-141`；`registry.tsx`；`auth.tsx:24-25` roles/permissions 未用 | 🟠 D |
| 前端 ops 视图不传 projectId | `ops/*-panel.tsx` fetcher | 🔴 A |
| metrics/freshness/alerts 无日期或不传 project | 各 view | 🔴 B/A |

## 关键裁决 (Decisions)

### D1：地基先行、契约冻结、四路只消费
- **Decision**：`TenantContext.projectId` + `JwtAuthFilter` 解析 + 统一作用域校验入口，由收尾方**先落地并冻结**，作为 Phase 2 Foundational（阻塞所有故事）。
- **Rationale**：这是唯一的全局共享耦合点。若 4 路各改地基必冲突（参考 CLAUDE.md 并行隔离规则 + `mvnd-build-cache-stale` 教训）。
- **Alternatives rejected**：让每路自带隔离参数透传 → 语义分裂、越权校验重复且易漏。

### D2：projectId 从何而来
- **Decision**：优先从 JWT claim / 请求头（前端从 `useProjectContext` 注入，如 `X-Project-Id` 或查询参数 `projectId`）解析，`JwtAuthFilter` 校验其属于当前 tenant 且用户为成员后置入 `TenantContext`；越权 → `project.forbidden`，缺失 → `project.required`。
- **Rationale**：当前项目是**用户运行时选择**（可多项目），不能只从 token 静态取；但必须服务端校验成员归属，防越权探测。
- **Open**：请求头 vs 查询参数的最终形态由地基实现时定，契约对四路暴露的是 `TenantContext.projectId()`，与传输形态解耦。

### D3：schema 补列单路独占
- **Decision**：所有 `schema.sql` 改动集中在 C 路一条工作流，避免多路同改同文件。
- **Rationale**：`schema.sql` 是最大冲突面（单一权威 DDL），且升版号需串行。

### D4：cron_fire / sla_baseline 隔离归属
- **Decision**：判定为**平台级/调度护栏对象**倾向豁免——`cron_fire` 是防重去重表（按 workflow 维度），加隔离列可能破坏跨 master 去重语义。C 路需实测：若加列不破坏调度死锁四不变量则补列，否则在 schema.sql 注释文档化豁免理由。
- **Rationale**：CLAUDE.md 硬约束——调度不变量是红线，隔离让位于正确性。

### D5：日期能力复用 ops bizDate 模型
- **Decision**：为缺日期的视图（metrics/freshness）补齐 **ops 既有 `bizDate` 模型**（T-1 兜底、yyyy-MM-dd），不引入新的全局日期选择器架构。
- **Rationale**：一致性优先；全局日期器是更大改动，US1 集成时若证明必要再评估。

### D6：角色集与授权复用现有闸门
- **Decision**：角色 OWNER/EDITOR/VIEWER（若表/`data.sql` 已有枚举以其为准）；写操作授权复用 `GatedActionService`/`PolicyEngine`，不新造授权框架。
- **Rationale**：CLAUDE.md「写操作过闸门，零 bypass」。

## 风险与依赖
- **R1**：地基契约若冻结后需变更 → 四路返工。缓解：契约只暴露 `TenantContext.projectId()` + 校验入口，最小面。
- **R2**：C 路 schema 升版与其他路合并顺序 → 集成时 **C 先合**。
- **R3**：H2/PG 方言差异（既往 `alert CALL IDENTITY` 假绿教训）→ C 路强制两库各测 + GeneratedKeyHolder。
- **R4**：neo4j 血缘真验需 etl-neo4j（B 路）。
