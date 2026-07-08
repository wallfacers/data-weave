# Implementation Plan: 全局系统设置——AI Agent 配置统收

**Branch**: `057-system-settings` | **Date**: 2026-07-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/057-system-settings/spec.md`

## Summary

把血缘「云 AI Agent」抽取通道的配置从**按项目隔离**（每项目一条，挂在血缘探索器工具栏弹窗）提升为**租户级全局单例**——全租户所有项目共用一份；管理入口迁到已有「系统设置」视图：**新增顶级「配置」tab**，其内部采用**左侧导航 + 右侧内容**布局（与「数据开发」视图同风格），AI Agent 作为左导航首个分区，且这套左右布局作为**可复用配置外壳能力**（未来全局配置只注册分区项）。

技术面 = 后端把 `lineage_agent_config` 从 `(tenant,project)` 作用域改为 `(tenant)` 单例 + 两个 enricher 调用点改读全局 + 端点去 projectId 改挂 `/api/settings/*`；前端给 `SettingsView` 加第 4 个「配置」tab（顺手把手写下划线 tab 条迁到合规范 `Tabs` 组件），新建 `ConfigShell`（左导航+右内容）与 `AiAgentConfigSection`（内联表单，复用既有 `AgentConfigPanel` 字段逻辑），并移除血缘工具栏旧入口。

## Technical Context

**Language/Version**: 后端 Java 25 / Spring Boot 4.0 / Spring Framework 7（Jackson 3）；前端 TypeScript / Next.js 16 (App Router, Turbopack) / React 19。

**Primary Dependencies**: 后端 WebFlux + Spring Data JDBC + JdbcTemplate；前端 shadcn/ui (base style) + hugeicons + next-intl + motion (framer) + `@xyflow/react`（既有，本特性不直接用）。复用既有 `DatasourceEncryptor`（凭据加密）、`AgentConfigRepository`、`LlmAgentClient`、`LineageAgentEnricher` 内核。

**Storage**: PostgreSQL（默认）/ H2（`profiles=h2`）。`lineage_agent_config` 表作用域改造（去 `project_id`、UNIQUE 改 `(tenant_id, deleted)`）；`lineage_agent_call` 审计表**保留 `project_id`**（调用按项目/任务溯源）。权威 DDL = `backend/dataweave-api/src/main/resources/schema.sql`，`schema_version` 由 **0.12.0 → 0.13.0**。无独立 migration 脚本（本项目约定：DDL 真相源 + 版本号，PG 重建/H2 自动）。

**Testing**: 后端 JUnit 5 + AssertJ；WebFlux 用 `@SpringBootTest` / WebTestClient（SB4 的 `@WebFluxTest` 在 `org.springframework.boot.webflux.test.autoconfigure`）。前端 vitest（纯函数：config-sections 注册表 / 权限过滤）+ 浏览器验证渲染页。

**Target Platform**: Linux server（后端）/ 现代浏览器（前端）。

**Project Type**: web-service + web-app（前后端双项目）。

**Performance Goals**: 配置读写 < 200ms（单行 upsert/select）；测试连接 = 一次真实云外呼（数秒级，异步反馈，不阻塞）。非高频路径，无吞吐压力。

**Constraints**: 凭据明文绝不入库/日志/回显（加密 + 脱敏，复用 053 机制）；全局配置缺失或禁用 → AI 通道整体旁路，不阻断 push（复用 053 既有降级）。

**Scale/Scope**: 单租户单例配置（1 行/租户）；审计行随调用增长（已有表，不扩大）。前端 1 个新 tab + 1 套可复用外壳 + 1 个分区组件。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 说明 |
|---|---|---|
| I. Files-First（文件优先） | N/A | 本特性是运行时**系统配置**（DB 存储，与 用户/角色/项目 等系统设置同类），非任务/工作流定义文件。系统配置不入 Files-First 范畴，不构成违规。 |
| II. Server is Source of Truth | PASS | 配置服务端存储与治理；租户隔离不变（全局单例仍按 `tenant_id` 隔离）。 |
| III. 分阶段运行时 | N/A | 不涉及运行时分期。 |
| IV. AI Lives in the Local Agent | PASS（对 057） | 血缘「云 AI Agent」通道（053）调用**外部**云大模型做血缘抽取，是既有已合的数据处理通道（非治理任务的服务端 AI 大脑）。**057 不引入任何新的服务端 AI**——仅把其配置提升为全局 + 加系统设置 UI。057 本身不违反 IV。 |

**Gate 结论**：无违规，无需 Complexity Tracking 豁免。Phase 1 设计后复核保持成立（设计未引入新服务端 AI）。

## Project Structure

### Documentation (this feature)

```text
specs/057-system-settings/
├── plan.md              # 本文件
├── research.md          # Phase 0：设计决策与研究
├── data-model.md        # Phase 1：实体/表改造
├── quickstart.md        # Phase 1：构建/运行/验证
├── contracts/
│   ├── system-settings-api.md        # 全局 AI Agent 配置 REST 契约
│   └── config-section-registry.md    # 前端「配置」分区注册表契约（可复用外壳）
└── tasks.md             # /speckit-tasks 生成（本命令不创建）
```

### Source Code (repository root)

```text
backend/
├── dataweave-api/src/main/resources/schema.sql            # 改：lineage_agent_config 去 project_id + UNIQUE；schema_version → 0.13.0
├── dataweave-api/.../interfaces/LineageAgentConfigController.java   # 改：@RequestMapping("/api/settings/agent-config")、去 projectId、admin 门
├── dataweave-master/.../domain/lineage/LineageAgentConfig.java      # 改：record 去 projectId
├── dataweave-master/.../infrastructure/lineage/AgentConfigRepository.java  # 改：findActiveByTenant + upsert 去 project_id；insertCall/findCalls 保留 project_id
├── dataweave-master/.../application/lineage/agent/AgentLineageConfigService.java  # 改：get/getActive/isEnabledFor/upsert 去 projectId（tenant 单例）
├── dataweave-master/.../application/lineage/agent/AgentLineageExtractor.java      # 改：调用点 :72 改读全局
├── dataweave-master/.../application/lineage/agent/LineageAgentEnricher.java       # 改：调用点 :162 改读全局
└── dataweave-master/.../test/.../lineage/agent/*                          # 改/加：单例语义测试

frontend/
├── components/workspace/views/settings-view.tsx                       # 改：tab 条迁 Tabs 组件 + 加「配置」tab
├── components/workspace/views/settings/config-shell.tsx               # 新：左导航+右内容可复用外壳（数据开发风格）
├── components/workspace/views/settings/ai-agent-config-section.tsx    # 新：AI Agent 内联表单（复用 AgentConfigPanel 字段逻辑）
├── components/workspace/views/settings/config-nav.tsx                 # 新：左侧分区导航列表（可拖拽调宽卡片）
├── lib/workspace/settings/config-sections.ts                          # 新：CONFIG_SECTIONS 注册表（纯数据 + 权限过滤，仿 nav-groups.ts）
├── lib/lineage-api.ts                                                  # 改：agent-config 函数去 projectId、改路径 /api/settings/agent-config
├── components/workspace/views/lineage/agent-config-panel.tsx          # 删/重定向：旧弹窗入口移除（FR-015）
├── components/workspace/views/lineage/lineage-toolbar.tsx              # 改：移除 AgentConfigPanel 触发按钮
└── messages/{zh-CN,en-US}.json                                         # 改：settingsView 命名空间加 配置 tab / 外壳 / AI Agent 分区文案
```

**Structure Decision**: 复用既有前后端目录与四模块 DDD 分层；后端改动集中在 master 模块（domain/application/infrastructure）+ api 模块（controller + schema）；前端改动集中在 `components/workspace/views/settings/` 新目录与 `lib/workspace/settings/` 注册表，遵循 037 reuse-first（`Tabs`/`DwScroll`/`DropdownSelect`/`Switch`/`Input`/`Card` 等公共组件）。

---

## 前端架构与布局（HTML 代码分割 / 大致布局）

> 用户明确要求本阶段给出前端组件拆分与布局。遵循 DESIGN.md：shadcn neutral、语义 token、`--card-spacing`、**无分割线靠留白**、reuse-first、Tabs 双变体（非 closable→下划线式 `Tabs` 组件，**禁止手写下划线 tab**）。

### 组件树 / 代码分割

```
SettingsView (settings-view.tsx — 改)
 │  顶部 tab 条：手写下划线 → 迁移到 <Tabs> 组件（下划线式，DESIGN 合规）
 ├── <Tabs value onValueChange>
 │   ├── TabsTrigger "用户"   → <UsersTab/>       (既有，内容不动)
 │   ├── TabsTrigger "角色"   → <RolesTab/>       (既有，内容不动)
 │   ├── TabsTrigger "项目"   → <ProjectsTab/>    (既有，内容不动)
 │   └── TabsTrigger "配置"   → <ConfigShell/>    (新)
 │
 ConfigShell (settings/config-shell.tsx — 新 · 可复用配置外壳)
  ├── 左：ConfigNav (settings/config-nav.tsx — 新)
  │     · 圆角卡片 motion.div（rounded-[var(--radius-lg)] border bg-card shadow-lg）
  │     · 可拖拽调宽 + localStorage 持久化（复用 DataDevIdeShell 的 catalog 宽度模式）
  │     · 内部 DwScroll 渲染 CONFIG_SECTIONS 列表（选中态 bg-muted/font-medium，hugeicons + 文案）
  │     · 选中分区项高亮、未选中 hover/text-muted-foreground；无分割线
  └── 右：ConfigContent（圆角卡片）
        · DwScroll 包裹；按 activeSectionId 渲染对应分区组件
        · activeSectionId==="ai-agent" → <AiAgentConfigSection/>

 CONFIG_SECTIONS (lib/workspace/settings/config-sections.ts — 新 · 纯数据注册表)
  · 仿 nav-groups.ts：{ id, titleKey, icon, requirePermission?, component }
  · 首项 = { id:"ai-agent", titleKey:"settingsView.configSectionAiAgent", icon: AiBrainIcon }
  · filterVisibleSections(permissions)：按权限过滤（对齐 viewRequiredPermission 模式）
  · 新增全局配置 = 在此数组加一项 + 写其内容组件，外壳零改动（SC-006）

 AiAgentConfigSection (settings/ai-agent-config-section.tsx — 新)
  · 内联表单（非 Dialog），字段与既有 AgentConfigPanel 等价、复用其 state/校验/test/save 逻辑
  · DropdownSelect(协议 ANTHROPIC|OPENAI) · Input(baseUrl) · Input(model)
  · Input type=password(apiKey，留空=不改 + 脱敏回显 sk-…xxxx)
  · Input type=number(timeoutMs / rateLimitPerMin / maxColumns) —— 大范围数值用 Input（DESIGN：大范围连续数值用 Input，非 Stepper）
  · Switch(enabled) · 测试结果反馈（语义色 success/destructive + hugeicons）
  · 操作：测试连接 / 保存（Button）
```

### 布局骨架（ConfigShell，对齐 DataDevIdeShell）

```html
<!-- 「配置」tab 内容：左导航 + 右内容，数据开发风格 -->
<div className="flex h-full gap-3 p-3">
  <!-- 左：配置分区导航（可拖拽调宽卡片） -->
  <div className="relative flex shrink-0 flex-col pr-1.5">
    <motion.div
      className="flex h-full flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-card shadow-lg"
      style={{ width: navWidthProp }}
    >
      <ConfigNav
        sections={visibleSections}
        activeId={activeSectionId}
        onSelect={setActiveSectionId}
      />
    </motion.div>
    <!-- 右缘拖拽分隔（col-resize，localStorage 持久化；同 DataDevIdeShell catalog 模式） -->
    <div role="separator" aria-orientation="vertical"
         onPointerDown={onNavResizeDown}
         className="group/resize absolute inset-y-3 right-0 z-20 flex w-2 cursor-col-resize ...">
      <div className="h-12 w-0.5 rounded-full bg-border/0 transition-colors group-hover/resize:bg-border" />
    </div>
  </div>

  <!-- 右：选中分区内容（圆角卡片，DwScroll 包裹） -->
  <div className="flex min-w-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-card shadow-lg">
    <DwScroll className="flex-1" innerClassName="overflow-y-auto">
      {/* 表单内容引用 --card-spacing，禁止魔法间距字面值 */}
      <div className="p-(--card-spacing)"><AiAgentConfigSection/></div>
    </DwScroll>
  </div>
