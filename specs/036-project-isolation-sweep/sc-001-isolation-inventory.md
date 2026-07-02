# SC-001 受隔离接口全盘清单（036 收口）

> 收口方产出（Phase 7 / T057）。分三类：**A 已隔离**（本特性前已按项目/租户收敛）·
> **B 本次收口**（036 及本轮闭环新增项目隔离，每项对应测试）· **C 平台级豁免**（结构性
> 不做项目隔离，附理由）。判定口径：读路径按 `TenantContext.projectId()` 或 `ProjectScope`
> 成员校验收敛；写路径经成员校验 + 归属守卫，跨项目 0 泄漏 / 0 越权改删。

## 地基（FND）

| 面 | 机制 | 测试 |
|----|------|------|
| `TenantContext.projectId` | 4 参 `set` + 访问器 | `ProjectScopeTest` |
| `JwtAuthFilter` 解析项目 | `X-Project-Id` 头 / `?projectId=`，缺则 null（**本轮修 NPE：null 不再 put exchange，避免 ConcurrentHashMap NPE**） | `JwtAuthProjectContextTest`（无项目上下文请求不再 500） |
| `ProjectScope.require` | `project_member` 真成员校验：缺→`project.required`，非成员→`project.forbidden` | `ProjectScopeTest` |

## B — 本次收口（036 + 本轮闭环）

| 模块 | 接口 | 收敛维度 | 测试 |
|------|------|----------|------|
| Ops (US1) | `/api/ops/instances`·`workflow-instances`·`summary`·`failed`·`backfill`·`eta-summary`·`periodic-workflows` | `(projectId, bizDate)` + `ProjectScope.require` | `OpsProjectIsolationTest` |
| Metrics (US2) | `/api/metrics`（`listLatest`/`findLatestByCode`）+ bizDate 观察 | `projectId` | `MetricProjectIsolationTest` |
| Lineage (US2) | `/api/lineage/*` | 去硬编码 `1L,1L`，按 `TenantContext` | `LineageServiceContextTest`·`LineageGraphEndpointTest` |
| Freshness (US2) | `/api/freshness` | SQL WHERE `tenant_id AND project_id` | `FreshnessProjectIsolationTest` |
| Alert (US3) | `/api/alert/rules·channels·routes·events` list + create | 项目级 + `ProjectScope.require` | `AlertRuleProjectIsolationTest` |
| **Alert by-id (T045b, 本轮)** | rules/channels/routes/events 的 `get·update·delete`（及 channel `test`、event `ack`/notifications） | **`requireOwned` 归属守卫**：跨项目按 id 读/改/删 → `project.forbidden` | `AlertCrossProjectGuardTest` |
| Quality (US3) | `/api/quality/*` | `findByTenantIdAndProjectId` | （repo 隔离；接线随 alert 套路） |
| Schema (US3) | `cron_fire`/`sla_baseline` 补 `project_id` 列 + 索引 + 回填 | 统一补列，仅追加 WHERE 过滤不破调度不变 | `CronFireSchemaMigrationTest` |
| Roles (US4) | `/api/projects/{id}/me`、`addMember`/`removeMember`/`update`/`delete` | `ProjectRoleService` `project:manage` 授权，越权→`project.role.forbidden` | `ProjectRoleServiceTest`·`ProjectRoleAuthzTest`·前端 `nav-permissions.test.ts` |
| **Task 定义写 (US4/D1)** | `POST/PUT/DELETE /api/tasks`、`publish`/`offline`/`rollback`/`catalog` | `ProjectAuthz` `task:manage`（create 按当前项目+打戳；by-id 按实体归属 projectId，跨项目→`project.forbidden`） | `TaskRoleAuthzTest`(5) |
| **Workflow 定义写 (US4/D1)** | `POST/PUT/DELETE /api/workflows`、`dag`/`draft`/`publish`/`offline`/`rollback`/`dependencies`/`catalog` | `ProjectAuthz` `workflow:manage`（同上模式） | `WorkflowRoleAuthzTest`(4) |
| **指标市场写 (US4/D2)** | `POST /api/marketplace/metrics`、`certify`/`DELETE`/`reuse` | `ProjectAuthz` `metric:manage` + 去 `defaultValue=1`（resolveProjectId 回落 TenantContext），闸门前置门 | `GovernanceRoleAuthzTest`(6) |
| **审批 (US4/D2)** | `POST /api/approvals/{id}/approve`/`reject` | `ProjectAuthz` `project:manage`（OWNER only；`agent_action` 无 project_id 列→按请求头项目，补列属 C 面接缝） | `GovernanceRoleAuthzTest` |
| **项目 push (US4/D2)** | `POST /api/projects/{id}/push` | `ProjectAuthz` `task:manage`（定义写入=EDITOR+；pull/diff 只读不接；MCP project_push 直调 service 走自身闸门不受影响） | `GovernanceRoleAuthzTest` |

## C — 平台级豁免（结构性不做项目隔离）

| 面 | 理由 |
|----|------|
| `McpAuthFilter` / MCP 工具 | MCP 无运行时项目选择，projectId 恒 null，仍按 `TenantContext.tenantId()` 租户隔离 |
| `GET /api/projects`（列项目） | 选中项目的前置入口，本身不能要求 `X-Project-Id`（否则无法列项目再选） |
| `/api/health`、CLI 白名单前缀 | filter 白名单放行，无身份上下文 |

## 本轮闭环补丁（Phase 7）

1. **cron_fire/sla_baseline 豁免反转 → 统一补列**（Session 2026-07-02 澄清）：均加 `project_id` 列 + 索引 + 回填，仅追加 WHERE 过滤不改 join/lock。→ T034b + `CronFireSchemaMigrationTest`。
2. **JwtAuthFilter NPE**：`projectId==null` 时不再 `exchange.getAttributes().put(...)`（ConcurrentHashMap put null → NPE，此前令所有无项目头请求 500）。→ `JwtAuthProjectContextTest`。
3. **T045b Alert by-id 归属守卫**：`AlertController.requireOwned` 覆盖 rules/channels/routes/events by-id 读改删，防跨项目越权。→ `AlertCrossProjectGuardTest`（6/6）。
4. **既有测试红修复**：`AlertRuleCrossTenantQueryTest` 内联建表补 `project_id` 列（036 给 repo insert 加了 project_id，漏改此既有测试的 DDL，`df4f8b5` 整合修复未覆盖）。

## 未纳入本轮（遗留，需独立跟进）

- Quality `Controller/Service` 端到端接线（repo 已隔离；tasks T042）与前端 `quality` 视图（T043）。
- 各故事的**浏览器真验**（T021/T032/T054）与 **neo4j 双项目血缘真验**（T032）。
- **PG/H2 双库回归**（T044）：本轮以 H2 profile 验证。
