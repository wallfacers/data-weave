-- 057 全局 AI Agent 配置统收 —— PostgreSQL 迁移参考脚本
-- ============================================================================
-- 用途：生产 PG 从 0.12.0 升级到 0.13.0 时，把 lineage_agent_config 从「按项目」
--       改造为「租户级全局单例」。H2/dev 由权威 schema.sql 重建，**无需**运行此脚本。
-- 项目约定：schema.sql 是结构真相源，无 Flyway/Liquibase；PG 升级由管理员手动执行。
-- 顺序依赖：必须在本脚本执行前，lineage_agent_config 仍为旧 schema（含 project_id）。
-- ============================================================================

-- 步骤 1：每租户仅保留最近更新的一条配置，其余软删。
--   原因：去 project_id 后 UNIQUE(tenant_id, deleted) 要求每租户仅一条 deleted=0；
--         若历史存在多条按项目配置，须先收拢，避免 ALTER 时违反新唯一约束。
--   策略：取每租户 updated_at 最新的一条保留，其余置 deleted=1（不静默丢失，可追溯）。
UPDATE lineage_agent_config c SET deleted = 1, updated_at = CURRENT_TIMESTAMP
WHERE c.deleted = 0
  AND c.id NOT IN (
    SELECT id FROM (
      SELECT id, ROW_NUMBER() OVER (PARTITION BY tenant_id ORDER BY updated_at DESC) AS rn
      FROM lineage_agent_config WHERE deleted = 0
    ) s WHERE s.rn = 1
  );

-- 步骤 2：去 project_id 列 + 改唯一约束（lineage_agent_config 改为租户级）。
ALTER TABLE lineage_agent_config DROP CONSTRAINT IF EXISTS uk_lineage_agent_config_tp;
ALTER TABLE lineage_agent_config DROP COLUMN IF EXISTS project_id;
ALTER TABLE lineage_agent_config ADD CONSTRAINT uk_lineage_agent_config_tenant UNIQUE (tenant_id, deleted);

-- lineage_agent_call 保留 project_id（审计按项目/任务溯源，FR-011），不动。

-- 步骤 3：登记版本号。
INSERT INTO schema_version (version, applied_at, description)
VALUES ('0.13.0', CURRENT_TIMESTAMP,
        '057 全局 AI Agent 配置统收（PG 参考脚本；H2/dev 由 schema.sql 重建）')
ON CONFLICT (version) DO NOTHING;
