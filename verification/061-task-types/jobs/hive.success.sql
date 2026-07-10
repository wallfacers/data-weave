CREATE DATABASE IF NOT EXISTS dwverify;
CREATE TABLE IF NOT EXISTS dwverify.t_orders (id INT, amount DECIMAL(10,2), city STRING) PARTITIONED BY (dt STRING);
INSERT INTO dwverify.t_orders PARTITION (dt='2026-07-10') VALUES (1,100.50,'Beijing'),(2,200.00,'Shanghai');
INSERT INTO dwverify.t_orders PARTITION (dt='2026-07-11') VALUES (3,50.25,'Beijing');
SHOW PARTITIONS dwverify.t_orders;
SELECT city, count(*) AS cnt, sum(amount) AS total FROM dwverify.t_orders GROUP BY city ORDER BY city;