</div>
```

**合规自查**：① 顶部 tab 用 `Tabs` 组件（非手写）；② 左导航是 nav rail（非 Tabs 组件，不触发双风格规则）；③ `DwScroll` 接管滚动；④ `--card-spacing` 驱动内边距；⑤ 无 `border-b/border-t/Separator` 区域分割线（仅卡片自身 `border` 与拖拽 handle 的 hover 提示条）；⑥ 语义 token，无裸色值/`dark:`；⑦ 复用 motion 的拖拽调宽模式（DataDevIdeShell 既有）。

---

## 后端改造要点

1. **DDL（schema.sql，升 0.13.0）**：`lineage_agent_config` 去 `project_id` 列；UNIQUE `uk_lineage_agent_config_tp` 改为 `(tenant_id, deleted)`；`lineage_agent_call` 不变（保留 project_id 溯源）。`schema_version` 增 `0.13.0` 行 + 文件头注释。
2. **domain/record**：`LineageAgentConfig` 去 `projectId`。
3. **Repository**：新增 `findActiveByTenant(tenantId)`；`update/insert` 去 project_id 参数；`insertCall/findCalls` 保留 project_id（审计）。
4. **Service**：`get/getActive/isEnabledFor/upsert` 去 `projectId` 参数（tenant 单例语义）；upsert 仍 upsert（每租户一条）；校验/加密/脱敏逻辑不变。
5. **调用点改读全局**：`AgentLineageExtractor:72`、`LineageAgentEnricher:162` 由 `getActive(tenantId, projectId)` → `getActive(tenantId)`（仍用任务的 tenantId；projectId 只用于审计落 `lineage_agent_call`）。
6. **Controller**：`@RequestMapping` 由 `/api/lineage/agent-config` → `/api/settings/agent-config`；各方法去 `projectId` 参数与 `ProjectScope.require`，改用 `TenantContext.tenantId()`；admin 鉴权复用既有 `/api/users` 等管理端点同款门（前端已按 `project:manage` 过滤可见性，后端机制实现时确认——见 research.md R1）。
7. **迁移默认**：H2/dev 由 DDL 重建（无数据）；PG 若有既有按项目配置，默认择一最近更新者提升为全局、其余软删留痕，不静默丢失（细节 tasks 阶段）。

## Complexity Tracking

> 无 Constitution 违规，本表留空。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
