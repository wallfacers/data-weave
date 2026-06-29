-- sample-task.sql —— 示例 SQL 脚本体
-- 使用 {{placeholder}} 占位符引用参数，运行时由平台替换。
SELECT order_id, customer_name, amount
FROM orders
WHERE dt = '{{bizdate}}'
  AND amount > {{threshold}}
ORDER BY amount DESC;
