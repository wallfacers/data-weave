-- ============================================================
-- V__add-next-trigger · 向后兼容迁移（PostgreSQL）
-- workflow_def 增加 next_trigger_time / schedule_interval_ms 列 + 扫描索引
-- ------------------------------------------------------------
-- 适应「短周期扫描 + 预读时间窗口 + 到点精确触发」调度模型：
--   next_trigger_time  = 下一次计划触发时间（单值持久化；NULL=首轮回填）
--   schedule_interval_ms = FIXED_RATE/FIXED_DELAY 周期（毫秒）；CRON 为 NULL
-- 开发态（H2）由 schema.sql 重建即可，本脚本仅供 PG 环境手工执行。
-- ============================================================

BEGIN;

-- 1) 新增 next_trigger_time：下一次计划触发时间
ALTER TABLE workflow_def
    ADD COLUMN IF NOT EXISTS next_trigger_time TIMESTAMP;

-- 2) 新增 schedule_interval_ms：FIXED_RATE/FIXED_DELAY 周期
ALTER TABLE workflow_def
    ADD COLUMN IF NOT EXISTS schedule_interval_ms BIGINT;

-- 3) 扫描索引：支撑「按类型/状态 + next_trigger_time 范围」高频扫描
CREATE INDEX IF NOT EXISTS idx_workflow_def_scan
    ON workflow_def (deleted, schedule_type, status, next_trigger_time);

COMMIT;
