-- ============================================================
-- task-instance-locale · 向后兼容迁移（PostgreSQL）
-- task_instance 加 locale 列（VARCHAR(16)，nullable）—— 任务运行日志 banner 按 locale 渲染（i18n 规则②）。
-- ------------------------------------------------------------
-- ADD COLUMN IF NOT EXISTS，向后兼容：
--   既有 task_instance.locale 取默认 NULL → 消费端兜底 zh-CN（Messages.DEFAULT_LOCALE），行为同改动前。
-- 与兄弟变更 task-run-decouple-and-log-tabs 改同表互不依赖加载顺序，谁先归档都不冲突。
-- 开发态（H2）由 schema.sql 重建即可，本脚本仅供 PG 环境手工执行。
-- ============================================================

BEGIN;

ALTER TABLE task_instance ADD COLUMN IF NOT EXISTS locale VARCHAR(16);

COMMIT;
