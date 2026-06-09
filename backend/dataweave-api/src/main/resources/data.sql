-- 种子数据。

-- mock 业务数据：orders（不同金额）
INSERT INTO orders (id, order_amount, created_at) VALUES (1, 120.50, TIMESTAMP '2026-06-01 09:12:00');
INSERT INTO orders (id, order_amount, created_at) VALUES (2, 89.00,  TIMESTAMP '2026-06-01 10:30:00');
INSERT INTO orders (id, order_amount, created_at) VALUES (3, 256.75, TIMESTAMP '2026-06-02 14:05:00');
INSERT INTO orders (id, order_amount, created_at) VALUES (4, 42.10,  TIMESTAMP '2026-06-02 16:48:00');
INSERT INTO orders (id, order_amount, created_at) VALUES (5, 999.99, TIMESTAMP '2026-06-03 08:00:00');
INSERT INTO orders (id, order_amount, created_at) VALUES (6, 18.20,  TIMESTAMP '2026-06-03 11:22:00');
INSERT INTO orders (id, order_amount, created_at) VALUES (7, 333.33, TIMESTAMP '2026-06-04 19:40:00');

-- GMV 指标（version=1，口径不可篡改）
INSERT INTO metrics (id, name, expr_sql, source_table, dimensions, owner, version, created_at)
VALUES (1, 'GMV', 'sum(order_amount)', 'orders', NULL, 'data-team', 1, TIMESTAMP '2026-06-01 00:00:00');

-- 血缘：GMV -> 物理表 orders
INSERT INTO metric_lineage (id, metric_id, downstream_type, downstream_id)
VALUES (1, 1, 'TABLE', 'orders');

-- 种子任务：每日 GMV 统计
INSERT INTO tasks (id, name, type, content, cron, status, depends_on, created_at)
VALUES (1, '每日 GMV 统计', 'SQL', 'select sum(order_amount) from orders', '0 0 8 * * ?', 'ONLINE', NULL, TIMESTAMP '2026-06-01 00:00:00');

-- 基础用户
INSERT INTO users (id, username, email, role) VALUES (1, 'admin', 'admin@dataweave.local', 'ADMIN');

-- 种子用了显式 id，把自增起点推到已用 id 之后，避免后续插入主键冲突。
-- 该语法 H2 与 PostgreSQL 均兼容。
ALTER TABLE tasks ALTER COLUMN id RESTART WITH 100;
ALTER TABLE task_instances ALTER COLUMN id RESTART WITH 100;
ALTER TABLE metrics ALTER COLUMN id RESTART WITH 100;
ALTER TABLE metric_lineage ALTER COLUMN id RESTART WITH 100;
ALTER TABLE users ALTER COLUMN id RESTART WITH 100;
