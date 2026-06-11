-- 种子数据（企业级 schema）。审计列：created_by/updated_by=1, deleted=0, version=0。
-- 统一定义时间 2026-06-01；运行态时间 2026-06-10（今天）。

-- ===== 域 A · 租户与 RBAC =====
INSERT INTO tenants (id, code, name, status, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 'default', '默认租户', 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO permissions (id, code, name, resource, action, description, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 'task:manage', '任务管理', 'task', 'manage', '任务定义的增删改查与上下线', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 'workflow:manage', '任务流管理', 'workflow', 'manage', '任务流(DAG)的编排与调度', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(3, 'metric:manage', '指标管理', 'metric', 'manage', '原子/派生指标的定义与口径', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(4, 'datasource:manage', '数据源管理', 'datasource', 'manage', '数据源的接入与测试', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO projects (id, tenant_id, code, name, owner_id, status, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 'demo', '示例项目', 1, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

-- password_hash 用明文标记 {plain}xxx，启动时 PasswordInitializer 自动替换为 BCrypt。
INSERT INTO users (id, tenant_id, username, password_hash, display_name, email, phone, status, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 'admin',   '{plain}admin',   '管理员',     'admin@dataweave.local',   NULL, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 'analyst', '{plain}analyst', '数据分析师', 'analyst@dataweave.local', NULL, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO roles (id, tenant_id, code, name, description, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 'ADMIN',     '管理员', '全部权限', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 'DEVELOPER', '开发', '任务/任务流/指标开发', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(3, 1, 'VIEWER',    '只读', '仅查看', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO user_role (id, tenant_id, user_id, role_id, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 1, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 2, 2, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO role_permission (id, tenant_id, role_id, permission_id, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 1, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 1, 2, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(3, 1, 1, 3, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(4, 1, 1, 4, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO project_member (id, tenant_id, project_id, user_id, role_id, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 1, 1, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 1, 2, 2, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

-- ===== 域 B · 数据源 =====
INSERT INTO datasource_types (id, code, name, category, driver, default_port, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 'MYSQL',    'MySQL',      'RDB', 'com.mysql.cj.jdbc.Driver', 3306, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 'POSTGRES', 'PostgreSQL', 'RDB', 'org.postgresql.Driver',    5432, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(3, 'HIVE',     'Hive',       'MPP', 'org.apache.hive.jdbc.HiveDriver', 10000, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO datasources (id, tenant_id, project_id, name, type_code, host, port, database_name, jdbc_url, username, password_enc, props_json, status, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 'orders_mysql', 'MYSQL', '10.0.0.20', 3306, 'shop', 'jdbc:mysql://10.0.0.20:3306/shop', 'app', '***', NULL, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

-- ===== 域 C · 任务与任务流 DAG =====
INSERT INTO task_def (id, tenant_id, project_id, name, type, content, datasource_id, target_datasource_id, params_json, timeout_sec, retry_max, status, current_version_no, has_draft_change, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 'GMV 统计',     'SQL', 'select sum(order_amount) from orders', 1, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 1, '订单宽表加工', 'SQL', 'insert into dwd_orders select * from orders', 1, 1, NULL, 1800, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-05 00:00:00', TIMESTAMP '2026-06-05 00:00:00', 0, 0),
(3, 1, 1, '用户画像聚合', 'SQL', 'insert into dws_user_profile select ...', 1, 1, NULL, 1800, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0);

-- 任务定义已发布版本快照（v1）
INSERT INTO task_def_version (id, tenant_id, project_id, task_id, version_no, name, type, content, datasource_id, target_datasource_id, params_json, timeout_sec, retry_max, remark, published_by, published_at, created_at) VALUES
(1, 1, 1, 1, 1, 'GMV 统计',     'SQL', 'select sum(order_amount) from orders', 1, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00'),
(2, 1, 1, 2, 1, '订单宽表加工', 'SQL', 'insert into dwd_orders select * from orders', 1, 1, NULL, 1800, 1, '首次发布', 1, TIMESTAMP '2026-06-05 00:00:00', TIMESTAMP '2026-06-05 00:00:00'),
(3, 1, 1, 3, 1, '用户画像聚合', 'SQL', 'insert into dws_user_profile select ...', 1, 1, NULL, 1800, 1, '首次发布', 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00');

INSERT INTO workflow_def (id, tenant_id, project_id, name, description, schedule_type, cron, schedule_start, schedule_end, status, current_version_no, has_draft_change, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, '每日 GMV 工作流', '订单宽表 → GMV 统计 / 用户画像', 'CRON', '0 0 2 * * ?', TIMESTAMP '2026-06-06 00:00:00', NULL, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0),
(2, 1, 1, '下游日报工作流', '依赖「每日 GMV 工作流」今日成功后出日报', 'DEPENDENCY', '0 0 5 * * ?', TIMESTAMP '2026-06-07 00:00:00', NULL, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-07 00:00:00', TIMESTAMP '2026-06-07 00:00:00', 0, 0);

-- 任务流已发布版本快照（v1，dag_snapshot_json 冻结整张 DAG）
INSERT INTO workflow_def_version (id, tenant_id, project_id, workflow_id, version_no, name, description, schedule_type, cron, dag_snapshot_json, remark, published_by, published_at, created_at) VALUES
(1, 1, 1, 1, 1, '每日 GMV 工作流', '订单宽表 → GMV 统计 / 用户画像', 'CRON', '0 0 2 * * ?',
  '{"nodes":[{"key":"n1","taskId":2,"taskVersion":1},{"key":"n2","taskId":1,"taskVersion":1},{"key":"n3","taskId":3,"taskVersion":1}],"edges":[["n1","n2"],["n1","n3"]]}',
  '首次发布', 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00'),
(2, 1, 1, 2, 1, '下游日报工作流', '依赖上游 GMV 工作流', 'CRON', '0 0 5 * * ?',
  '{"nodes":[],"edges":[],"dependsOn":[{"workflowId":1,"dateOffset":"CURRENT_DAY"}]}',
  '首次发布', 1, TIMESTAMP '2026-06-07 00:00:00', TIMESTAMP '2026-06-07 00:00:00');

-- 跨任务流依赖：工作流2（今日）依赖工作流1（今日）整条成功
INSERT INTO workflow_dependency (id, tenant_id, project_id, workflow_id, node_id, depend_workflow_id, depend_node_id, date_offset, dep_type, enabled, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 2, NULL, 1, NULL, 'CURRENT_DAY', 'ALL_SUCCESS', 1, 1, 1, TIMESTAMP '2026-06-07 00:00:00', TIMESTAMP '2026-06-07 00:00:00', 0, 0);

-- DAG 节点：node1=订单宽表(task2)  node2=GMV统计(task1)  node3=用户画像(task3)
INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, task_id, node_key, name, pos_x, pos_y, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 1, 2, 'n1', '订单宽表加工', 100, 100, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0),
(2, 1, 1, 1, 1, 'n2', 'GMV 统计',     300, 60,  1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0),
(3, 1, 1, 1, 3, 'n3', '用户画像聚合', 300, 160, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0);

-- DAG 边：node1 → node2，node1 → node3
INSERT INTO workflow_edge (id, tenant_id, project_id, workflow_id, from_node_id, to_node_id, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 1, 1, 2, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0),
(2, 1, 1, 1, 1, 3, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0);

-- 工作流实例：wi1 失败(诊断现场) / wi2 成功 / wi3 运行中(含等待)
INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, workflow_version_no, trigger_type, state, biz_date, started_at, finished_at, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 1, 1, 'CRON',   'FAILED',  '2026-06-09', TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:08:00', 1, 1, TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:08:00', 0, 0),
(2, 1, 1, 1, 1, 'MANUAL', 'SUCCESS', '2026-06-09', TIMESTAMP '2026-06-10 08:00:00', TIMESTAMP '2026-06-10 08:02:00', 1, 1, TIMESTAMP '2026-06-10 08:00:00', TIMESTAMP '2026-06-10 08:02:00', 0, 0),
(3, 1, 1, 1, 1, 'MANUAL', 'RUNNING', '2026-06-10', TIMESTAMP '2026-06-10 09:30:00', NULL,                          1, 1, TIMESTAMP '2026-06-10 09:30:00', TIMESTAMP '2026-06-10 09:30:00', 0, 0);

-- 节点实例
-- wi1：n1 失败(OOM@node-3) → n2/n3 因上游失败被取消 STOPPED
INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, workflow_node_id, task_id, task_version_no, run_mode, state, attempt, worker_node_code, started_at, finished_at, log, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 1, 1, 2, 1, 'NORMAL', 'FAILED',  1, 'node-3', TIMESTAMP '2026-06-10 02:00:03', TIMESTAMP '2026-06-10 02:07:51', 'java.lang.OutOfMemoryError: Java heap space at executor stage 3; container killed by YARN, used 8.1GB of 8GB physical memory', 1, 1, TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:07:51', 0, 0),
(2, 1, 1, 1, 2, 1, 1, 'NORMAL', 'STOPPED', 0, NULL,     NULL, NULL, '上游 订单宽表加工 失败，本节点被取消调度', 1, 1, TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:08:00', 0, 0),
(3, 1, 1, 1, 3, 3, 1, 'NORMAL', 'STOPPED', 0, NULL,     NULL, NULL, '上游 订单宽表加工 失败，本节点被取消调度', 1, 1, TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:08:00', 0, 0),
-- wi2：全部成功
(4, 1, 1, 2, 1, 2, 1, 'NORMAL', 'SUCCESS', 1, 'node-1', TIMESTAMP '2026-06-10 08:00:05', TIMESTAMP '2026-06-10 08:00:55', '[mock] 执行成功', 1, 1, TIMESTAMP '2026-06-10 08:00:00', TIMESTAMP '2026-06-10 08:00:55', 0, 0),
(5, 1, 1, 2, 2, 1, 1, 'NORMAL', 'SUCCESS', 1, 'node-2', TIMESTAMP '2026-06-10 08:01:00', TIMESTAMP '2026-06-10 08:01:30', '[mock] 执行成功', 1, 1, TIMESTAMP '2026-06-10 08:01:00', TIMESTAMP '2026-06-10 08:01:30', 0, 0),
(6, 1, 1, 2, 3, 3, 1, 'NORMAL', 'SUCCESS', 1, 'node-5', TIMESTAMP '2026-06-10 08:01:00', TIMESTAMP '2026-06-10 08:01:40', '[mock] 执行成功', 1, 1, TIMESTAMP '2026-06-10 08:01:00', TIMESTAMP '2026-06-10 08:01:40', 0, 0),
-- wi3：n1 运行中 → n2/n3 等待上游
(7, 1, 1, 3, 1, 2, 1, 'NORMAL', 'RUNNING', 1, 'node-5', TIMESTAMP '2026-06-10 09:30:05', NULL, '执行中…', 1, 1, TIMESTAMP '2026-06-10 09:30:00', TIMESTAMP '2026-06-10 09:30:05', 0, 0),
(8, 1, 1, 3, 2, 1, 1, 'NORMAL', 'WAITING', 0, NULL,     NULL, NULL, '等待上游 订单宽表加工 完成', 1, 1, TIMESTAMP '2026-06-10 09:30:00', TIMESTAMP '2026-06-10 09:30:00', 0, 0),
(9, 1, 1, 3, 3, 3, 1, 'NORMAL', 'WAITING', 0, NULL,     NULL, NULL, '等待上游 订单宽表加工 完成', 1, 1, TIMESTAMP '2026-06-10 09:30:00', TIMESTAMP '2026-06-10 09:30:00', 0, 0),
-- 试跑：脱离工作流、跑草稿版(task_version_no=NULL)、run_mode=TEST，不计入生产
(10, 1, 1, NULL, NULL, 1, NULL, 'TEST', 'SUCCESS', 1, 'node-5', TIMESTAMP '2026-06-10 11:00:00', TIMESTAMP '2026-06-10 11:00:08', '[test] 试跑成功，返回 1 行：GMV=1859.87', 1, 1, TIMESTAMP '2026-06-10 11:00:00', TIMESTAMP '2026-06-10 11:00:08', 0, 0);

-- ===== 域 D · 指标体系 =====
INSERT INTO dimensions (id, tenant_id, project_id, code, name, data_type, expr, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 'city', '城市', 'STRING', 'orders.city', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO atomic_metrics (id, tenant_id, project_id, code, name, datasource_id, source_table, measure_expr, agg_type, unit, owner_id, version_no, status, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 'GMV',       'GMV',   1, 'orders', 'sum(order_amount)', 'SUM',   '元', 1, 1, 'ONLINE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 1, 'ORDER_CNT', '订单数', 1, 'orders', 'count(*)',          'COUNT', '单', 1, 1, 'ONLINE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO derived_metrics (id, tenant_id, project_id, code, name, formula, atomic_refs_json, dimension_refs_json, filter_expr, time_window, owner_id, version_no, status, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 'AOV', '客单价', 'GMV / ORDER_CNT', '["GMV","ORDER_CNT"]', '["city"]', NULL, '1d', 1, 1, 'ONLINE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO metric_dimension (id, tenant_id, project_id, metric_type, metric_id, dimension_id, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 'ATOMIC', 1, 1, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO metric_lineage (id, tenant_id, project_id, metric_type, metric_id, downstream_type, downstream_id, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 'ATOMIC', 1, 'TABLE', 'orders', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

-- ===== 域 E · 资源与诊断 =====
-- node-3 内存吃紧(95%)且并发2，是失败根因现场；node-4 心跳超时离线
INSERT INTO worker_nodes (id, node_code, host, ip, capacity, cpu, mem, disk, load_avg, running_tasks, status, last_heartbeat, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 'node-1', 'worker-1', '10.0.0.11', '8C/16G', 35.0, 48.0, 52.0, 2.10, 1, 'ONLINE',  TIMESTAMP '2026-06-10 10:00:00', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-10 10:00:00', 0, 0),
(2, 'node-2', 'worker-2', '10.0.0.12', '8C/16G', 41.0, 55.0, 60.0, 2.80, 1, 'ONLINE',  TIMESTAMP '2026-06-10 10:00:00', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-10 10:00:00', 0, 0),
(3, 'node-3', 'worker-3', '10.0.0.13', '8C/8G',  72.0, 95.0, 78.0, 9.40, 2, 'ONLINE',  TIMESTAMP '2026-06-10 10:00:00', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-10 10:00:00', 0, 0),
(4, 'node-4', 'worker-4', '10.0.0.14', '8C/16G', 0.0,  0.0,  33.0, 0.00, 0, 'OFFLINE', TIMESTAMP '2026-06-10 06:12:00', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-10 06:12:00', 0, 0),
(5, 'node-5', 'worker-5', '10.0.0.15', '8C/16G', 12.0, 30.0, 40.0, 0.90, 1, 'ONLINE',  TIMESTAMP '2026-06-10 10:00:00', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-10 10:00:00', 0, 0);

INSERT INTO task_diagnosis (id, tenant_id, project_id, task_instance_id, workflow_instance_id, task_id, worker_node_code, title, root_cause, context_json, suggestions_json, status, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 1, 1, 2, 'node-3',
  '订单宽表加工 失败 · 节点内存不足导致 OOM',
  'node-3 内存使用率 95%，本任务在 stage 3 触发 OutOfMemoryError 被容器终止；同时段 node-3 上还并发运行 2 个任务，存在资源争抢。',
  '{"nodeId":"node-3","nodeMem":95,"nodeCpu":72,"nodeLoad":9.4,"concurrentTasks":2,"history":"近 7 天该任务在 node-3 失败 2 次"}',
  '[{"action":"RERUN_MORE_MEMORY","label":"调大 executor 内存重跑"},{"action":"MIGRATE_NODE","label":"迁移到空闲节点 node-5 重跑"},{"action":"CAP_NODE_WEIGHT","label":"为 node-3 设置调度权重上限"}]',
  'OPEN', 1, 1, TIMESTAMP '2026-06-10 02:08:00', TIMESTAMP '2026-06-10 02:08:00', 0, 0);

-- ===== 域 F · 告警 =====
INSERT INTO notification_channels (id, tenant_id, name, type, config_json, enabled, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, '默认日志通道', 'LOG', NULL, 1, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO alert_rules (id, tenant_id, project_id, name, target_type, target_id, condition_expr, level, channel_id, enabled, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 'GMV 工作流失败告警', 'WORKFLOW', '1', 'state = FAILED', 'CRITICAL', 1, 1, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0);

-- ===== 域 G · 审计与 mock 业务 =====
INSERT INTO audit_log (id, tenant_id, project_id, user_id, action, target_type, target_id, detail_json, created_at) VALUES
(1, 1, 1, 1, 'CREATE', 'WORKFLOW', '1', '{"name":"每日 GMV 工作流"}', TIMESTAMP '2026-06-06 00:00:00'),
(2, 1, 1, 1, 'DIAGNOSE', 'TASK_INSTANCE', '1', '{"result":"OOM@node-3"}', TIMESTAMP '2026-06-10 02:08:00');

-- ===== 域 H · Agent 策略规则（policy_rules）=====
-- 分级维度：爆炸半径 × 可逆性 × 资源归属 × 环境。归属/环境/数量阈值在 PolicyEngine 运行时抬升，
-- 此处只定基础等级。宁严勿松：未匹配的写动作由引擎默认按 L2 处理。
-- CMD_PREFIX 用于 node_exec 命令串首词裁决；TOOL 用于 MCP 工具名裁决。
INSERT INTO policy_rules (id, match_type, pattern, condition_expr, base_level, description, enabled, sort_order, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
-- 只读命令前缀（L0）
(1,  'CMD_PREFIX', 'df',                NULL, 'L0', '磁盘用量（只读）',           1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2,  'CMD_PREFIX', 'free',              NULL, 'L0', '内存用量（只读）',           1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(3,  'CMD_PREFIX', 'jstat',             NULL, 'L0', 'JVM GC 统计（只读）',        1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(4,  'CMD_PREFIX', 'tail',              NULL, 'L0', '看日志尾部（只读）',         1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(5,  'CMD_PREFIX', 'grep',              NULL, 'L0', '过滤（只读）',               1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(6,  'CMD_PREFIX', 'cat',               NULL, 'L0', '看文件（只读）',             1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(7,  'CMD_PREFIX', 'dw logs',           NULL, 'L0', 'dw 看日志（只读）',          1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(8,  'CMD_PREFIX', 'dw task list',      NULL, 'L0', 'dw 任务列表（只读）',        1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(9,  'CMD_PREFIX', 'dw task show',      NULL, 'L0', 'dw 任务详情（只读）',        1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(10, 'CMD_PREFIX', 'dw task instances', NULL, 'L0', 'dw 实例列表（只读）',        1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- 可逆例行写命令前缀（L1）
(11, 'CMD_PREFIX', 'dw task rerun',     NULL, 'L1', 'dw 重跑（可逆例行）',        1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- 禁止命令前缀（L4）
(12, 'CMD_PREFIX', 'rm',                NULL, 'L4', '删除文件（禁止）',           1, 5,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(13, 'CMD_PREFIX', 'mkfs',             NULL, 'L4', '格式化（禁止）',             1, 5,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(14, 'CMD_PREFIX', 'shutdown',          NULL, 'L4', '关机（禁止）',               1, 5,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- 只读 MCP 工具（L0）
(20, 'TOOL', 'query_task_definitions',  NULL, 'L0', '查任务定义（只读）',         1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(21, 'TOOL', 'query_task_instances',    NULL, 'L0', '查任务实例（只读）',         1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(22, 'TOOL', 'query_fleet',             NULL, 'L0', '查机器集群（只读）',         1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(23, 'TOOL', 'query_metric',            NULL, 'L0', '查指标（只读）',             1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(24, 'TOOL', 'query_lineage',           NULL, 'L0', '查血缘（只读）',             1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(25, 'TOOL', 'query_diagnosis',         NULL, 'L0', '查诊断（只读）',             1, 10, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- 可逆例行写 MCP 工具（L1）
(30, 'TOOL', 'task_rerun',              NULL, 'L1', '重跑任务实例（可逆例行）',   1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(31, 'TOOL', 'create_task',             NULL, 'L1', '建任务并上线（可逆）',       1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(32, 'TOOL', 'apply_fix',               NULL, 'L1', '一键修复（可逆例行）',       1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(33, 'TOOL', 'node_exec',               NULL, 'L1', '节点受控执行（按命令串解析抬升）', 1, 25, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- 不可逆 MCP 工具（L3，需二次确认）
(40, 'TOOL', 'drop_table',              NULL, 'L3', '删表（不可逆）',             1, 30, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(41, 'TOOL', 'delete_topic',            NULL, 'L3', '删 topic（不可逆）',         1, 30, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO orders (id, order_amount, city, created_at) VALUES
(1, 120.50, '上海', TIMESTAMP '2026-06-01 09:12:00'),
(2, 89.00,  '北京', TIMESTAMP '2026-06-01 10:30:00'),
(3, 256.75, '上海', TIMESTAMP '2026-06-02 14:05:00'),
(4, 42.10,  '广州', TIMESTAMP '2026-06-02 16:48:00'),
(5, 999.99, '深圳', TIMESTAMP '2026-06-03 08:00:00'),
(6, 18.20,  '北京', TIMESTAMP '2026-06-03 11:22:00'),
(7, 333.33, '深圳', TIMESTAMP '2026-06-04 19:40:00');

-- 自增起点推到已用 id 之后（H2 / PostgreSQL 均兼容）
ALTER TABLE tenants ALTER COLUMN id RESTART WITH 100;
ALTER TABLE permissions ALTER COLUMN id RESTART WITH 100;
ALTER TABLE projects ALTER COLUMN id RESTART WITH 100;
ALTER TABLE users ALTER COLUMN id RESTART WITH 100;
ALTER TABLE roles ALTER COLUMN id RESTART WITH 100;
ALTER TABLE user_role ALTER COLUMN id RESTART WITH 100;
ALTER TABLE role_permission ALTER COLUMN id RESTART WITH 100;
ALTER TABLE project_member ALTER COLUMN id RESTART WITH 100;
ALTER TABLE datasource_types ALTER COLUMN id RESTART WITH 100;
ALTER TABLE datasources ALTER COLUMN id RESTART WITH 100;
ALTER TABLE task_def ALTER COLUMN id RESTART WITH 100;
ALTER TABLE task_def_version ALTER COLUMN id RESTART WITH 100;
ALTER TABLE workflow_def ALTER COLUMN id RESTART WITH 100;
ALTER TABLE workflow_def_version ALTER COLUMN id RESTART WITH 100;
ALTER TABLE workflow_dependency ALTER COLUMN id RESTART WITH 100;
ALTER TABLE workflow_node ALTER COLUMN id RESTART WITH 100;
ALTER TABLE workflow_edge ALTER COLUMN id RESTART WITH 100;
ALTER TABLE workflow_instance ALTER COLUMN id RESTART WITH 100;
ALTER TABLE task_instance ALTER COLUMN id RESTART WITH 100;
ALTER TABLE dimensions ALTER COLUMN id RESTART WITH 100;
ALTER TABLE atomic_metrics ALTER COLUMN id RESTART WITH 100;
ALTER TABLE derived_metrics ALTER COLUMN id RESTART WITH 100;
ALTER TABLE metric_dimension ALTER COLUMN id RESTART WITH 100;
ALTER TABLE metric_lineage ALTER COLUMN id RESTART WITH 100;
ALTER TABLE worker_nodes ALTER COLUMN id RESTART WITH 100;
ALTER TABLE task_diagnosis ALTER COLUMN id RESTART WITH 100;
ALTER TABLE notification_channels ALTER COLUMN id RESTART WITH 100;
ALTER TABLE alert_rules ALTER COLUMN id RESTART WITH 100;
ALTER TABLE audit_log ALTER COLUMN id RESTART WITH 100;
ALTER TABLE policy_rules ALTER COLUMN id RESTART WITH 100;
ALTER TABLE agent_session ALTER COLUMN id RESTART WITH 100;
ALTER TABLE agent_run ALTER COLUMN id RESTART WITH 100;
ALTER TABLE agent_step ALTER COLUMN id RESTART WITH 100;
ALTER TABLE agent_action ALTER COLUMN id RESTART WITH 100;
