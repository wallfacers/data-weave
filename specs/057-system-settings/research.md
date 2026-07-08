# Phase 0 Research: 全局系统设置——AI Agent 配置统收

**Feature**: 057-system-settings | **Date**: 2026-07-08

spec 经 clarify 已较清晰，Phase 0 聚焦实现落地的研究项与设计取舍（Decision / Rationale / Alternatives）。

## R1 — `/api/settings/*` 的 admin 鉴权机制
- **Decision**: 新全局 agent-config 端点复用既有 `/api/users`、`/api/roles`、`/api/projects` 管理端点的同款 admin 鉴权。
- **Rationale**: 这些端点未见方法级 `@PreAuthorize`，admin 门由全局 JWT/role 过滤器层兜底；前端已通过 `VIEW_META.requirePermission="project:manage"` 过滤系统设置可见性。复用 = 零新鉴权模型，与 spec Clarifications「沿用 project:manage」一致。
- **Alternatives**: ① 引入 `@PreAuthorize` 注解式鉴权——增加复杂度且与既有管理端点不一致；② 新建 `system:manage` 权限——spec Q2 已默认否决。
- **实现待确认**: 抽 1 个测试验证非 admin 角色访问 `/api/settings/agent-config` 应被拒（行为与 `/api/users` 对齐）。

## R2 — schema 迁移方式（无 migration 脚本）
- **Decision**: 改权威 `schema.sql`（`lineage_agent_config` 去 `project_id`、UNIQUE 改 `(tenant_id, deleted)`），`schema_version` 0.12.0 → 0.13.0；H2/dev 由 DDL 重建；PG 若有既有按项目数据，择一最近更新者提升为全局、其余软删留痕。
- **Rationale**: 项目约定「权威 DDL + 版本号，无独立 migration 脚本」（CLAUDE.md）。053 刚合（2026-07-07），生产按项目配置数据预期极少。
- **Alternatives**: ① 写 Flyway/Liquibase 迁移——违反本项目约定；② 保留 `project_id` 仅改默认读取为全局——语义模糊、留死列。

## R3 — 顶部 tab 条：手写 → `Tabs` 组件
- **Decision**: 加「配置」tab 时，顺手把 `settings-view.tsx` 现有手写下划线 tab 条迁到公共 `Tabs` 组件（下划线式）。
- **Rationale**: DESIGN.md 明确「禁止手写 `role=tab` + `after:bottom-0` 内联下划线」，非 closable 子 tab 必须用 `Tabs`。既有三个 tab **内容/行为不变**（仅换合规容器），与用户「三个 tab 不动」（指内容/位置/行为）不冲突。
- **Alternatives**: 保留手写、仅加第 4 个 button——延续违规、DESIGN lint 不过。

## R4 — 左导航+右内容外壳复用 `DataDevIdeShell` 模式
- **Decision**: `ConfigShell` 复用 `workflow-canvas-view.tsx` 的 `DataDevIdeShell` 模式——左侧可拖拽调宽卡片（`motion` value 驱动宽度 + localStorage 持久化）+ 右侧圆角卡片内容区（`rounded-[var(--radius-lg)] border bg-card shadow-lg`、`flex h-full gap-3 p-3`）。
- **Rationale**: 用户明确「和数据开发这种风格」；reuse-first（DESIGN.md）；该模式已验证可拖拽 + 持久化 + 亮暗自适应。
- **Alternatives**: ① shadcn Resizable Panel——新依赖、观感不一致；② 固定宽度不可调——交互退步。

## R5 — 数值字段控件选择（Input vs Stepper）
- **Decision**: `timeoutMs(30000)` / `rateLimitPerMin(60)` / `maxColumns(2000)` 用 `Input type=number`。
- **Rationale**: DESIGN.md `Stepper` 条目明确「大范围连续数值仍用 Input」。三字段范围大、步进无意义；既有 `AgentConfigPanel` 已用 `Input type=number`，保持一致。
- **Alternatives**: `Stepper`——大数值下 −/+ 步进体验差，且 DESIGN 明确不适用。

## R6 — 血缘工具栏旧入口处置
- **Decision**: 移除 `lineage-toolbar.tsx` 中的 `AgentConfigPanel` 触发按钮，删除 `agent-config-panel.tsx`（字段逻辑迁入新 `AiAgentConfigSection`）。单一真相源 = 系统设置 → 配置 → AI Agent。
- **Rationale**: FR-015；避免双入口与「一处全局、一处按项目」语义混淆。
- **Alternatives**: 保留为只读快捷跳转——多余入口、维护两处。

## R7 — 端点路径
- **Decision**: `/api/lineage/agent-config` → `/api/settings/agent-config`（去 `projectId`，tenant 全局）。
- **Rationale**: 配置管理入口已迁入系统设置；`/api/settings/*` 作为未来全局配置命名空间起点。Controller 保留 `LineageAgentConfigController`（仍拥有该领域），仅改 `@RequestMapping` 与去 `projectId`。
- **Alternatives**: ① 保留旧路径仅去 projectId——路径与新管理位置不符；② 新建 `SystemSettingsController` 承载——当前仅一个配置，过早抽象。

## 结论
所有设计项已决，无 NEEDS CLARIFICATION 残留，进入 Phase 1。
