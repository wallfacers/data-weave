-- 061 ClickHouse fail 夹具：查不存在的表（作业自身错，非缺引擎）→ 真失败 + 退出码透传
SELECT * FROM dwverify.no_such_table_xyz;
