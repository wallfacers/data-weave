-- ============================================================
-- workflow-canvas · 向后兼容迁移（PostgreSQL）
-- 画布编排能力：workflow_node 引入 node_type，task_id 放宽可空
-- ------------------------------------------------------------
-- 全部 ADD COLUMN / DROP NOT NULL，向后兼容：
--   既有节点 node_type 取默认 'TASK'，不影响现有调度。
-- 开发态（H2）由 schema.sql 重建即可，本脚本仅供 PG 环境手工执行。
-- ============================================================

BEGIN;

-- 1) 新增 node_type：TASK（绑 task_def）| VIRTUAL（zero-load 起始/汇聚锚点）
ALTER TABLE workflow_node
    ADD COLUMN IF NOT EXISTS node_type VARCHAR(32) DEFAULT 'TASK';

-- 2) 放宽 task_id 可空：VIRTUAL 节点不绑任务
ALTER TABLE workflow_node
    ALTER COLUMN task_id DROP NOT NULL;

-- 3) 存量数据兜底：历史节点统一视为 TASK
UPDATE workflow_node SET node_type = 'TASK' WHERE node_type IS NULL;

-- 4) task_instance.task_id 放宽可空：VIRTUAL 节点物化的实例不绑任务
ALTER TABLE task_instance
    ALTER COLUMN task_id DROP NOT NULL;

COMMIT;
