-- Flink 无界流式夹具：datagen → blackhole（long_running=true，SC-005 核心）
-- detached 提交 flink run -d → JobID 解析 → handle 回写 → REST 轮询至 cancel 终态
-- 注意：此作业不会自动结束，由 verify-flink-longrunning.sh 在 reattach 验证后 flink cancel 收尾

SET 'execution.runtime-mode' = 'streaming';

-- 无界 datagen 源（rows-per-second=1，永不停止）
CREATE TEMPORARY TABLE endless_source (
    id BIGINT,
    ts TIMESTAMP_LTZ(3),
    msg VARCHAR
) WITH (
    'connector' = 'datagen',
    'rows-per-second' = '1',
    'fields.id.kind' = 'sequence',
    'fields.id.start' = '1',
    'fields.id.end' = '999999'
);

-- blackhole sink（收到即丢弃，最小开销）
CREATE TEMPORARY TABLE blackhole_sink (
    id BIGINT,
    ts TIMESTAMP_LTZ(3),
    msg VARCHAR
) WITH (
    'connector' = 'blackhole'
);

INSERT INTO blackhole_sink SELECT * FROM endless_source;
