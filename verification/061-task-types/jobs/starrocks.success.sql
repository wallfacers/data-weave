CREATE DATABASE IF NOT EXISTS dwverify;
CREATE TABLE IF NOT EXISTS dwverify.t_orders (id INT, amount DECIMAL(10,2), city VARCHAR(64))
  DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES("replication_num"="1");
INSERT INTO dwverify.t_orders VALUES (1,100.50,'Beijing'),(2,200.00,'Shanghai'),(3,50.25,'Beijing');
SHOW TABLES FROM dwverify;
SELECT city, count(*) AS cnt, sum(amount) AS total FROM dwverify.t_orders GROUP BY city ORDER BY city;
