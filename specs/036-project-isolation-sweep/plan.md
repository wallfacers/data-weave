# Implementation Plan: 项目级数据隔离全盘收口

**Branch**: `036-project-isolation-sweep` | **Date**: 2026-07-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/036-project-isolation-sweep/spec.md`

## Summary

在 `032-project-nav` 建立的项目切换基础设施之上，把"项目切换"从 UI 空壳做成**全盘真隔离**：请求级贯通 `projectId` 上下文 → 所有受隔离读写路径按 (tenantId, projectId[, bizDate]) 过滤 → 补齐告警/质量表的项目维度 → 落地项目角色的菜单/授权隔离。技术路径：**先冻结共享地基契约（三元组上下文 + 作用域校验 + 错误码）**，再由 4 路垂直域并行收口（各自 git worktree），最后收尾方集成兜底并交付「受隔离接口全盘清单」。

## Technical Context

**Language/Version**: Java 25 (Spring Boot 4.0 / Spring Framework 7, Jackson 3) + TypeScript (Next.js 16 / React 19)

**Primary Dependencies**: WebFlux, Spring Data JDBC + JdbcTemplate, next-intl, zustand, shadcn/ui (base style / hugeicons)

**Storage**: PostgreSQL (default) · H2 (`profiles=h2`) · neo4j (lineage) · Redis (EventBus)。权威 DDL：`backend/dataweave-api/src/main/resources/schema.sql`

**Testing**: 后端 JUnit 5 + AssertJ + WebTestClient（JWT via JwtTestSupport）；前端 vitest + Playwright 浏览器验证

**Target Platform**: Linux server (backend :8000) + Web (frontend :4000)

**Project Type**: Web application（backend 多模块 DDD + frontend App Router）

**Constraints**: 严守调度死锁防御四不变量（SKIP LOCKED / CAS / 锁顺序 / 事务内只持久化）；schema 改表必升 `schema_version`（三处恒等，SemVer）；i18n 双 bundle 键集一致；不弱化 PolicyEngine 闸门；指标定义不可变（改则加 version）。

**Scale/Scope**: 4 路并行 + 1 地基。受隔离读接口目标 100% 收口：ops/metrics/lineage/alert/quality/freshness ≈ 6 大域，涉及 ~10 controller、~8 service、6 张表补列、前端 ~8 视图。

## Constitution Check

项目无 `.specify/memory/constitution.md`；以 CLAUDE.md「Key Conventions / Working Rules」为治理约束，逐条对齐：

- ✅ **依赖方向** domain ← application ← infrastructure ← interfaces：隔离过滤下沉到 repo/service，不逆向。
- ✅ **写操作过闸门**：角色授权（US4）复用 `GatedActionService`/`PolicyEngine`，零 bypass。
- ✅ **调度不变量**：A 路禁改 claim/CAS/锁顺序；C 路对 `cron_fire` 加列以"不破坏不变量"为红线。
- ✅ **指标不可变**：B 路只加隔离过滤，不 UPDATE 旧 metric。
- ✅ **i18n 三规则**：静态 UI 走 next-intl（by UI locale）；错误码 `project.forbidden/required/role.forbidden` 走 `BizException` + `GlobalExceptionHandler`。
- ✅ **schema 单一权威**：C 路只改 `schema.sql`，不建增量脚本，升版三处恒等。
- ✅ **并行隔离**：每路独立 worktree，SDD 指针不互相覆盖。

无违反项 → Complexity Tracking 免填。

## Project Structure

### Documentation (this feature)

```text
specs/036-project-isolation-sweep/
├── spec.md              # 需求真相源（US1~US4 + FR + SC）
├── plan.md              # 本文件
├── research.md          # Phase 0：现状基线 + 关键裁决
├── data-model.md        # Phase 1：ProjectScope 上下文 + schema 补列
├── contracts/           # Phase 1：地基契约 + 各域隔离契约
├── launch-prompts.md    # 四路启动提示词（交付外部 agent）
├── checklists/requirements.md
└── tasks.md             # Phase 2（/speckit-tasks 产出）
```

### Source Code (repository root)

```text
backend/
├── dataweave-api/        # 地基: TenantContext / JwtAuthFilter / McpAuthFilter / GlobalExceptionHandler
│   └── .../interfaces/   # MetricsController(B) / OpsController(A) / AlertController(C) / 权限接口(D)
│   └── resources/schema.sql   # C 路补列 + 升版
├── dataweave-master/     # OpsService(A) / MetricService·LineageService(B) / QualityService(C) / RBAC 解析(D)
├── dataweave-alert/      # AlertRule/Event/Channel/Route repo+service(C)
└── (各模块 src/test/)     # WebTestClient 双项目隔离测试

frontend/
├── lib/project-context.ts        # 地基（已存在，读约定；不改结构）
├── lib/workspace/{registry,nav-groups}.tsx  # D 路菜单权限过滤
├── components/workspace/left-nav.tsx         # D 路导航权限
└── components/workspace/views/
    ├── ops/*-panel.tsx           # A 路：projectId + bizDate
    ├── metrics-view.tsx / freshness-view.tsx  # B 路
    └── alerts-view.tsx           # C 路
messages/{zh-CN,en-US}.json       # D 路 i18n 命名空间
```

**Structure Decision**: Web application 双工程既有布局，不新增模块。隔离逻辑下沉到各模块 repo/service，controller 接入地基作用域校验；前端复用 `useProjectContext`。冲突面已在 spec 拆分表按域切成 A/B/C/D 互斥集。

## Phasing

- **Phase 0（research.md）**：现状基线（已由并行扫描产出）+ 关键裁决（地基先行、schema 单路独占、cron_fire 豁免判定）。
- **Phase 1（data-model.md + contracts/）**：ProjectScope 上下文模型、6 表补列 DDL 方案与回填、地基契约 + 4 域隔离契约。
- **Phase 2（tasks.md）**：Setup → Foundational（地基，阻塞）→ US1(A)/US2(B)/US3(C)/US4(D) 并行 → Polish（集成兜底 + 全盘清单）。

## Complexity Tracking

无 Constitution 违反项，免填。
