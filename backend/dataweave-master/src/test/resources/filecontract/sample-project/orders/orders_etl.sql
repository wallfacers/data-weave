INSERT INTO mart_orders.daily
SELECT order_date, SUM(amount)
FROM warehouse_main.orders
WHERE order_date = '${bizDate}'
GROUP BY order_date;
