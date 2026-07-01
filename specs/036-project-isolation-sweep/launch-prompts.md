# 036 项目隔离全盘收口 — 四路并发启动提示词

> 用法：把下面每个 **▶ 提示词** 整段复制给一个独立的 Claude Code CLI agent。四路并发。
> **前置硬约束（所有路共同遵守）**：
> 1. **地基先行**：`(tenantId, userId, projectId)` 三元组上下文契约由收尾方（本机主 Claude）先落地并冻结，见「地基契约」一节。四路 agent **只消费、不修改**地基文件（`TenantContext.java`、`JwtAuthFilter.java`、`McpAuthFilter.java`、前端 `lib/project-context.ts`）。若发现契约不满足需求，**回报收尾方**，不要自行改地基。
> 2. **worktree 隔离**：每路在自己的 git worktree 干活：`git worktree add ../dw-036-<X> -b 036-iso-<X>`，避免 SDD 指针与文件互相覆盖。
> 3. **验证门**：后端每改一处 `cd backend && ./mvnw -q -pl <module> compile`；前端 `cd frontend && pnpm typecheck`；长跑构建/测试用 `setsid` 脱离（见 CLAUDE.md WSL2 规则）。
> 4. **测试即完成**：每个收口点必须有"双项目不串数据"测试，无测试 = 未完成。
> 5. **读 CLAUDE.md + spec.md**：`specs/036-project-isolation-sweep/spec.md` 是需求真相源；隔离/i18n/闸门约定以 CLAUDE.md 为准。

---

## 地基契约（收尾方先行，冻结后广播给四路）

以下 API 冻结后四路直接调用，视为稳定：

- `TenantContext.projectId()` → 当前请求项目 id（Long）。
- 项目作用域校验入口（拟）`ProjectScope.require(projectId)` 或等价服务：校验当前用户是当前项目成员，越权抛 `BizException("project.forbidden")`；缺项目身份抛 `project.required`。
- Repository 隔离方法命名约定：`findBy...AndProjectId(...)` / `findByTenantIdAndProjectId...`。
- 前端：受隔离请求统一从 `useProjectContext.currentProjectId()` 取 projectId 作为查询参数；详情 tab 的 `params` 必须透传 `projectId`。
- 新错误码：`project.forbidden` / `project.required`（收尾方接入 `GlobalExceptionHandler` + 双语）。

---

## ▶ A 路：运维中心调度 + 运行态总览 + 实例表 + 日期联动

```
你是 data-weave 项目 036-project-isolation-sweep 特性的 A 路实现 agent。先读 specs/036-project-isolation-sweep/spec.md（覆盖 US1、FR-010/011）与根目录 CLAUDE.md。

在自己的 git worktree 工作：git worktree add ../dw-036-a -b 036-iso-a。

目标：让运维中心「调度 + 运行态总览 + 实例表」全部按 (当前项目 projectId, 业务日期 bizDate) 隔离与收敛，消除全表裸查，保持下钻联动的项目上下文。

后端（dataweave-master / dataweave-api）：
- OpsService.instances()、periodicWorkflows() 等当前用 findAll()/无隔离的查询，改为按 TenantContext.projectId() 过滤（用 Repository 的 findBy...AndProjectId 方法，缺则新增）。
- OpsController 的 instances / workflow-instances / periodic / backfill / summary / eta-summary 等端点，接入项目作用域（调用地基 ProjectScope 校验），只返回当前项目数据。
- 涉及 bizDate 的查询保证 (projectId, bizDate) 联合过滤。SSE 日志/DAG 事件流若跨项目可订阅，补项目校验。
- 严守调度死锁防御四不变量，不改 SKIP LOCKED / CAS / 锁顺序。

前端（frontend/components/workspace/views/ops/*）：
- periodic-instances-panel、workflow-instances-panel、backfill-panel 的 fetcher 追加 projectId（读 useProjectContext）。
- 下钻打开的详情 tab（DAG / instance-log / workflow-instance-detail）params 透传 projectId。
- 切换项目时失效参数化 tab 的行为复用 032 既有逻辑，勿重造。

测试：后端 WebTestClient + JWT（JwtTestSupport）造双项目实例，断言各 ops 端点跨项目 0 串；bizDate 切换收敛正确。前端 typecheck + 浏览器验证实例表切项目/切日期刷新。

不要碰：TenantContext/JwtAuthFilter/McpAuthFilter/lib/project-context.ts（地基），metrics/lineage/alert/quality/权限菜单（其他路）。地基 API 若不满足，回报收尾方。

每改一处即 ./mvnw -q -pl <module> compile / pnpm typecheck；长跑用 setsid 脱离。完成后输出：改动清单 + 测试结果 + 仍需收尾方处理的接缝。
```

