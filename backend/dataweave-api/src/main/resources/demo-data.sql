-- demo profile 专用：预填的「演示诊断结论」假数据。
-- 默认 data.sql 不再包含这些预生成结论；仅 `demo` profile 在 data.sql 之后追加加载本文件
-- （见 application-demo.yml）。失败实例素材与节点注册仍在 data.sql，让 Inspector 去真诊断。
--
-- 本文件三段为同一 OOM@node-3 故障的预填结论：task_diagnosis / finding / audit_log(DIAGNOSE)。
-- 对应 data.sql 中的失败实例 task_instance '01910000-0010-7000-8000-000000000001'。
-- i18n 豁免（design D10）：演示数据保留中文。

INSERT INTO task_diagnosis (id, tenant_id, project_id, task_instance_id, workflow_instance_id, task_id, worker_node_code, title, root_cause, context_json, suggestions_json, status, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, '01910000-0010-7000-8000-000000000001', '01910000-0001-7000-8000-000000000001', 2, 'node-3',
  '订单宽表加工 失败 · 节点内存不足导致 OOM',
  'node-3 内存使用率 95%，本任务在 stage 3 触发 OutOfMemoryError 被容器终止；同时段 node-3 上还并发运行 2 个任务，存在资源争抢。',
  '{"nodeId":"node-3","nodeMem":95,"nodeCpu":72,"nodeLoad":9.4,"concurrentTasks":2,"history":"近 7 天该任务在 node-3 失败 2 次"}',
  '[{"action":"RERUN_MORE_MEMORY","label":"调大 executor 内存重跑"},{"action":"MIGRATE_NODE","label":"迁移到空闲节点 node-5 重跑"},{"action":"CAP_NODE_WEIGHT","label":"为 node-3 设置调度权重上限"}]',
  'OPEN', 1, 1, TIMESTAMP '2026-06-10 02:08:00', TIMESTAMP '2026-06-10 02:08:00', 0, 0);

-- 首屏 Finding（与上面 OOM 诊断对应；source=TASK_FAILURE，举手台开箱即有一张真证据卡片）。
-- 运行期由 TaskFailureInspector 自动产出；此处仅保证 demo profile 首屏不空。
INSERT INTO finding (id, tenant_id, project_id, source, severity, target_type, target_id, title, root_cause, evidence_json, actions_json, status, announced, task_diagnosis_id, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 'TASK_FAILURE', 'CRITICAL', 'TASK_INSTANCE', '01910000-0010-7000-8000-000000000001',
  '订单宽表加工 失败 · 节点内存不足导致 OOM',
  'node-3 内存使用率 95%，本任务在 stage 3 触发 OutOfMemoryError 被容器终止；同时段 node-3 上还并发运行 2 个任务，存在资源争抢。',
  '{"nodeId":"node-3","nodeMem":95,"nodeCpu":72,"nodeLoad":9.4,"concurrentTasks":2,"history":"近 7 天该任务在 node-3 失败 2 次"}',
  '[{"key":"RERUN_MORE_MEMORY","label":"调大 executor 内存重跑","actionType":"APPLY_FIX_RERUN_MORE_MEMORY"},{"key":"MIGRATE_NODE","label":"迁移到空闲节点 node-5 重跑","actionType":"APPLY_FIX_MIGRATE_NODE"},{"key":"CAP_NODE_WEIGHT","label":"为 node-3 设置调度权重上限","actionType":"APPLY_FIX_CAP_NODE_WEIGHT"}]',
  'OPEN', 0, 1, 1, 1, TIMESTAMP '2026-06-10 02:08:00', TIMESTAMP '2026-06-10 02:08:00', 0, 0);
ALTER TABLE finding ALTER COLUMN id RESTART WITH 100;

-- 审计：DIAGNOSE 记录（对应上面预填诊断结论）。
INSERT INTO audit_log (id, tenant_id, project_id, user_id, action, target_type, target_id, detail_json, created_at) VALUES
(2, 1, 1, 1, 'DIAGNOSE', 'TASK_INSTANCE', '01910000-0010-7000-8000-000000000001', '{"result":"OOM@node-3"}', TIMESTAMP '2026-06-10 02:08:00');
