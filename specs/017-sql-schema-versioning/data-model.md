# Phase 1 Data Model: SQL 脚本重梳理与严格 Schema 版本设计

**Feature**: 017-sql-schema-versioning | **Date**: 2026-06-29

本特性**不改任何现有业务表结构**（结构等价收口）。数据模型层面唯一新增对象是基础设施性质的版本记录表 `schema_version`。本文同时给出收口/删除的「内容清单」作为实现对账依据。

---

## 1. 新增实体：`schema_version`（单行版本记录表）

记录当前权威 schema 的版本——其值即项目发布版本（R2/R3）。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `version` | `VARCHAR(32)` | `NOT NULL`，合法 SemVer `MAJOR.MINOR.PATCH` | 当前 schema 版本 = 项目发布版本；基线 `0.0.1` |
| `applied_at` | `TIMESTAMP` | `NOT NULL` | 该版本结构被建立（重建）的时刻 |
| `description` | `VARCHAR(256)` | 可空 | 可选备注（如对应里程碑/变更摘要） |

**位置**：定义（`CREATE TABLE`）与其唯一一行（`INSERT`）**均写在 `schema.sql` 内**，与版本头注释物理同源、随同一文件加载——防漂移。

**生命周期 / 不变量**：
- **恰好一行**（覆盖式发布：drop-and-recreate，每次启动按 schema.sql 重写当前版本）。
- `version` MUST 为合法 SemVer；MUST 等于 schema.sql 头部声明的版本；MUST 等于当前项目发布版本（三者恒等，R2）。
- 不可变约定：版本只随结构变更而**递增**（破坏性→MAJOR / 加表加列→MINOR / 修正→PATCH），任何 schema 改动不得在不升版本下发布（FR-005/FR-007）。
- 无外键、无租户列（全局元数据表，类比 `tenants/permissions` 等全局表无 `tenant_id/project_id`）。
- 被动记录表：无写网关路径、无 API 暴露（不违反 FR-014「零新增业务能力」）。

**关系**：与任何业务表均无关联（纯元数据）。

**参考 DDL**（PostgreSQL / H2 兼容，最终以 schema.sql 内实现为准）：

```sql
-- ===== schema 版本（= 项目发布版本，单行）=====
DROP TABLE IF EXISTS schema_version;
CREATE TABLE schema_version (
    version      VARCHAR(32)  NOT NULL,
    applied_at   TIMESTAMP    NOT NULL,
    description  VARCHAR(256),
    CONSTRAINT pk_schema_version PRIMARY KEY (version)
);
INSERT INTO schema_version (version, applied_at, description)
VALUES ('0.0.1', CURRENT_TIMESTAMP, 'Baseline after SQL consolidation (017)');
```

---

## 2. 收口清单（合并进权威 schema → 删除源文件）

下列 8 个 `db/migration/*.sql` 的改动效果**已全部存在于 `schema.sql`**（research R1 逐项核对），故动作是**删除源文件**，无需再次搬运 DDL。删除后须确认启动建库不缺任何表/列。

| 源文件（删除） | 已并入 schema.sql 的对应结构 |
|---|---|
| `catalog-pg.sql` | `catalog_node` / `tag` / `entity_tag` + `task_def/workflow_def.catalog_node_id` |
| `datasource-driver-isolation-pg.sql` | `driver_jars` + `datasources.driver_jar_id` |
| `datasource-connection-status-pg.sql` | `datasources.connection_status` |
| `V__add-master-nodes-pg.sql` | `master_nodes` |
| `V__add-next-trigger-pg.sql` | `workflow_def.next_trigger_time` / `schedule_interval_ms` |
| `task-instance-locale-pg.sql` | `task_instance.locale` |
| `workflow-canvas-pg.sql` | `workflow_node.node_type` / `task_id` 可空 |
| `distributed-scheduler-m1-uuidv7-pg.sql` | `task_instance`/`workflow_instance` UUID（已并入）；其对 `task_diagnosis` 的 ALTER → 该表已移除，作废 |

---

## 3. 删除/修正清单（过时内容）

| 对象 | 处置 | 验证 |
|---|---|---|
| `db/migration/`（整目录 8 文件） | 删除 | 目录不存在；启动建库无缺失 |
| `demo-data.sql` 中 `INSERT INTO task_diagnosis` / `finding` | 删除（两表已移除，demo profile 当前已坏） | 全仓无活引用；demo profile 可起或随文件整体删除 |
| `demo-data.sql`（若清后无实义） | 整文件删除 + 撤 `application-demo.yml` 的 `data-locations` 引用 | demo profile 启动不报缺表 |
| `schema.sql` 头部真相源注释（指向废弃 `openspec/.../design-data-model.md`） | 改指现行文档或声明自述真相源 | 注释不再引用不存在路径 |
| `master_nodes` 等尾部 `IF NOT EXISTS` 追加块 | （可选）归一进主 DROP+CREATE 结构 | 文件风格一致；建表行为不变 |
| `orders` 表 | **保留**（仅测试解析字符串引用，待评审，默认不动） | —— |

---

## 4. 未改动声明

- 现有全部业务表（`tenants`/`projects`/`task_def`/`workflow_*`/`*_instance`/`metric*`/`policy_rules`/`agent_action` 等）结构、索引、约束**保持不变**。
- 种子数据 `data.sql` 仅在「删表/删列致其 INSERT 失效」时同步清理（本次核对：data.sql 未引用被删表，预期无需改；实现期再核一遍）。
- 发布模型不变：`spring.sql.init mode=always` + `DROP IF EXISTS` 覆盖式重建。