---

## ▶ B 路：指标 + 血缘 + 时效 + 日期观察

```
你是 data-weave 项目 036-project-isolation-sweep 特性的 B 路实现 agent。先读 specs/036-project-isolation-sweep/spec.md（覆盖 US2、FR-012/013/016）与根目录 CLAUDE.md。

在自己的 git worktree 工作：git worktree add ../dw-036-b -b 036-iso-b。

目标：指标、血缘、时效(freshness) 三个读接口全部按当前项目隔离；指标看板支持按业务日期观察；移除血缘硬编码。

后端：
- MetricsController / MetricService.listLatest()/findLatestByCode() 当前全租户裸查，改为按 TenantContext.projectId() 过滤（atomic_metrics/derived_metrics 已有 project_id 列）。指标看板增加按 bizDate 观察的查询路径。
- LineageService.lineageOf() 当前硬编码 1L,1L，改为从上下文取 tenantId/projectId（neo4j 底座，查询按项目作用域）。
- freshness/时效 及其他含 project_id 列却未收口的读路径，改为按 projectId 过滤。

前端（frontend/components/workspace/views/）：
- metrics-view：请求带 projectId；加业务日期选择（复用 ops 的 bizDate 模型，勿造全新全局日期器），缺数据给明确空态。
- freshness-view：请求带 projectId。
- 血缘图视图（若有）：带 projectId。

测试：后端造双项目指标/血缘，断言接口跨项目 0 串；LineageService 用上下文而非常量的单测；指标按日期返回对应快照。前端 typecheck + 浏览器验证。neo4j 相关如需真验，直连 etl-neo4j。

不要碰：TenantContext/JwtAuthFilter/地基文件，ops panel（A 路），alert/quality（C 路），权限菜单（D 路）。地基 API 不满足则回报收尾方。

每改一处即 compile/typecheck；长跑 setsid。完成输出：改动清单 + 测试结果 + 接缝遗留。
```

---

## ▶ C 路：告警 + 质量 + Schema 迁移（加 project_id 列）

```
你是 data-weave 项目 036-project-isolation-sweep 特性的 C 路实现 agent。先读 specs/036-project-isolation-sweep/spec.md（覆盖 US3、FR-014/015/030/031）与根目录 CLAUDE.md（尤其 Schema 真相源、H2/PG 方言、schema_version 规则）。

在自己的 git worktree 工作：git worktree add ../dw-036-c -b 036-iso-c。

目标：把 alert_* 与 quality_* 从"仅租户隔离"升级为"项目隔离"，含建模补列与数据回填；并明确 cron_fire/sla_baseline 的隔离归属。

Schema（backend/dataweave-api/src/main/resources/schema.sql，单一权威 DDL，勿用增量脚本）：
- 给 alert_rule / alert_event / alert_channel / alert_route / quality_rule / quality_check_run 增加 project_id 列 + 索引。
- 存量数据幂等回填到其租户的默认项目（无孤儿行）。
- cron_fire / sla_baseline：判定是否平台级对象——若加隔离列不破坏调度四不变量则补列，否则在 schema.sql 注释文档化豁免理由（二选一并落地）。
- 升 schema_version（当前 0.4.0，按 SemVer；改表属 MINOR/PATCH 自行判定），保证库内/文件头/项目版本三处恒等。PG 与 H2 两方言 DDL 都要过（CONCAT 别用 ||、IF NOT EXISTS）。

后端（dataweave-alert / dataweave-master + api）：
- AlertRule/Event/Channel/Route 及 quality 的 Repository/Service/Controller 补 project_id 维度，查询按 TenantContext.projectId() 过滤，写入注入 projectId。
- 自增主键取值用 GeneratedKeyHolder（勿用 H2 旧 CALL IDENTITY，跨方言会假绿，见既往 bug）。

前端：alerts-view / 质量视图请求带 projectId。

测试：迁移后造双项目告警/质检，断言列表接口跨项目 0 串；回填无孤儿；PG+H2 各跑一遍 DDL；接缝测试别只数行数（造真数据断言归属）。

不要碰：TenantContext/地基，ops(A)/metrics/lineage(B)/权限菜单(D)。schema.sql 只改告警/质量/cron/sla 相关段，若需与他路共改 schema，回报收尾方协调（schema.sql 是最大冲突面）。

每改一处 compile；长跑 setsid。完成输出：schema diff + 升版号 + 双方言验证结果 + 测试结果。
```

