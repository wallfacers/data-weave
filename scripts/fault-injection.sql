-- ============================================================
-- 故障注入脚本（proactive-discovery · live-telemetry L3）
--
-- 用途：测试 / demo 期手动注入「真实」的失败素材，喂给主动发现链路
--       （巡检器 → 自动诊断 → 举手台冒泡 → Agent 主动开口 → 闸门修复）。
--
-- 非运行时组件：本脚本不随应用启动加载、生产 profile 不参与，仅手动执行，杜绝污染真实采集。
--
-- 运行（PG 默认库）：
--   psql "$DATABASE_URL" -f scripts/fault-injection.sql
-- 运行（容器内 PG）：
--   docker compose exec -T postgres psql -U dataweave -d dataweave < scripts/fault-injection.sql
--
-- 可重跑：固定 synthetic UUID，先删后插，幂等。
-- ============================================================

-- 1) 拉高目标节点 node-3 的真实水位（内存 95% → 触发 OOM 规则；load/cpu 高 → 争抢）
UPDATE worker_nodes
   SET mem = 95.0, cpu = 72.0, load_avg = 9.40, running_tasks = 3, status = 'ONLINE'
 WHERE node_code = 'node-3';

-- 2) 清理上一轮注入（按 synthetic id 前缀），保证可重跑
DELETE FROM task_instance WHERE id IN (
  '01910000-fa17-7000-8000-000000000001',
  '01910000-fa17-7000-8000-000000000002',
  '01910000-fa17-7000-8000-000000000003'
);

-- 3) 注入一条真实 FAILED 实例（日志含 OOM 堆栈，finished_at 取当前时刻附近）
INSERT INTO task_instance
  (id, tenant_id, project_id, task_id, run_mode, state, attempt, worker_node_code,
   started_at, finished_at, log, exit_code, failure_reason,
   created_by, updated_by, created_at, updated_at, deleted, version)
VALUES
  ('01910000-fa17-7000-8000-000000000001', 1, 1, 2, 'NORMAL', 'FAILED', 1, 'node-3',
   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
   'stage 3: shuffle read 4.2GB... java.lang.OutOfMemoryError: Java heap space at org.apache.spark.executor.Executor... Container killed by YARN for exceeding memory limits. 9.4 GB of 8 GB physical memory used',
   137, 'EXIT_NONZERO', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0);

-- 4) 同节点并发运行 2 个实例 → master 端聚合得真实 concurrentTasks（资源争抢证据）
INSERT INTO task_instance
  (id, tenant_id, project_id, task_id, run_mode, state, attempt, worker_node_code,
   started_at, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES
  ('01910000-fa17-7000-8000-000000000002', 1, 1, 3, 'NORMAL', 'RUNNING', 1, 'node-3',
   CURRENT_TIMESTAMP, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0),
  ('01910000-fa17-7000-8000-000000000003', 1, 1, 4, 'NORMAL', 'DISPATCHED', 1, 'node-3',
   CURRENT_TIMESTAMP, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0);

-- 注入完成后：下一轮 InspectorScheduler 巡检（或失败事件加速）会对上面的 FAILED 实例自动诊断，
-- 证据里的 concurrentTasks=2、history7d 为真实统计，举手台冒出新卡片，Agent 主动开口。
