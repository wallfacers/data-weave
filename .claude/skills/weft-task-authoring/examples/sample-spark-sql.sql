-- sample-spark-sql.sql —— Spark SQL 脚本体（与 sample-spark-sql.task.yaml 同目录）
-- 朴素分号切分，逐句 spark.sql(stmt) 执行；任一句异常 → 非0退出。
-- 未装 Spark 的环境 dw run 呈现 SKIPPED（不报错、不伪装成功）。

SELECT 1 AS test;

-- 真实场景示例（替换为实际表名）：
-- SELECT count(*) FROM orders;
-- SELECT dt, count(*) FROM orders GROUP BY dt ORDER BY dt;
