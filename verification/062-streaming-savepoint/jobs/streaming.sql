-- 062 savepoint 端到端夹具：无界 datagen(random) → blackhole
-- 用 random 源（非 sequence）—— sequence 源在 checkpoint/savepoint 时会物化整段序列状态导致 OOM；
-- random 源几乎无 checkpoint 状态，savepoint 快照极小可靠。stop-with-savepoint 对此作业成立。
-- 由 verify-savepoint-e2e.sh 经服务端 stop-with-savepoint 停止（非 cancel），核验 location + 检查点续跑。

SET 'execution.runtime-mode' = 'streaming';

CREATE TEMPORARY TABLE endless_source (
    id BIGINT,
    ts TIMESTAMP_LTZ(3),
    msg VARCHAR
) WITH (
    'connector' = 'datagen',
    'rows-per-second' = '1',
    'fields.id.kind' = 'random',
    'fields.id.min' = '1',
    'fields.id.max' = '1000000',
    'fields.msg.length' = '8'
);

CREATE TEMPORARY TABLE blackhole_sink (
    id BIGINT,
    ts TIMESTAMP_LTZ(3),
    msg VARCHAR
) WITH (
    'connector' = 'blackhole'
);

INSERT INTO blackhole_sink SELECT * FROM endless_source;
