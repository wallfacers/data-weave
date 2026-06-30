# Implementation Plan: 血缘查询、API 与企业级前端视图

**Branch**: `020-lineage-graph-api` | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/020-lineage-graph-api/spec.md`；共享设计契约 [docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md](../../docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md)（尤其 §4 图模型 / §7 查询+API+前端 / §10 拆分）。

## Summary

把血缘**读侧**整体从 Java 内存 BFS 迁移到 neo4j Cypher 变长路径。核心交付三块：

1. **查询内核（dataweave-master）**：用 Cypher `[:FLOWS_TO|DERIVES_FROM*]` 变长路径取代现 `LineageGraphService` 的内存 BFS，复用 018 提供的只读 neo4j 会话；新增 `LineageQueryService` 提供库/表/列结构下钻、任意深度上下游、影响面、指标血缘、运行态聚合，全部按 `(tenantId, projectId)` 隔离 + 深度/节点上界 + 分页/截断（截断必 log）。
2. **多粒度 API（dataweave-api）**：重设计 `LineageGraphController` 的 `/api/lineage/*`：`datasources`、`tables/{id}/columns`、`tables/{id}/{upstream|downstream}?depth=&granularity=`、`columns/{id}/{upstream|downstream}`、`impact/{nodeId}`、`metrics/{id}/lineage`、`sync-summary`。返回结构携带 `type`（datasource/table/column/metric）与 `granularity`。neo4j 不可达 → `BizException("lineage.store_unavailable")` 走 `GlobalExceptionHandler`。
3. **企业级前端血缘视图（frontend）**：把 `lineage` 从占位视图升级为真实 view（注册进 workspace registry），库/表/列三级展开折叠 + 表级/列级血缘流 + 影响面高亮 + 指标叠加 + neo4j 不可达友好降级；遵循 DESIGN.md（无 header/footer 分割线、语义 token、shadcn base、hugeicons）、next-intl 双语 key 等集。

**只读边界**：本特性不写图、不做 SQL 解析。图数据由 018 写入、列级数据由 019 经 018 写入。neo4j driver/`@Bean`/只读会话由 018 提供，本特性复用。

## Technical Context

**Language/Version**: Java 25（backend）/ TypeScript + React 19（frontend）

**Primary Dependencies**:
- Backend：Spring Boot 4.0 / Spring Framework 7（WebFlux）、neo4j Java Driver（**由 018 提供 `@Bean` + 只读 session/`ReactiveSession`**，本特性复用其只读会话，不新建 driver 配置）、Micrometer。
- Frontend：Next.js 16（App Router, Turbopack）、React 19、shadcn/ui（base style）、`@hugeicons/core-free-icons`、next-intl、zustand（workspace store）。

**Storage**: neo4j（图，只读消费 018 写入的 `:Datasource/:Table/:Column/:Metric/:Task/:TaskRun` 节点与 `HAS_TABLE/HAS_COLUMN/READS/WRITES/FLOWS_TO/DERIVES_FROM/COMPUTED_FROM/SYNCED` 关系）。本特性 **不写** neo4j，**不碰** PostgreSQL 血缘表（已由 018 在 schema.sql 中删除）。

**Testing**:
- Backend：JUnit 5 + AssertJ；**Testcontainers neo4j**（真容器）做 Cypher 查询正确性集成测试（多跳上下游/影响面/列级流/分层下钻/截断边界/不可达降级）；沿用后端测试隔离不变量（唯一库、`@DirtiesContext`、seed 不漂移）。
- Frontend：vitest 按需（视图模型转换、折叠状态归约）；浏览器验证（admin/admin 取 JWT 注入 localStorage，深链 `/?open=lineage`，库/表/列下钻 + 列级流 + 影响面高亮 + 不可达降级渲染）。

**Target Platform**: Linux server（backend `:8000`）；浏览器（frontend `:4000`）。

**Project Type**: Web application（Next.js frontend + Spring Boot WebFlux backend，DDD 四模块）。

**Performance Goals**: 变长路径查询有界——深度上界（默认/最大可配，如 depth≤20）+ 节点数上界（如 ≤2000）+ 分页（offset/limit），单次查询不一次性拉爆全图；前端分层懒加载（展开某节点才取其子层/邻域）。

**Constraints**:
- 所有查询 MUST 按 `(tenantId, projectId)` 隔离（沿用 MCP 租户隔离；当前 controller 固定 1/1，保持与现状一致的注入点，预留 `TenantContext`）。
- 变长路径 MUST 防环（Cypher `*..N` 上界 + neo4j 路径默认不重复关系；查询模式避免无限环）。
- neo4j 不可达 MUST 返回稳定错误码 `lineage.store_unavailable`，平台其余功能 100% 不受影响。
- 截断 MUST `log`（WARN，含被截断节点数/深度/查询锚点），不静默丢。

**Scale/Scope**: 后端约 1 个查询服务（`LineageQueryService`，含多个 Cypher 方法）+ 1 个 controller 重设计（8 类端点）+ 返回视图模型若干 record；前端约 1 个新 view（`lineage-view.tsx`）+ 1 个 lineage api 客户端（`frontend/lib/lineage-api.ts`）+ registry/views 各一行 + 双语 key 一组。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 适用性 | 结论 |
|------|--------|------|
| **I. Files-First** | 不直接相关（读侧查询，不引入新文件定义格式）。 | ✅ 不违反；不改任务/工作流文件契约。 |
| **II. Server is Source of Truth** | 查询按租户/项目隔离，越权拒绝；只读不破坏 push 覆盖+快照治理。 | ✅ 查询路径强制 `(tenantId, projectId)` scope，沿用隔离。 |
| **III. Two-Legged Debugging** | 不相关（不碰 CLI 本地 runtime / 执行器）。 | ✅ N/A。 |
| **IV. AI Lives in Local Agent** | 不嵌入服务端 AI；血缘查询是纯确定性 Cypher，不嵌推理。 | ✅ 无 AI 大脑残留。 |
| **V. Reuse the Kernel** | 复用 018 的 neo4j driver/会话与 §4 图模型契约，不重写存储；不重写调度/执行/网关。 | ✅ 只读消费契约，零重写。 |
| **DDD 依赖方向** | HTTP（`LineageGraphController`）在 dataweave-api/interfaces；查询逻辑（`LineageQueryService`）在 dataweave-master/application；只依赖 018 的 infrastructure neo4j 会话。outer→inner 单向。 | ✅ api(interfaces) → master(application) → 018 infrastructure；不反向。 |
| **i18n 三规则** | ① 静态 UI 文案（视图标题/空态/展开折叠/影响面标签）→ frontend next-intl ICU `{name}`，按 UI locale；② 后端生成文案：本特性无 MCP 描述/审批理由，N/A；③ 错误（`lineage.store_unavailable` 等）→ `BizException(code, args)` + `GlobalExceptionHandler`，按 UI locale。数据术语 lineage/DAG/upstream/downstream 保留英文。zh-CN/en-US key 等集（CI 校验）。 | ✅ 三规则归位。 |
| **前端栈 gate** | base-style 组件、自定义 trigger 用 `render`（非 asChild）、`Button` 作 `<a>` 需 `nativeButton={false}`；图标 hugeicons；语义 token；`pnpm typecheck` 零错。 | ✅ 计划遵守，tasks 含 typecheck 门。 |
| **Design Contract gate** | 改 frontend 视觉前先读 frontend/DESIGN.md 并声明约束：**无 header/footer 分割线**（区域靠留白）、语义 token（`bg-primary`/`text-muted-foreground`）、`gap-*`/`size-*`、不手写 `dark:`。血缘视图作为一个 view 注册进 registry，复用 workspace 容器外壳。本特性不改主题变量（不触 globals.css/DESIGN.md token）。 | ✅ Phase 0 起读 DESIGN.md。 |
| **新功能必须有测试** | 后端 Testcontainers neo4j 集成测试（查询正确性 + 截断 + 不可达降级）；前端 vitest 按需 + 浏览器验证。无测试=未完成。 | ✅ tasks 每个 user story 含测试任务。 |
| **跨特性隔离与闭环** | 本特性在独立 worktree（`dw-020-lineage-graph-api`），`.specify/feature.json` 指向 020。依赖 018（neo4j 会话 + 图模型契约）、消费 019 写入的列级数据。**对桩并行**：A 先落会话/图模型契约桩，本特性对桩开发；集成时先合 018，再跑共享 surface（neo4j 会话、`:Table` 图内 id）测试确认缝合。 | ✅ 已隔离；依赖与 ordering 记录在 research.md。 |

**初判**：无违反，无需 Complexity Tracking。重新检查（Phase 1 后）：设计未引入新越权面/新服务端 AI/新文件格式，**Constitution Check 仍 PASS**。

## Project Structure

### Documentation (this feature)

```text
specs/020-lineage-graph-api/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出（关键决策）
├── data-model.md        # Phase 1 输出（返回视图模型）
├── quickstart.md        # Phase 1 输出（验证步骤）
├── contracts/           # Phase 1 输出（/api/lineage/* 端点契约）
│   └── lineage-api.md
├── checklists/          # 既有
└── tasks.md             # Phase 2 输出（/speckit-tasks，非本命令产出）
```

### Source Code (repository root)

```text
backend/
├── dataweave-master/                  # 查询逻辑（application 层）
│   └── src/main/java/com/dataweave/master/application/
│       ├── LineageQueryService.java          # 新增：Cypher 变长路径查询（取代 BFS）
│       └── LineageGraphService.java          # 现有 JDBC BFS —— 读侧方法迁出/标记弃用（写侧归 018）
│   └── src/main/java/com/dataweave/master/lineage/    # 返回视图模型（record）
│       ├── GraphNodeView.java                # type/granularity/layer
│       ├── FlowEdgeView.java
│       ├── ImpactResult.java
│       ├── MetricLineage.java
│       └── SyncSummary.java
│   └── src/test/java/com/dataweave/master/application/
│       └── LineageQueryServiceNeo4jTest.java # Testcontainers neo4j 集成测试
└── dataweave-api/                     # HTTP（interfaces 层）
    └── src/main/java/com/dataweave/api/interfaces/
        └── LineageGraphController.java       # 重设计：多粒度 /api/lineage/*
    └── src/main/resources/
        └── messages*.properties              # 错误码 lineage.store_unavailable（zh/en）

frontend/
├── components/workspace/views/
│   └── lineage-view.tsx                # 新增：企业级血缘视图（库/表/列三级 + 列级流 + 影响面 + 指标叠加）
├── components/workspace/views/lineage/  # 视图子组件（按需拆分）
│   ├── lineage-tree.tsx               # 库→表→列 展开折叠
│   ├── lineage-flow.tsx               # 表级/列级血缘流画布
│   └── impact-panel.tsx              # 影响面高亮 + 指标叠加
├── lib/
│   ├── lineage-api.ts                 # 新增：/api/lineage/* 客户端
│   └── workspace/
│       ├── registry.tsx               # lineage 占位 → 真实 LineageView（一行）
│       └── views.ts                   # lineage 元数据（已存在，确认 title）
└── messages/
    ├── zh-CN.json                     # lineageView.* 一组 key
    └── en-US.json                     # 等集
```

**Structure Decision**: Web application（Option 2）。后端按 DDD：HTTP 落 dataweave-api/interfaces，查询逻辑落 dataweave-master/application + 返回模型落 dataweave-master/lineage（与现有 lineage 服务同模块，避免跨模块循环）；neo4j 只读会话依赖 018 在 dataweave-master/infrastructure 提供的 `@Bean`。前端血缘视图作为一个 view 注册进既有 workspace registry，复用多 tab 外壳与 zustand store。

## Complexity Tracking

> 无 Constitution 违反，本节留空。
