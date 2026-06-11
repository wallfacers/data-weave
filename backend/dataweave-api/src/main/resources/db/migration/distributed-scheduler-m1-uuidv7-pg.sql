-- ============================================================
-- distributed-scheduler-m1 · BREAKING 迁移（PostgreSQL）
-- 实例类核心表主键 自增 BIGINT → UUIDv7（时间有序，由应用层 Uuid7 生成）
-- ------------------------------------------------------------
-- 适用前提：尚无生产实例数据（design D12「最后的廉价窗口」）。
-- 既有自增主键无法语义化转换为 UUID，故实例表与其外键引用整体重建。
-- 开发态（H2）由 schema.sql + data.sql 重建即可，本脚本仅供 PG 环境手工执行。
-- 执行前请确认 task_instance / workflow_instance / task_diagnosis 中无需保留的数据。
-- ============================================================

BEGIN;

-- 1) 丢弃实例类表（逆依赖序）。task_diagnosis 引用实例外键，先于实例表清理其列类型。
DROP TABLE IF EXISTS task_instance;
DROP TABLE IF EXISTS workflow_instance;

-- 2) 重建 workflow_instance（id → UUID）
CREATE TABLE workflow_instance (
    id           UUID PRIMARY KEY,              -- UUIDv7（应用层生成）
    tenant_id    BIGINT NOT NULL,
    project_id   BIGINT NOT NULL,
    workflow_id  BIGINT NOT NULL,
    workflow_version_no INTEGER,
    trigger_type VARCHAR(32),
    state        VARCHAR(32) DEFAULT 'NOT_RUN',
    biz_date     VARCHAR(32),
    total_tasks     INTEGER DEFAULT 0,
    completed_tasks INTEGER DEFAULT 0,
    failed_tasks    INTEGER DEFAULT 0,
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    created_by   BIGINT,
    updated_by   BIGINT,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    deleted      SMALLINT DEFAULT 0,
    version      INTEGER DEFAULT 0
);

-- 3) 重建 task_instance（id → UUID；workflow_instance_id 外键 → UUID）
--    注：1.2 将追加 lease_expire_at / failure_reason 等新字段，见后续迁移。
CREATE TABLE task_instance (
    id                   UUID PRIMARY KEY,       -- UUIDv7（应用层生成）
    tenant_id            BIGINT NOT NULL,
    project_id           BIGINT NOT NULL,
    workflow_instance_id UUID,
    workflow_node_id     BIGINT,
    task_id              BIGINT NOT NULL,
    task_version_no      INTEGER,
    run_mode             VARCHAR(32) DEFAULT 'NORMAL',
    state                VARCHAR(32) DEFAULT 'NOT_RUN',
    attempt              INTEGER DEFAULT 0,
    worker_node_code     VARCHAR(64),
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP,
    log                  TEXT,
    exit_code            INTEGER,
    error_message        VARCHAR(2000),
    created_by           BIGINT,
    updated_by           BIGINT,
    created_at           TIMESTAMP,
    updated_at           TIMESTAMP,
    deleted              SMALLINT DEFAULT 0,
    version              INTEGER DEFAULT 0
);

-- 4) task_diagnosis 外键列类型对齐（清空后改列类型；表内若无数据可直接 ALTER）
ALTER TABLE task_diagnosis ALTER COLUMN task_instance_id     TYPE UUID USING NULL;
ALTER TABLE task_diagnosis ALTER COLUMN workflow_instance_id TYPE UUID USING NULL;

COMMIT;
