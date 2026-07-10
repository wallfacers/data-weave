-- Flink 真失败夹具：SQL 语义/语法错误（查不存在的表）
-- 作业自身错 → 非 0 退出码 + Flink 原生错误；明确区别于「缺 FLINK_HOME → 已跳过」

SET 'execution.runtime-mode' = 'batch';

-- 查不存在的表 → 解析/验证阶段失败
SELECT * FROM nonexistent_table_061_fail_test;
