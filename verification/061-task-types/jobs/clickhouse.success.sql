-- 061 ClickHouse success 夹具：DDL + DML(影响行数) + SHOW TABLES(结果集) + SELECT(结果集) → 验 FR-008/SC-004
CREATE TABLE IF NOT EXISTS dwverify.t_orders (id UInt32, amount Decimal(10,2), city String) ENGINE = MergeTree ORDER BY id;
INSERT INTO dwverify.t_orders VALUES (1, 100.50, 'Beijing'), (2, 200.00, 'Shanghai'), (3, 50.25, 'Beijing');
SHOW TABLES FROM dwverify;
SELECT city, count(*) AS cnt, sum(amount) AS total FROM dwverify.t_orders GROUP BY city ORDER BY city;
