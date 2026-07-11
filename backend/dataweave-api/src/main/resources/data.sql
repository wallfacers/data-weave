-- 种子数据（企业级 schema）。审计列：created_by/updated_by=1, deleted=0, version=0。
-- 统一定义时间 2026-06-01；运行态时间 2026-06-10（今天）。
-- i18n 豁免（design D10）：本文件全部业务种子数据（角色名/任务名/指标中文名/演示 error_message
-- 与诊断 JSON 等）为演示数据，保留中文、不做双语化。i18n 仅覆盖代码路径文案，不含种子数据。

-- ===== 域 A · 租户与 RBAC =====
INSERT INTO tenants (id, code, name, status, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 'default', '默认租户', 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO permissions (id, code, name, resource, action, description, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 'task:manage', '任务管理', 'task', 'manage', '任务定义的增删改查与上下线', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 'workflow:manage', '任务流管理', 'workflow', 'manage', '任务流(DAG)的编排与调度', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(3, 'metric:manage', '指标管理', 'metric', 'manage', '原子/派生指标的定义与口径', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(4, 'datasource:manage', '数据源管理', 'datasource', 'manage', '数据源的接入与测试', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- 036-D：项目管理权限（成员管理/项目设置），仅 ADMIN 持有；驱动 settings 视图与成员管理端点授权。
(5, 'project:manage', '项目管理', 'project', 'manage', '项目设置与成员管理（含角色分配）', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

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

-- 036-D 角色×权限矩阵：ADMIN 全权（含 project:manage）；DEVELOPER 开发四权（无 project:manage）；VIEWER 无（只读，靠"无权限"自然受限）。
INSERT INTO role_permission (id, tenant_id, role_id, permission_id, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
-- ADMIN(role 1) → task / workflow / metric / datasource / project:manage
(1, 1, 1, 1, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 1, 2, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(3, 1, 1, 3, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(4, 1, 1, 4, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(5, 1, 1, 5, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- DEVELOPER(role 2) → task / workflow / metric / datasource:manage
(6, 1, 2, 1, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(7, 1, 2, 2, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(8, 1, 2, 3, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(9, 1, 2, 4, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO project_member (id, tenant_id, project_id, user_id, role_id, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 1, 1, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 1, 2, 2, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

-- ===== 域 B · 数据源 =====
INSERT INTO datasource_types (id, code, name, category, driver, default_port, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
-- RDB（关系型数据库）
(1,  'MYSQL',        'MySQL',         'RDB',     'com.mysql.cj.jdbc.Driver',             3306,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2,  'POSTGRES',     'PostgreSQL',    'RDB',     'org.postgresql.Driver',                5432,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(3,  'ORACLE',       'Oracle',        'RDB',     'oracle.jdbc.OracleDriver',             1521,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(4,  'SQLSERVER',    'SQL Server',    'RDB',     'com.microsoft.sqlserver.jdbc.SQLServerDriver', 1433, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(5,  'MARIADB',      'MariaDB',       'RDB',     'org.mariadb.jdbc.Driver',              3306,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(6,  'DB2',          'DB2',           'RDB',     'com.ibm.db2.jcc.DB2Driver',            50000, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- MPP / OLAP（分析型数据库）
(7,  'HIVE',         'Hive',          'MPP',     'org.apache.hive.jdbc.HiveDriver',      10000, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(8,  'IMPALA',       'Impala',        'MPP',     'com.cloudera.impala.jdbc.Driver',      21050, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(9,  'CLICKHOUSE',   'ClickHouse',    'MPP',     'com.clickhouse.jdbc.ClickHouseDriver', 8123,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(10, 'STARROCKS',    'StarRocks',     'MPP',     'com.mysql.cj.jdbc.Driver',             9030,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(11, 'DORIS',        'Doris',         'MPP',     'com.mysql.cj.jdbc.Driver',             9030,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- NoSQL
(12, 'MONGODB',      'MongoDB',       'NOSQL',   NULL,                                   27017, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(13, 'REDIS',        'Redis',         'NOSQL',   NULL,                                   6379,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(14, 'ELASTICSEARCH','Elasticsearch', 'NOSQL',   NULL,                                   9200,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(15, 'HBASE',        'HBase',         'NOSQL',   NULL,                                   16000, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- Storage（文件 / 对象存储）
(16, 'S3',           'S3/MinIO',      'STORAGE', NULL,                                   9000,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(17, 'HDFS',         'HDFS',          'STORAGE', NULL,                                   8020,  1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(18, 'FTP',          'FTP',           'STORAGE', NULL,                                   21,    1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

INSERT INTO datasources (id, tenant_id, project_id, name, type_code, host, port, database_name, jdbc_url, username, password_enc, props_json, status, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 'orders_mysql', 'MYSQL', '10.0.0.20', 3306, 'shop', 'jdbc:mysql://10.0.0.20:3306/shop', 'app', '***', NULL, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(2, 1, 1, 'local_mysql_biz', 'MYSQL', 'localhost', 3306, 'dataweave_biz', 'jdbc:mysql://localhost:3306/dataweave_biz', 'root', '***', NULL, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0);

-- ===== 域 C · 任务与任务流 DAG =====
INSERT INTO task_def (id, tenant_id, project_id, name, type, content, datasource_id, target_datasource_id, params_json, timeout_sec, retry_max, status, current_version_no, has_draft_change, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, 'GMV 统计',     'SQL', 'select sum(order_amount) from orders', 1, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 1, 0),
(2, 1, 1, '订单宽表加工', 'SQL', 'insert into dwd_orders select * from orders', 1, 1, NULL, 1800, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-05 00:00:00', TIMESTAMP '2026-06-05 00:00:00', 0, 0),
(3, 1, 1, '用户画像聚合', 'SQL', 'insert into dws_user_profile select ...', 1, 1, NULL, 1800, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0),
(4, 1, 1, '实时流量统计', 'SQL', 'select count(*) from access_log', 1, NULL, NULL, 300, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-10 00:00:00', TIMESTAMP '2026-06-10 00:00:00', 0, 0);

-- 任务定义已发布版本快照（v1）
INSERT INTO task_def_version (id, tenant_id, project_id, task_id, version_no, name, type, content, datasource_id, target_datasource_id, params_json, timeout_sec, retry_max, remark, published_by, published_at, created_at) VALUES
(1, 1, 1, 1, 1, 'GMV 统计',     'SQL', 'select sum(order_amount) from orders', 1, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00'),
(2, 1, 1, 2, 1, '订单宽表加工', 'SQL', 'insert into dwd_orders select * from orders', 1, 1, NULL, 1800, 1, '首次发布', 1, TIMESTAMP '2026-06-05 00:00:00', TIMESTAMP '2026-06-05 00:00:00'),
(3, 1, 1, 3, 1, '用户画像聚合', 'SQL', 'insert into dws_user_profile select ...', 1, 1, NULL, 1800, 1, '首次发布', 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00'),
(4, 1, 1, 4, 1, '实时流量统计', 'SQL', 'select count(*) from access_log', 1, NULL, NULL, 300, 1, '首次发布', 1, TIMESTAMP '2026-06-10 00:00:00', TIMESTAMP '2026-06-10 00:00:00');

INSERT INTO workflow_def (id, tenant_id, project_id, name, description, schedule_type, cron, schedule_start, schedule_end, status, current_version_no, has_draft_change, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(1, 1, 1, '每日 GMV 工作流', '仅用户画像（GMV 统计/订单宽表加工 已移除）', 'CRON', '0 0 2 * * ?', TIMESTAMP '2026-06-06 00:00:00', NULL, 'ONLINE', 2, 0, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0),
(2, 1, 1, '下游日报工作流', '依赖「每日 GMV 工作流」今日成功后出日报', 'DEPENDENCY', '0 0 5 * * ?', TIMESTAMP '2026-06-07 00:00:00', NULL, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-07 00:00:00', TIMESTAMP '2026-06-07 00:00:00', 0, 0);

-- 任务流已发布版本快照（dag_snapshot_json 冻结整张 DAG）。
-- 工作流 1 演示「发布后编辑删节点再晋级」的真实版本演进：
--   v1（id=1，3 节点）= 历史首次发布，三个历史实例 01910000-0001/0002/0003 即钉死此版（DAG 读此快照）；
--   v2（id=7，1 节点）= 当前线上版，移除 GMV 统计/订单宽表加工 后重新晋级，current_version_no=2。
-- 这样历史实例的 DAG 视图能完整呈现其当时跑过的全部节点（含失败节点），与 workflow_instance.state 自洽，
-- 不再出现「v1 快照只剩 1 节点却挂着 3 个 task_instance」的脏数据矛盾（真实发布按版本不可变，本 seed 此前错误地把两版压成一版）。
INSERT INTO workflow_def_version (id, tenant_id, project_id, workflow_id, version_no, name, description, schedule_type, cron, dag_snapshot_json, remark, published_by, published_at, created_at) VALUES
(1, 1, 1, 1, 1, '每日 GMV 工作流', '首次发布(3节点)', 'CRON', '0 0 2 * * ?',
  '{"nodes":[{"nodeKey":"n1","nodeType":"TASK","taskId":2,"taskVersionNo":1,"name":"订单宽表加工","posX":50,"posY":160},{"nodeKey":"n2","nodeType":"TASK","taskId":1,"taskVersionNo":1,"name":"GMV 统计","posX":350,"posY":80},{"nodeKey":"n3","nodeType":"TASK","taskId":3,"taskVersionNo":1,"name":"用户画像聚合","posX":350,"posY":240}],"edges":[{"fromNodeKey":"n1","toNodeKey":"n2","strength":"STRONG"},{"fromNodeKey":"n1","toNodeKey":"n3","strength":"STRONG"}]}',
  '首次发布', 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00'),
(7, 1, 1, 1, 2, '每日 GMV 工作流', '仅用户画像（GMV 统计/订单宽表加工 已移除）', 'CRON', '0 0 2 * * ?',
  '{"nodes":[{"nodeKey":"n3","nodeType":"TASK","taskId":3,"taskVersionNo":1,"name":"用户画像聚合","posX":300,"posY":160}],"edges":[]}',
  '移除 GMV 统计/订单宽表加工', 1, TIMESTAMP '2026-06-08 00:00:00', TIMESTAMP '2026-06-08 00:00:00'),
(2, 1, 1, 2, 1, '下游日报工作流', '依赖上游 GMV 工作流', 'CRON', '0 0 5 * * ?',
  '{"nodes":[],"edges":[],"dependsOn":[{"workflowId":1,"dateOffset":"CURRENT_DAY"}]}',
  '首次发布', 1, TIMESTAMP '2026-06-07 00:00:00', TIMESTAMP '2026-06-07 00:00:00');

-- 跨任务流依赖：工作流2（今日）依赖工作流1（今日）整条成功
INSERT INTO workflow_dependency (id, tenant_id, project_id, workflow_id, node_id, depend_workflow_id, depend_node_id, date_offset, dep_type, enabled, created_by, updated_by, created_at, updated_at, deleted, version)
VALUES (1, 1, 1, 2, NULL, 1, NULL, 'CURRENT_DAY', 'ALL_SUCCESS', 1, 1, 1, TIMESTAMP '2026-06-07 00:00:00', TIMESTAMP '2026-06-07 00:00:00', 0, 0);

-- DAG 节点：node3=用户画像(task3)（n1/n2 已移除，GMV 统计 和 订单宽表加工 不再关联工作流）
INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, task_id, node_key, name, pos_x, pos_y, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(3, 1, 1, 1, 3, 'n3', '用户画像聚合', 300, 160, 1, 1, TIMESTAMP '2026-06-06 00:00:00', TIMESTAMP '2026-06-06 00:00:00', 0, 0);

-- ===== workflow id=3「订单 SHELL 流水线」（6 节点 DAG：n1→n2→{n3,n4}→n5→n6）=====
-- 供 KernelSchedulingTest / CrossCycleDependencyTest / BackfillThrottleE2ETest（弱依赖 / 子图运行范围 / 跨周期 / 端到端）依赖。其他 change 重构 seed 时请勿删此链。
-- 节点 type=SHELL：内容保留大数据脚本叙事（sqoop/hive/hadoop 命令以 echo 模拟），用 SHELL 执行器真实运行，
-- 在任何环境（含沙箱/CI 无大数据二进制）均可 SUCCESS。既保 demo 真实感又让调度 E2E 可在裸环境断言 SUCCESS。
-- 真实大数据命令任务（DATAX/SEATUNNEL/FLINK 等）由后续 seed 补充。
INSERT INTO task_def (id, tenant_id, project_id, name, type, content, datasource_id, target_datasource_id, params_json, timeout_sec, retry_max, status, current_version_no, has_draft_change, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(10, 1, 1, '抽取-拉取订单分区', 'SHELL', '#!/bin/bash
# 抽取：从源库按分区拉取订单数据
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}
TABLE="orders"
PARTITION="dt=''${BIZ_DATE}''"

echo "[抽取] 开始拉取 ${TABLE} 分区 ${PARTITION}"
echo "[抽取] sqoop import --connect jdbc:mysql://db-source.prd/data_platform --table ${TABLE} --where \"${PARTITION}\" --target-dir /warehouse/ods/${TABLE}/${BIZ_DATE}/ --num-mappers 8"
echo "[抽取] 完成，共 1,234,567 行"', NULL, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(11, 1, 1, '清洗-去重生成宽表', 'SHELL', '#!/bin/bash
# 清洗：去重 + 关联维度表生成 DWD 宽表
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}

echo "[清洗] 开始加工 DWD 层，业务日期=${BIZ_DATE}"
echo "[清洗] hive -e \"INSERT OVERWRITE TABLE dwd.dwd_orders_wide PARTITION (dt=''${BIZ_DATE}'') SELECT o.order_id, o.user_id, o.order_amount, ... FROM ods.orders o LEFT JOIN dim.dim_user u ... QUALIFY rn = 1\""
echo "[清洗] 完成，输出 987,654 行"', NULL, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(12, 1, 1, '质检-行数阈值校验', 'SHELL', '#!/bin/bash
# 质检：校验 DWD 表行数在合理范围内
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}
MIN_ROWS=10000
MAX_ROWS=50000000
ACTUAL=987654

echo "[质检] 校验 DWD 行数阈值，业务日期=${BIZ_DATE}"
echo "[质检] hive -e \"SELECT COUNT(*) FROM dwd.dwd_orders_wide WHERE dt=''${BIZ_DATE}''\" -> ${ACTUAL}"

if [ "${ACTUAL}" -lt "${MIN_ROWS}" ]; then
  echo "[质检] FAILED: 行数 ${ACTUAL} < 下限 ${MIN_ROWS}，可能数据缺失"
  exit 1
fi

if [ "${ACTUAL}" -gt "${MAX_ROWS}" ]; then
  echo "[质检] FAILED: 行数 ${ACTUAL} > 上限 ${MAX_ROWS}，可能数据异常"
  exit 1
fi

echo "[质检] PASSED: 行数 ${ACTUAL} 在 [${MIN_ROWS}, ${MAX_ROWS}] 范围内"', NULL, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(13, 1, 1, '指标-GMV 汇总',     'SHELL', '#!/bin/bash
# 指标：按维度汇总 GMV 写入 DWS 层
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}

echo "[指标] 开始汇总 GMV，业务日期=${BIZ_DATE}"
echo "[指标] hive -e \"INSERT OVERWRITE TABLE dws.dws_gmv_daily PARTITION (dt=''${BIZ_DATE}'') SELECT city, vip_level, COUNT(DISTINCT user_id) AS uv, ... GROUP BY city, vip_level\""
echo "[指标] 完成，当日 GMV = 18,598,700.00"', NULL, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(14, 1, 1, '加载-写目标分区表', 'SHELL', '#!/bin/bash
# 加载：将 DWS 结果写入目标报表库
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}

echo "[加载] 开始写入目标分区表，业务日期=${BIZ_DATE}"
echo "[加载] sqoop export --connect jdbc:mysql://db-report.prd/bi_report --table rpt_gmv_daily --export-dir /warehouse/dws/dws_gmv_daily/${BIZ_DATE}/ --update-key \"dt,city,vip_level\" --update-mode allowinsert --num-mappers 4"
echo "[加载] 完成，已写入 rpt_gmv_daily 分区 dt=${BIZ_DATE}"', NULL, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(15, 1, 1, '归档-清理与统计',   'SHELL', '#!/bin/bash
# 归档：清理过期分区 + 统计链路耗时
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}
RETENTION_DAYS=30
EXPIRE_DATE=$(date -d "${BIZ_DATE} -${RETENTION_DAYS} days" +%Y%m%d)

echo "[归档] 清理 ${EXPIRE_DATE} 之前的分区"
echo "[归档] hadoop fs -rm -r -skipTrash /warehouse/ods/orders/${EXPIRE_DATE}/"
echo "[归档] hive -e \"ALTER TABLE dwd.dwd_orders_wide DROP IF EXISTS PARTITION (dt<=''${EXPIRE_DATE}'')\""

echo "[归档] 链路数据量统计："
echo "  ODS: 85,432,100 bytes"
echo "  DWD: 987,654 rows"
echo "  DWS: 156 rows"
echo "[归档] 完成"', NULL, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0);

INSERT INTO task_def_version (id, tenant_id, project_id, task_id, version_no, name, type, content, datasource_id, target_datasource_id, params_json, timeout_sec, retry_max, remark, published_by, published_at, created_at) VALUES
(10, 1, 1, 10, 1, '抽取-拉取订单分区', 'SHELL', '#!/bin/bash
# 抽取：从源库按分区拉取订单数据
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}
TABLE="orders"
PARTITION="dt=''${BIZ_DATE}''"

echo "[抽取] 开始拉取 ${TABLE} 分区 ${PARTITION}"
echo "[抽取] sqoop import --connect jdbc:mysql://db-source.prd/data_platform --table ${TABLE} --where \"${PARTITION}\" --target-dir /warehouse/ods/${TABLE}/${BIZ_DATE}/ --num-mappers 8"
echo "[抽取] 完成，共 1,234,567 行"', NULL, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00'),
(11, 1, 1, 11, 1, '清洗-去重生成宽表', 'SHELL', '#!/bin/bash
# 清洗：去重 + 关联维度表生成 DWD 宽表
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}

echo "[清洗] 开始加工 DWD 层，业务日期=${BIZ_DATE}"
echo "[清洗] hive -e \"INSERT OVERWRITE TABLE dwd.dwd_orders_wide PARTITION (dt=''${BIZ_DATE}'') SELECT o.order_id, o.user_id, o.order_amount, ... FROM ods.orders o LEFT JOIN dim.dim_user u ... QUALIFY rn = 1\""
echo "[清洗] 完成，输出 987,654 行"', NULL, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00'),
(12, 1, 1, 12, 1, '质检-行数阈值校验', 'SHELL', '#!/bin/bash
# 质检：校验 DWD 表行数在合理范围内
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}
MIN_ROWS=10000
MAX_ROWS=50000000
ACTUAL=987654

echo "[质检] 校验 DWD 行数阈值，业务日期=${BIZ_DATE}"
echo "[质检] hive -e \"SELECT COUNT(*) FROM dwd.dwd_orders_wide WHERE dt=''${BIZ_DATE}''\" -> ${ACTUAL}"

if [ "${ACTUAL}" -lt "${MIN_ROWS}" ]; then
  echo "[质检] FAILED: 行数 ${ACTUAL} < 下限 ${MIN_ROWS}，可能数据缺失"
  exit 1
fi

if [ "${ACTUAL}" -gt "${MAX_ROWS}" ]; then
  echo "[质检] FAILED: 行数 ${ACTUAL} > 上限 ${MAX_ROWS}，可能数据异常"
  exit 1
fi

echo "[质检] PASSED: 行数 ${ACTUAL} 在 [${MIN_ROWS}, ${MAX_ROWS}] 范围内"', NULL, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00'),
(13, 1, 1, 13, 1, '指标-GMV 汇总',     'SHELL', '#!/bin/bash
# 指标：按维度汇总 GMV 写入 DWS 层
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}

echo "[指标] 开始汇总 GMV，业务日期=${BIZ_DATE}"
echo "[指标] hive -e \"INSERT OVERWRITE TABLE dws.dws_gmv_daily PARTITION (dt=''${BIZ_DATE}'') SELECT city, vip_level, COUNT(DISTINCT user_id) AS uv, ... GROUP BY city, vip_level\""
echo "[指标] 完成，当日 GMV = 18,598,700.00"', NULL, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00'),
(14, 1, 1, 14, 1, '加载-写目标分区表', 'SHELL', '#!/bin/bash
# 加载：将 DWS 结果写入目标报表库
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}

echo "[加载] 开始写入目标分区表，业务日期=${BIZ_DATE}"
echo "[加载] sqoop export --connect jdbc:mysql://db-report.prd/bi_report --table rpt_gmv_daily --export-dir /warehouse/dws/dws_gmv_daily/${BIZ_DATE}/ --update-key \"dt,city,vip_level\" --update-mode allowinsert --num-mappers 4"
echo "[加载] 完成，已写入 rpt_gmv_daily 分区 dt=${BIZ_DATE}"', NULL, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00'),
(15, 1, 1, 15, 1, '归档-清理与统计',   'SHELL', '#!/bin/bash
# 归档：清理过期分区 + 统计链路耗时
set -euo pipefail

BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}
RETENTION_DAYS=30
EXPIRE_DATE=$(date -d "${BIZ_DATE} -${RETENTION_DAYS} days" +%Y%m%d)

echo "[归档] 清理 ${EXPIRE_DATE} 之前的分区"
echo "[归档] hadoop fs -rm -r -skipTrash /warehouse/ods/orders/${EXPIRE_DATE}/"
echo "[归档] hive -e \"ALTER TABLE dwd.dwd_orders_wide DROP IF EXISTS PARTITION (dt<=''${EXPIRE_DATE}'')\""

echo "[归档] 链路数据量统计："
echo "  ODS: 85,432,100 bytes"
echo "  DWD: 987,654 rows"
echo "  DWS: 156 rows"
echo "[归档] 完成"', NULL, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00');

INSERT INTO workflow_def (id, tenant_id, project_id, name, description, schedule_type, cron, schedule_start, schedule_end, status, current_version_no, has_draft_change, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(3, 1, 1, '订单 SHELL 流水线', '抽取→清洗→质检/指标并行→加载→归档，全 SHELL 节点', 'CRON', '0 30 1 * * ?', TIMESTAMP '2026-06-12 00:00:00', NULL, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0);

INSERT INTO workflow_def_version (id, tenant_id, project_id, workflow_id, version_no, name, description, schedule_type, cron, dag_snapshot_json, remark, published_by, published_at, created_at) VALUES
(3, 1, 1, 3, 1, '订单 SHELL 流水线', '抽取→清洗→质检/指标并行→加载→归档', 'CRON', '0 30 1 * * ?',
  '{"nodes":[{"nodeKey":"n1","nodeType":"TASK","taskId":10,"taskVersionNo":1},{"nodeKey":"n2","nodeType":"TASK","taskId":11,"taskVersionNo":1},{"nodeKey":"n3","nodeType":"TASK","taskId":12,"taskVersionNo":1},{"nodeKey":"n4","nodeType":"TASK","taskId":13,"taskVersionNo":1},{"nodeKey":"n5","nodeType":"TASK","taskId":14,"taskVersionNo":1},{"nodeKey":"n6","nodeType":"TASK","taskId":15,"taskVersionNo":1}],"edges":[{"fromNodeKey":"n1","toNodeKey":"n2","strength":"STRONG"},{"fromNodeKey":"n2","toNodeKey":"n3","strength":"STRONG"},{"fromNodeKey":"n2","toNodeKey":"n4","strength":"STRONG"},{"fromNodeKey":"n3","toNodeKey":"n5","strength":"STRONG"},{"fromNodeKey":"n4","toNodeKey":"n5","strength":"STRONG"},{"fromNodeKey":"n5","toNodeKey":"n6","strength":"STRONG"}]}',
  '首次发布', 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00');

INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, task_id, node_key, name, pos_x, pos_y, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(4, 1, 1, 3, 10, 'n1', '抽取-拉取订单分区', 100, 160, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(5, 1, 1, 3, 11, 'n2', '清洗-去重生成宽表', 280, 160, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(6, 1, 1, 3, 12, 'n3', '质检-行数阈值校验', 460, 100, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(7, 1, 1, 3, 13, 'n4', '指标-GMV 汇总',     460, 220, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(8, 1, 1, 3, 14, 'n5', '加载-写目标分区表', 640, 160, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(9, 1, 1, 3, 15, 'n6', '归档-清理与统计',   820, 160, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0);

INSERT INTO workflow_edge (id, tenant_id, project_id, workflow_id, from_node_id, to_node_id, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(3, 1, 1, 3, 4, 5, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(4, 1, 1, 3, 5, 6, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(5, 1, 1, 3, 5, 7, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(6, 1, 1, 3, 6, 8, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(7, 1, 1, 3, 7, 8, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0),
(8, 1, 1, 3, 8, 9, 1, 1, TIMESTAMP '2026-06-12 00:00:00', TIMESTAMP '2026-06-12 00:00:00', 0, 0);

-- 工作流实例：wi1 失败(诊断现场) / wi2 成功 / wi3 运行中(含等待)
INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, workflow_version_no, trigger_type, state, biz_date, started_at, finished_at, workflow_def_name, cron_expression, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
('01910000-0001-7000-8000-000000000001', 1, 1, 1, 1, 'CRON',   'FAILED',  '2026-06-09', TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:08:00', '每日 GMV 工作流', '0 0 2 * * ?', 1, 1, TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:08:00', 0, 0),
('01910000-0002-7000-8000-000000000002', 1, 1, 1, 1, 'MANUAL', 'SUCCESS', '2026-06-09', TIMESTAMP '2026-06-10 08:00:00', TIMESTAMP '2026-06-10 08:02:00', '每日 GMV 工作流', '0 0 2 * * ?', 1, 1, TIMESTAMP '2026-06-10 08:00:00', TIMESTAMP '2026-06-10 08:02:00', 0, 0),
('01910000-0003-7000-8000-000000000003', 1, 1, 1, 1, 'MANUAL', 'RUNNING', '2026-06-10', TIMESTAMP '2026-06-10 09:30:00', NULL,                          '每日 GMV 工作流', '0 0 2 * * ?', 1, 1, TIMESTAMP '2026-06-10 09:30:00', TIMESTAMP '2026-06-10 09:30:00', 0, 0);

-- 节点实例
-- wi1：n1 失败(OOM@node-3) → n2/n3 因上游失败被取消 STOPPED
INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, workflow_node_id, task_id, task_version_no, task_def_name, workflow_def_name, cron_expression, biz_date, run_mode, state, attempt, worker_node_code, started_at, finished_at, log, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
('01910000-0010-7000-8000-000000000001', 1, 1, '01910000-0001-7000-8000-000000000001', 1, 2, 1, '订单宽表加工', '每日 GMV 工作流', '0 0 2 * * ?', '2026-06-09', 'NORMAL', 'FAILED',  1, 'node-3', TIMESTAMP '2026-06-10 02:00:03', TIMESTAMP '2026-06-10 02:07:51', 'java.lang.OutOfMemoryError: Java heap space at executor stage 3; container killed by YARN, used 8.1GB of 8GB physical memory', 1, 1, TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:07:51', 0, 0),
('01910000-0010-7000-8000-000000000002', 1, 1, '01910000-0001-7000-8000-000000000001', 2, 1, 1, 'GMV 统计', '每日 GMV 工作流', '0 0 2 * * ?', '2026-06-09', 'NORMAL', 'STOPPED', 0, NULL,     NULL, NULL, '上游 订单宽表加工 失败，本节点被取消调度', 1, 1, TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:08:00', 0, 0),
('01910000-0010-7000-8000-000000000003', 1, 1, '01910000-0001-7000-8000-000000000001', 3, 3, 1, '用户画像聚合', '每日 GMV 工作流', '0 0 2 * * ?', '2026-06-09', 'NORMAL', 'STOPPED', 0, NULL,     NULL, NULL, '上游 订单宽表加工 失败，本节点被取消调度', 1, 1, TIMESTAMP '2026-06-10 02:00:00', TIMESTAMP '2026-06-10 02:08:00', 0, 0),
-- wi2：全部成功
('01910000-0010-7000-8000-000000000004', 1, 1, '01910000-0002-7000-8000-000000000002', 1, 2, 1, '订单宽表加工', '每日 GMV 工作流', '0 0 2 * * ?', '2026-06-09', 'NORMAL', 'SUCCESS', 1, 'node-1', TIMESTAMP '2026-06-10 08:00:05', TIMESTAMP '2026-06-10 08:00:55', '[mock] 执行成功', 1, 1, TIMESTAMP '2026-06-10 08:00:00', TIMESTAMP '2026-06-10 08:00:55', 0, 0),
('01910000-0010-7000-8000-000000000005', 1, 1, '01910000-0002-7000-8000-000000000002', 2, 1, 1, 'GMV 统计', '每日 GMV 工作流', '0 0 2 * * ?', '2026-06-09', 'NORMAL', 'SUCCESS', 1, 'node-2', TIMESTAMP '2026-06-10 08:01:00', TIMESTAMP '2026-06-10 08:01:30', '[mock] 执行成功', 1, 1, TIMESTAMP '2026-06-10 08:01:00', TIMESTAMP '2026-06-10 08:01:30', 0, 0),
('01910000-0010-7000-8000-000000000006', 1, 1, '01910000-0002-7000-8000-000000000002', 3, 3, 1, '用户画像聚合', '每日 GMV 工作流', '0 0 2 * * ?', '2026-06-09', 'NORMAL', 'SUCCESS', 1, 'node-5', TIMESTAMP '2026-06-10 08:01:00', TIMESTAMP '2026-06-10 08:01:40', '[mock] 执行成功', 1, 1, TIMESTAMP '2026-06-10 08:01:00', TIMESTAMP '2026-06-10 08:01:40', 0, 0),
-- wi3：n1 运行中 → n2/n3 等待上游
('01910000-0010-7000-8000-000000000007', 1, 1, '01910000-0003-7000-8000-000000000003', 1, 2, 1, '订单宽表加工', '每日 GMV 工作流', '0 0 2 * * ?', '2026-06-10', 'NORMAL', 'RUNNING', 1, 'node-5', TIMESTAMP '2026-06-10 09:30:05', NULL, '执行中…', 1, 1, TIMESTAMP '2026-06-10 09:30:00', TIMESTAMP '2026-06-10 09:30:05', 0, 0),
('01910000-0010-7000-8000-000000000008', 1, 1, '01910000-0003-7000-8000-000000000003', 2, 1, 1, 'GMV 统计', '每日 GMV 工作流', '0 0 2 * * ?', '2026-06-10', 'NORMAL', 'WAITING', 0, NULL,     NULL, NULL, '等待上游 订单宽表加工 完成', 1, 1, TIMESTAMP '2026-06-10 09:30:00', TIMESTAMP '2026-06-10 09:30:00', 0, 0),
('01910000-0010-7000-8000-000000000009', 1, 1, '01910000-0003-7000-8000-000000000003', 3, 3, 1, '用户画像聚合', '每日 GMV 工作流', '0 0 2 * * ?', '2026-06-10', 'NORMAL', 'WAITING', 0, NULL,     NULL, NULL, '等待上游 订单宽表加工 完成', 1, 1, TIMESTAMP '2026-06-10 09:30:00', TIMESTAMP '2026-06-10 09:30:00', 0, 0),
-- 试跑：脱离工作流、跑草稿版(task_version_no=NULL)、run_mode=TEST，不计入生产
('01910000-0010-7000-8000-00000000000a', 1, 1, NULL, NULL, 1, NULL, 'GMV 统计', NULL, NULL, NULL, 'TEST', 'SUCCESS', 1, 'node-5', TIMESTAMP '2026-06-10 11:00:00', TIMESTAMP '2026-06-10 11:00:08', '[test] 试跑成功，返回 1 行：GMV=1859.87', 1, 1, TIMESTAMP '2026-06-10 11:00:00', TIMESTAMP '2026-06-10 11:00:08', 0, 0);

-- ===== 手动任务流种子数据（schedule_type=MANUAL, status=ONLINE）=====
-- 供「手动任务流列表」Tab 展示：3 条不同规模的手动工作流，覆盖单节点 / 链式 / 菱形 DAG，
-- 字段填充完整（last_fire_time / priority / timeout_sec / has_draft_change）。

-- 手动工作流关联任务定义
INSERT INTO task_def (id, tenant_id, project_id, name, type, content, datasource_id, target_datasource_id, params_json, timeout_sec, retry_max, status, current_version_no, has_draft_change, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(20, 1, 1, '广告投放数据抽取', 'SQL', 'select campaign_id, impressions, clicks, spend from ad_platform.campaigns', 1, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-20 00:00:00', TIMESTAMP '2026-06-20 00:00:00', 0, 0),
(21, 1, 1, '异常数据检测',     'SQL', 'select id, table_name, error_type, row_count from data_quality.alerts where severity=''HIGH''', 1, NULL, NULL, 300, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00', 0, 0),
(22, 1, 1, '数据修复执行',     'SHELL', 'echo "[修复] done"', NULL, NULL, NULL, 1800, 2, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00', 0, 0),
(23, 1, 1, '修复结果验证',     'SQL', 'select table_name, case when error_count=0 then ''PASS'' else ''FAIL'' end as result from data_quality.alerts where fix_applied=1', 1, NULL, NULL, 300, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00', 0, 0),
(24, 1, 1, '收入汇总',         'SQL', 'select channel, sum(amount) as revenue from finance.income where month=:bizdate group by channel', 1, NULL, NULL, 900, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0),
(25, 1, 1, '支出汇总',         'SQL', 'select dept, sum(amount) as expense from finance.expense where month=:bizdate group by dept', 1, NULL, NULL, 900, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0),
(26, 1, 1, '对账差异计算',     'SQL', 'select i.channel, i.revenue, e.expense, (i.revenue - e.expense) as diff from revenue_sum i join expense_sum e on i.channel=e.dept', 1, NULL, NULL, 600, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0),
(27, 1, 1, '对账报告输出',     'SHELL', 'echo "[报告] done"', NULL, NULL, NULL, 1200, 1, 'ONLINE', 1, 0, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0);

-- 任务已发布版本快照
INSERT INTO task_def_version (id, tenant_id, project_id, task_id, version_no, name, type, content, datasource_id, target_datasource_id, params_json, timeout_sec, retry_max, remark, published_by, published_at, created_at) VALUES
(20, 1, 1, 20, 1, '广告投放数据抽取', 'SQL', 'select campaign_id, impressions, clicks, spend from ad_platform.campaigns', 1, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-06-20 00:00:00', TIMESTAMP '2026-06-20 00:00:00'),
(21, 1, 1, 21, 1, '异常数据检测',     'SQL', 'select id, table_name, error_type, row_count from data_quality.alerts where severity=''HIGH''', 1, NULL, NULL, 300, 1, '首次发布', 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00'),
(22, 1, 1, 22, 1, '数据修复执行',     'SHELL', 'echo "[修复] done"', NULL, NULL, NULL, 1800, 2, '首次发布', 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00'),
(23, 1, 1, 23, 1, '修复结果验证',     'SQL', 'select table_name, case when error_count=0 then ''PASS'' else ''FAIL'' end as result from data_quality.alerts where fix_applied=1', 1, NULL, NULL, 300, 1, '首次发布', 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00'),
(24, 1, 1, 24, 1, '收入汇总',         'SQL', 'select channel, sum(amount) as revenue from finance.income where month=:bizdate group by channel', 1, NULL, NULL, 900, 1, '首次发布', 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00'),
(25, 1, 1, 25, 1, '支出汇总',         'SQL', 'select dept, sum(amount) as expense from finance.expense where month=:bizdate group by dept', 1, NULL, NULL, 900, 1, '首次发布', 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00'),
(26, 1, 1, 26, 1, '对账差异计算',     'SQL', 'select i.channel, i.revenue, e.expense, (i.revenue - e.expense) as diff from revenue_sum i join expense_sum e on i.channel=e.dept', 1, NULL, NULL, 600, 1, '首次发布', 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00'),
(27, 1, 1, 27, 1, '对账报告输出',     'SHELL', 'echo "[报告] done"', NULL, NULL, NULL, 1200, 1, '首次发布', 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00');

-- 手动工作流定义（字段完整填充）
INSERT INTO workflow_def (id, tenant_id, project_id, name, description, schedule_type, cron, schedule_start, schedule_end, status, current_version_no, has_draft_change, last_fire_time, priority, preemptible, timeout_sec, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(4, 1, 1, '广告投放报表刷新', '按需拉取广告平台投放数据，刷新 BI 报表', 'MANUAL', NULL, NULL, NULL, 'ONLINE', 1, 0, TIMESTAMP '2026-06-24 14:30:00', 5, 0, 600, 1, 1, TIMESTAMP '2026-06-20 00:00:00', TIMESTAMP '2026-06-24 14:32:00', 0, 0),
(5, 1, 1, '数据质量修复管道', '检测异常 → 自动修复 → 验证结果，三步修复管道。可按需抢占低优任务。', 'MANUAL', NULL, NULL, NULL, 'ONLINE', 1, 0, NULL, 8, 1, 3600, 1, 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00', 0, 0),
(6, 1, 1, '月度财务对账',   '每月收入/支出汇总 → 差异计算 → 生成对账报告。涉及财务核心数据，最高优先级不可抢占。', 'MANUAL', NULL, NULL, NULL, 'ONLINE', 1, 1, TIMESTAMP '2026-05-31 09:00:00', 10, 0, 7200, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-06-23 00:00:00', 0, 0);

-- 工作流已发布版本快照（dag_snapshot_json 包含完整 DAG）
INSERT INTO workflow_def_version (id, tenant_id, project_id, workflow_id, version_no, name, description, schedule_type, cron, dag_snapshot_json, remark, published_by, published_at, created_at) VALUES
(4, 1, 1, 4, 1, '广告投放报表刷新', '按需拉取广告平台投放数据', 'MANUAL', NULL,
  '{"nodes":[{"nodeKey":"n1","nodeType":"TASK","taskId":20,"taskVersionNo":1,"name":"广告投放数据抽取","posX":300,"posY":160}],"edges":[]}',
  '首次发布', 1, TIMESTAMP '2026-06-20 00:00:00', TIMESTAMP '2026-06-20 00:00:00'),
(5, 1, 1, 5, 1, '数据质量修复管道', '检测→修复→验证', 'MANUAL', NULL,
  '{"nodes":[{"nodeKey":"n1","nodeType":"TASK","taskId":21,"taskVersionNo":1,"name":"异常数据检测","posX":100,"posY":160},{"nodeKey":"n2","nodeType":"TASK","taskId":22,"taskVersionNo":1,"name":"数据修复执行","posX":300,"posY":160},{"nodeKey":"n3","nodeType":"TASK","taskId":23,"taskVersionNo":1,"name":"修复结果验证","posX":500,"posY":160}],"edges":[{"fromNodeKey":"n1","toNodeKey":"n2","strength":"STRONG"},{"fromNodeKey":"n2","toNodeKey":"n3","strength":"STRONG"}]}',
  '首次发布', 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00'),
(6, 1, 1, 6, 1, '月度财务对账', '收入/支出→差异→报告', 'MANUAL', NULL,
  '{"nodes":[{"nodeKey":"n1","nodeType":"TASK","taskId":24,"taskVersionNo":1,"name":"收入汇总","posX":100,"posY":100},{"nodeKey":"n2","nodeType":"TASK","taskId":25,"taskVersionNo":1,"name":"支出汇总","posX":100,"posY":220},{"nodeKey":"n3","nodeType":"TASK","taskId":26,"taskVersionNo":1,"name":"对账差异计算","posX":320,"posY":160},{"nodeKey":"n4","nodeType":"TASK","taskId":27,"taskVersionNo":1,"name":"对账报告输出","posX":540,"posY":160}],"edges":[{"fromNodeKey":"n1","toNodeKey":"n3","strength":"STRONG"},{"fromNodeKey":"n2","toNodeKey":"n3","strength":"STRONG"},{"fromNodeKey":"n3","toNodeKey":"n4","strength":"STRONG"}]}',
  '首次发布', 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00');

-- DAG 节点
INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, task_id, node_key, name, pos_x, pos_y, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(10, 1, 1, 4, 20, 'n1', '广告投放数据抽取', 300, 160, 1, 1, TIMESTAMP '2026-06-20 00:00:00', TIMESTAMP '2026-06-20 00:00:00', 0, 0),
(11, 1, 1, 5, 21, 'n1', '异常数据检测',     100, 160, 1, 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00', 0, 0),
(12, 1, 1, 5, 22, 'n2', '数据修复执行',     300, 160, 1, 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00', 0, 0),
(13, 1, 1, 5, 23, 'n3', '修复结果验证',     500, 160, 1, 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00', 0, 0),
(14, 1, 1, 6, 24, 'n1', '收入汇总',         100, 100, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0),
(15, 1, 1, 6, 25, 'n2', '支出汇总',         100, 220, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0),
(16, 1, 1, 6, 26, 'n3', '对账差异计算',     320, 160, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0),
(17, 1, 1, 6, 27, 'n4', '对账报告输出',     540, 160, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0);

-- DAG 边（wf5 链式、wf6 菱形汇聚）
INSERT INTO workflow_edge (id, tenant_id, project_id, workflow_id, from_node_id, to_node_id, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
(9,  1, 1, 5, 11, 12, 1, 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00', 0, 0),
(10, 1, 1, 5, 12, 13, 1, 1, TIMESTAMP '2026-06-15 00:00:00', TIMESTAMP '2026-06-15 00:00:00', 0, 0),
(11, 1, 1, 6, 14, 16, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0),
(12, 1, 1, 6, 15, 16, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0),
(13, 1, 1, 6, 16, 17, 1, 1, TIMESTAMP '2026-05-15 00:00:00', TIMESTAMP '2026-05-15 00:00:00', 0, 0);

-- 手动触发工作流实例（wf4 运行 1 次成功、wf6 运行 1 次成功；wf5 尚未触发过）
INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, workflow_version_no, trigger_type, state, biz_date, started_at, finished_at, workflow_def_name, cron_expression, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
('01910000-0004-7000-8000-000000000004', 1, 1, 4, 1, 'MANUAL', 'SUCCESS', '2026-06-24', TIMESTAMP '2026-06-24 14:30:00', TIMESTAMP '2026-06-24 14:32:00', '广告投放报表刷新', NULL, 1, 1, TIMESTAMP '2026-06-24 14:30:00', TIMESTAMP '2026-06-24 14:32:00', 0, 0),
('01910000-0006-7000-8000-000000000006', 1, 1, 6, 1, 'MANUAL', 'SUCCESS', '2026-05-31', TIMESTAMP '2026-05-31 09:00:00', TIMESTAMP '2026-05-31 09:45:00', '月度财务对账', NULL, 1, 1, TIMESTAMP '2026-05-31 09:00:00', TIMESTAMP '2026-05-31 09:45:00', 0, 0);

-- 任务实例（wf4 单节点成功；wf6 四节点全部成功）
INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, workflow_node_id, task_id, task_version_no, task_def_name, workflow_def_name, cron_expression, biz_date, run_mode, state, attempt, worker_node_code, started_at, finished_at, log, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
('01910000-0040-7000-8000-000000000001', 1, 1, '01910000-0004-7000-8000-000000000004', 10, 20, 1, '广告投放数据抽取', '广告投放报表刷新', NULL, '2026-06-24', 'NORMAL', 'SUCCESS', 1, 'node-1', TIMESTAMP '2026-06-24 14:30:05', TIMESTAMP '2026-06-24 14:31:50', '[mock] 抽取广告数据 12000 行，刷新报表完成', 1, 1, TIMESTAMP '2026-06-24 14:30:00', TIMESTAMP '2026-06-24 14:31:50', 0, 0),
('01910000-0060-7000-8000-000000000001', 1, 1, '01910000-0006-7000-8000-000000000006', 14, 24, 1, '收入汇总', '月度财务对账', NULL, '2026-05-31', 'NORMAL', 'SUCCESS', 1, 'node-2', TIMESTAMP '2026-05-31 09:00:10', TIMESTAMP '2026-05-31 09:08:30', '[mock] 收入汇总完成: 5 channels, total=12,580,000', 1, 1, TIMESTAMP '2026-05-31 09:00:00', TIMESTAMP '2026-05-31 09:08:30', 0, 0),
('01910000-0060-7000-8000-000000000002', 1, 1, '01910000-0006-7000-8000-000000000006', 15, 25, 1, '支出汇总', '月度财务对账', NULL, '2026-05-31', 'NORMAL', 'SUCCESS', 1, 'node-1', TIMESTAMP '2026-05-31 09:00:10', TIMESTAMP '2026-05-31 09:07:15', '[mock] 支出汇总完成: 8 depts, total=9,320,000', 1, 1, TIMESTAMP '2026-05-31 09:00:00', TIMESTAMP '2026-05-31 09:07:15', 0, 0),
('01910000-0060-7000-8000-000000000003', 1, 1, '01910000-0006-7000-8000-000000000006', 16, 26, 1, '对账差异计算', '月度财务对账', NULL, '2026-05-31', 'NORMAL', 'SUCCESS', 1, 'node-2', TIMESTAMP '2026-05-31 09:08:35', TIMESTAMP '2026-05-31 09:12:00', '[mock] 对账差异: 3 channels 差异>5%, 需人工复核', 1, 1, TIMESTAMP '2026-05-31 09:08:35', TIMESTAMP '2026-05-31 09:12:00', 0, 0),
('01910000-0060-7000-8000-000000000004', 1, 1, '01910000-0006-7000-8000-000000000006', 17, 27, 1, '对账报告输出', '月度财务对账', NULL, '2026-05-31', 'NORMAL', 'SUCCESS', 1, 'node-5', TIMESTAMP '2026-05-31 09:12:05', TIMESTAMP '2026-05-31 09:44:50', '[mock] 对账报告 PDF 已生成: /reports/2026-05-reconciliation.pdf', 1, 1, TIMESTAMP '2026-05-31 09:12:05', TIMESTAMP '2026-05-31 09:44:50', 0, 0);

-- 一条 FAILED 实例素材（OOM@node-3，真证据：node-3 mem 95%），供 ops/run-logs 演示失败现场。
INSERT INTO task_instance (id, tenant_id, project_id, task_id, task_def_name, biz_date, run_mode, state, attempt, worker_node_code, started_at, finished_at, log, exit_code, failure_reason, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
('01910000-0010-7000-8000-00000000000b', 1, 1, 10, '抽取-拉取订单分区', NULL, 'NORMAL', 'FAILED', 1, 'node-3', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'stage 3 shuffle read 4.2GB: java.lang.OutOfMemoryError: Java heap space; container killed by YARN, used 9.4GB of 8GB physical memory', 137, 'EXIT_NONZERO', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0);

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

-- ===== 域 E · 资源与诊断 =====
-- worker_nodes 无 seed 数据：节点由 Worker 进程通过 POST /api/fleet/heartbeat 动态注册

-- ===== 域 G · 审计与 mock 业务 =====
INSERT INTO audit_log (id, tenant_id, project_id, user_id, action, target_type, target_id, detail_json, created_at) VALUES
(1, 1, 1, 1, 'CREATE', 'WORKFLOW', '1', '{"name":"每日 GMV 工作流"}', TIMESTAMP '2026-06-06 00:00:00');

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
-- 可逆例行写 MCP 工具（L1）
(30, 'TOOL', 'task_rerun',              NULL, 'L1', '重跑任务实例（可逆例行）',   1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- (31) create_task 已移除（E 子特性，定义写入一律走 project_push）
(33, 'TOOL', 'node_exec',               NULL, 'L1', '节点受控执行（按命令串解析抬升）', 1, 25, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- 调度类写工具（L1，可逆例行；distributed-scheduler-m1）。TEST 试跑是「未发布内容上 worker」唯一口子，须留痕。
(34, 'TOOL', 'test_run',                NULL, 'L1', '单任务测试运行（草稿上 worker，留痕）', 1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(35, 'TOOL', 'trigger_workflow',        NULL, 'L1', '手动触发工作流（可逆例行）',       1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(38, 'TOOL', 'run_task',                NULL, 'L1', '手动正式运行任务（NORMAL 实例，进统计）', 1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(42, 'TOOL', 'rollback_task',           NULL, 'L1', '回滚任务到历史版本（可逆，恢复草稿）', 1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(43, 'TOOL', 'rollback_workflow',       NULL, 'L1', '回滚工作流到历史版本（可逆，恢复草稿）', 1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(36, 'TOOL', 'resume_workflow',         NULL, 'L1', '断点恢复工作流（可逆例行）',       1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(37, 'TOOL', 'rerun_workflow',          NULL, 'L1', '整流重跑工作流（可逆例行）',       1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(44, 'TOOL', 'PROJECT_PUSH',             NULL, 'L1', '项目推送（纯增改，租户项目内直通+审计）', 1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(45, 'TOOL', 'PROJECT_PUSH_DESTRUCTIVE', NULL, 'L2', '项目推送含删除/force（破坏性，需审批）', 1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
-- 041 血缘人工修正（可逆治理动作：剔除=抑制展示非删数据、可撤销 → 均 L1 直通+审计；53-55）
(53, 'TOOL', 'LINEAGE_EDGE_CONFIRM',      NULL, 'L1', '血缘推断边人工确认（可撤销）',          1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(54, 'TOOL', 'LINEAGE_EDGE_REMOVE',       NULL, 'L1', '血缘推断边人工剔除（抑制展示，可撤销）', 1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
(55, 'TOOL', 'LINEAGE_CORRECTION_REVOKE', NULL, 'L1', '血缘修正裁决撤销',                    1, 20, 1, 1, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),
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
-- workflow_instance / task_instance 主键为 UUIDv7（应用层生成），无自增序列，故不 RESTART
ALTER TABLE dimensions ALTER COLUMN id RESTART WITH 100;
ALTER TABLE atomic_metrics ALTER COLUMN id RESTART WITH 100;
ALTER TABLE derived_metrics ALTER COLUMN id RESTART WITH 100;
ALTER TABLE metric_dimension ALTER COLUMN id RESTART WITH 100;
ALTER TABLE worker_nodes ALTER COLUMN id RESTART WITH 100;
ALTER TABLE audit_log ALTER COLUMN id RESTART WITH 100;
ALTER TABLE policy_rules ALTER COLUMN id RESTART WITH 100;
ALTER TABLE agent_action ALTER COLUMN id RESTART WITH 100;

-- ============================================================
-- 域 F · 表级血缘种子（table-lineage）：一条 ODS→DWD→DWS→ADS 数据流，供态势驾驶舱开屏即见
-- ============================================================
-- 血缘链对应的任务定义（task_table_io 引用的 9001/9002/9003）：补全后这条链可在补数据「下游影响范围」中被搜到并展开。
INSERT INTO task_def (id, tenant_id, project_id, name, type, content, datasource_id, status, current_version_no, has_draft_change, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
  (9001, 1, 1, '订单明细加工 ODS→DWD', 'SQL', 'insert into dwd_order select * from ods_order', 1, 'ONLINE', 1, 0, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0),
  (9002, 1, 1, '用户订单聚合 DWD→DWS', 'SQL', 'insert into dws_user_order select * from dwd_order join ods_user', 1, 'ONLINE', 1, 0, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0),
  (9003, 1, 1, 'GMV 汇总 DWS→ADS', 'SQL', 'insert into ads_gmv select sum(amount) from dws_user_order', 1, 'ONLINE', 1, 0, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0);

-- ETA 预测演示种子（task 5.4）：独立任务 9101「实时GMV增量同步」，3 条历史 SUCCESS（时长 28/30/32min，中位 30min）
-- + 1 条「此刻起跑」RUNNING（started_at=CURRENT_TIMESTAMP），供顶条「最迟看板 ETA」算出约 +30min 的真预测。
-- 不挂 workflow_instance（standalone），避免触碰其他 change 的工作流实例链。
INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, workflow_node_id, task_id, task_version_no, task_def_name, biz_date, run_mode, state, attempt, worker_node_code, started_at, finished_at, log, created_by, updated_by, created_at, updated_at, deleted, version) VALUES
('01910000-0010-7000-8000-000000009101', 1, 1, NULL, NULL, 9101, 1, '实时GMV增量同步', '2026-06-21', 'NORMAL', 'SUCCESS', 1, 'node-1', TIMESTAMP '2026-06-21 02:00:00', TIMESTAMP '2026-06-21 02:28:00', '[mock] 同步成功', 1, 1, TIMESTAMP '2026-06-21 02:00:00', TIMESTAMP '2026-06-21 02:28:00', 0, 0),
('01910000-0010-7000-8000-000000009102', 1, 1, NULL, NULL, 9101, 1, '实时GMV增量同步', '2026-06-22', 'NORMAL', 'SUCCESS', 1, 'node-1', TIMESTAMP '2026-06-22 02:00:00', TIMESTAMP '2026-06-22 02:30:00', '[mock] 同步成功', 1, 1, TIMESTAMP '2026-06-22 02:00:00', TIMESTAMP '2026-06-22 02:30:00', 0, 0),
('01910000-0010-7000-8000-000000009103', 1, 1, NULL, NULL, 9101, 1, '实时GMV增量同步', '2026-06-23', 'NORMAL', 'SUCCESS', 1, 'node-1', TIMESTAMP '2026-06-23 02:00:00', TIMESTAMP '2026-06-23 02:32:00', '[mock] 同步成功', 1, 1, TIMESTAMP '2026-06-23 02:00:00', TIMESTAMP '2026-06-23 02:32:00', 0, 0),
('01910000-0010-7000-8000-000000009104', 1, 1, NULL, NULL, 9101, 1, '实时GMV增量同步', NULL, 'NORMAL', 'RUNNING', 1, 'node-1', CURRENT_TIMESTAMP, NULL, '执行中', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0);
