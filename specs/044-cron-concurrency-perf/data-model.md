# Data Model: 观测涉及的既有实体

> 本 feature **不新建实体**,仅观测既有表用于测试核对。列出的字段是测试关注的子集(非完整 schema,完整定义见 `backend/dataweave-api/src/main/resources/schema.sql`)。

## WorkflowDef(`workflow_def` 表)

| 字段 | 测试用途 |
|---|---|
| `id`, `name` | 标识本次压测建的 wf(RUN_TAG 前缀) |
| `schedule_type` | = `CRON`(纳入扫描的前提) |
| `cron` | = `*/10 * * * * *`(6 字段秒级) |
| `status` | 必须 `ONLINE` 才被扫描;publish 后生效 |
| `next_trigger_time` | 调度器回填;观测扫描是否预读 |

**测试操作**:批量建 N 个 `schedule_type=CRON + cron='*/10 * * * * *' + publish→ONLINE`。

## WorkflowInstance(`workflow_instance` 表)

| 字段 | 测试用途 |
|---|---|
| `id`(UUID) | 实例标识 |
| `workflow_id` | 关联 wf |
| `trigger_type` | **`CRON` vs `MANUAL`**(正确性核对的核心) |
| `state` | RUNNING / SUCCESS / FAILED 分布 |
| `scheduled_fire_time` | 到期点;与 `started_at` 差 = 触发延迟 |
| `started_at` | 实例创建时间 |

**核对**:
- `trigger_type=CRON` 实例数 ≈ wf 数 × 触发点数(正确性,SC-001)
- `scheduled_fire_time → started_at` 延迟 p99(SC-003)
- 同 `workflow_id + scheduled_fire_time` 唯一(去重,SC-002)

## cron_fire(触发去重表)

| 字段 | 测试用途 |
|---|---|
| `workflow_id` | 关联 wf |
| `scheduled_fire_time` | 到期点 |
| UNIQUE(workflow_id, scheduled_fire_time) | 全局去重(多 master 零协调) |

**核对**:
- 行数 = 实际触发点数(每个 wf × 每个到期点 1 行)
- 双 master 撞键放弃数 = master-1 触发尝试 + master-2 触发尝试 − 唯一行数(SC-002 证据)

## TaskInstance(`task_instance` 表)

| 字段 | 测试用途 |
|---|---|
| `workflow_instance_id` | 关联实例 |
| `state` | SUCCESS 比例(ECHO 应回显即成功) |
| `worker_node_code` | worker-1 / worker-2 分布(执行端均衡) |

**核对**:ECHO 任务 SUCCESS 率应 ≈100%;worker 分布观测执行端是否均衡。