---

## ▶ D 路：项目角色隔离 + 菜单隔离 + i18n

```
你是 data-weave 项目 036-project-isolation-sweep 特性的 D 路实现 agent。先读 specs/036-project-isolation-sweep/spec.md（覆盖 US4、FR-040~043/050）与根目录 CLAUDE.md（i18n 三条所有权规则、闸门约定）。

在自己的 git worktree 工作：git worktree add ../dw-036-d -b 036-iso-d。

目标：按用户在当前项目的角色（OWNER/EDITOR/VIEWER）做权限解析、前端菜单/视图隔离、后端写操作授权，切项目重算。

后端：
- 依据 project_member + role/role_permission 表，提供"当前用户在当前项目的角色/权限集"解析（读 TenantContext 的 tenantId/userId/projectId）。若已有既定角色枚举以其为准，勿新造。
- 受保护的写操作端点接入项目角色授权，越权返回结构化 BizException（复用现有 PolicyEngine/GatedActionService 语义，不弱化闸门），错误码稳定（如 project.role.forbidden）。
- 提供前端可用的"当前项目权限"查询接口（供菜单过滤）。

前端（frontend）：
- lib/auth.tsx 的 user.roles/permissions 当前未用；结合当前项目权限，在 LeftNav NavItem / registry VIEW_RENDER 处按权限过滤：无权限菜单不渲染、无权限视图不可直达。
- 切换项目触发权限重算（配合 useProjectContext）。
- VIEWER 隐藏写操作按钮/入口。

i18n：messages/{zh-CN,en-US}.json 补 project/role/permission/menu 命名空间及越权提示，双 bundle 键集一致（CI 校验），静态 UI 文案走 next-intl。

测试：后端造 VIEWER/EDITOR/OWNER 三成员，断言越权写被拒、角色权限矩阵一致。前端 vitest + 浏览器验证三角色菜单差异、切项目重算。

不要碰：TenantContext/地基（读可以、改不行），ops(A)/metrics(B)/alert-quality-schema(C) 的数据行过滤逻辑。菜单/权限层是你的独占面。地基 API 不满足回报收尾方。

每改一处 compile/typecheck；长跑 setsid。完成输出：角色矩阵 + 改动清单 + 测试结果 + 接缝遗留。
```

---

## 收尾方（本机主 Claude）职责

1. **先行**：落地地基契约（FR-001~003）+ `project.forbidden`/`project.required` 错误码接入，冻结后广播四路。
2. **全盘清单**：维护 SC-001 的"受隔离接口全盘清单"，四路回报后逐项打勾/标豁免。
3. **集成兜底**：四路 worktree 合并（C 路 schema 先合），重跑共享面测试（尤其 schema + 地基接缝），确认无跨项目泄漏（SC-002）、日期收敛（SC-003）、角色矩阵一致（SC-004）。
4. **补漏**：四路遗留接缝、遗漏的含隔离列却未收口的读路径、i18n 键集一致性 CI、schema 三处版本恒等。
