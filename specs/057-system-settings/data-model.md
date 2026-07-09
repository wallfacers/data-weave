# Data Model: 全局系统设置——AI Agent 配置统收

**Feature**: 057-system-settings | **Date**: 2026-07-08

## 1. 全局 AI Agent 配置（`lineage_agent_config`）—— 作用域：项目 → 租户单例

| 字段 | 类型 | 改动 | 说明 |
|---|---|---|---|
| id | BIGINT PK | 不变 | |
| tenant_id | BIGINT NOT NULL | 不变 | 隔离键（全局单例 = 每租户一行）|
| ~~project_id~~ | ~~BIGINT NOT NULL~~ | **删除** | 配置不再属于项目 |
| protocol | VARCHAR(16) | 不变 | `ANTHROPIC` \| `OPENAI` |
| base_url | VARCHAR(512) | 不变 | 兼容端点根 URL（http/https）|
| model | VARCHAR(128) | 不变 | 模型名 |
| api_key_enc | VARCHAR(1024) | 不变 | 加密密文；NULL=免鉴权网关（明文绝不入库/日志）|
| enabled | SMALLINT | 不变 | 默认 0=关闭 |
| timeout_ms | INTEGER | 不变 | 默认 30000 |
| rate_limit_per_min | INTEGER | 不变 | 默认 60 |
| max_columns | INTEGER | 不变 | 默认 2000 |
| created_by / updated_by / created_at / updated_at / deleted / version | | 不变 | 软删 + 乐观锁 |

**UNIQUE 改动**：`uk_lineage_agent_config_tp (tenant_id, project_id, deleted)` → **`(tenant_id, deleted)`**（每租户一条生效配置）。
**CHECK 保留**：`ck_lineage_agent_protocol` / `ck_lineage_agent_enabled` / `ck_lineage_agent_deleted`。
**迁移**：H2/dev DDL 重建；PG 择一最近更新者提升为全局、其余软删（不静默丢失）。

### domain record 改造
`LineageAgentConfig(...)` 去 `projectId` 字段（位置与构造同步调整）。`AgentConfigRepository.map(rs)` 同步去 `project_id` 读取。

## 2. AI Agent 调用审计（`lineage_agent_call`）—— 不变

**保留 `project_id`**（审计按项目/任务溯源，FR-011）。`config_id → lineage_agent_config.id` 仍指向（如今的全局）配置行。字段、CHECK（status/proto）、索引 `idx_agent_call_task` 均不变。

> 语义变化：审计行的 `config_id` 现指向租户全局配置；`project_id` 记录「是哪个项目的任务触发了这次外呼」，与配置作用域解耦。

## 3. 配置分区（Config Section）—— 新（前端注册表实体，非 DB 表）

| 属性 | 类型 | 说明 |
|---|---|---|
| id | string | 稳定标识（首项 `"ai-agent"`）|
| titleKey | string | i18n key（`settingsView.*` 命名空间）|
| icon | HugeIcon | 分区图标 |
| requirePermission? | string | 可选权限码（缺省继承 settings 的 `project:manage`）|
| component | React.FC | 右侧内容组件（内联渲染，自带数据获取/保存）|

注册表 = `frontend/lib/workspace/settings/config-sections.ts`（纯数据，仿 `nav-groups.ts`）。新增全局配置 = 加一项，外壳零改动（SC-006）。

## 4. `schema_version`

**0.12.0 → 0.13.0**：`schema.sql` 文件头注释 + `INSERT INTO schema_version` 行 + DB 单行表三处同步（CLAUDE.md 权威 DDL 约定）。

## 状态 / 生命周期

- **全局配置**：单例 upsert（无则 insert，有则 update）；`enabled` 开关；缺失或 `enabled=0` → AI 通道旁路（复用 053 既有降级，不阻断 push）。
- **凭据**：`api_key_enc` 经 `DatasourceEncryptor` 加密存储；读取脱敏（`sk-…xxxx`）；编辑留空 = 不改（PATCH null vs 缺失语义，复用 053）。
- **审计行**：append-only，按 `created_at` 倒序，可按 `task_def_id` 过滤，`limit` 上限 200。
