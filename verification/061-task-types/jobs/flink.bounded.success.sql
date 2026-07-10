-- Flink 有界成功夹具：batch datagen source → print sink
-- 执行模式：batch（有界），子进程 sql-client.sh -f，阻塞 waitFor 至 FINISHED
-- 退出码 0 即真跑成功；覆盖 FR-006 原生 stdout 透出

SET 'sql-client.execution.result-mode' = 'TABLEAU';
SET 'execution.runtime-mode' = 'batch';

-- datagen 源表（有界：10 行）
CREATE TEMPORARY TABLE datagen_source (
    id BIGINT,
    data VARCHAR
) WITH (
    'connector' = 'datagen',
    'number-of-rows' = '10'
);

-- print sink 到 TM stdout
CREATE TEMPORARY TABLE print_sink (
    id BIGINT,
    data VARCHAR
) WITH (
    'connector' = 'print'
);

-- 提交 INSERT（batch mode，sql-client -f 同步等待完成）
INSERT INTO print_sink SELECT id, CONCAT('row-', CAST(id AS VARCHAR)) AS data FROM datagen_source;
