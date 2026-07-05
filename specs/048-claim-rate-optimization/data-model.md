# Data Model: claim 速率深优化

## 持久化层（无 schema 变更）

本 feature **不改动任何表结构**(无新列、无新表、schema_version 不变)。复用既有表:

### task_instance（认领对象）

认领相关字段(已有,不变):
- `id` UUID PK
- `state` VARCHAR — 认领推进 `WAITING → DISPATCHED`
- `worker_node_code` VARCHAR — 认领写入目标 worker
- `lease_expire_at` TIMESTAMP — 认领写入租约
- `attempt` INT — 认领递增
- `updated_at` TIMESTAMP — 认领更新
- `deleted` INT — 软删标志(WHERE 条件)

**批量 casDispatch**(R2):单条 `UPDATE FROM VALUES` 改 `state/worker_node_code/lease_expire_at/attempt/updated_at`,`WHERE task_instance.id=v.id AND state='WAITING' AND deleted=0`。

### workflow_dependency（跨周期依赖,只读）

字段(已有,不变):
- `workflow_id`、`node_id` — 定位本节点
- `depend_node_id` — 上游依赖节点
- `date_offset` (LAST_DAY/CURRENT_DAY) — 周期偏移
- `earliest_biz_date` — 回溯起点(非空=启用,首周期豁免)
- `enabled`、`deleted`

**批量查依赖**(R3):`WHERE (workflow_id, node_id) IN ((?,?),…) AND enabled=1 AND deleted=0 AND earliest_biz_date IS NOT NULL`(行构造器 IN,H2 T2 实测兼容)。

## 进程内结构（新增/扩展,无持久化）

### Row（扩展,SchedulerKernel 内）
新增 `workflowId` 字段(`selectRunnable` NORMAL SQL 标量子查询 `(SELECT wi.workflow_id FROM workflow_instance wi WHERE wi.id=ti.workflow_instance_id) AS wfid` 填入)。其余字段不变(id/workflowInstanceId/workflowNodeId/taskId/taskVersionNo/contentOverride/paramsOverride/attempt/runMode/bizDate/waitingSince/priority/timeoutSec/taskType/datasourceId/locale/workflowTrigger)。

### Placement（新 record）
`(UUID id, String workerNodeCode, LocalDateTime leaseExpireAt, int attempt)` — assign 阶段 1 place 收集,阶段 2 批量 cas 的输入。

### CrossCycleBatchResult（新）
`Set<UUID> readyIds` — `batchCrossCycleReady` 输出,`claimAndMark` 用它 filter normals(替换单行 `crossCycleReady`)。内部中间 Map:
- `Map<(workflowId,nodeId), List<DepRow>>` depsByNode
- `Map<(dependNodeId, prevBizDate), Integer>` successCount

## 状态转换（语义不变）

```
WAITING ──claim(批量 CAS)──▶ DISPATCHED ──content 失败──▶ FAILED
   ▲                            │
   │                            └──下发失败 casRequeue(046 不变)──▶ WAITING
   └──(循环)
```

- `WAITING → DISPATCHED`:claim 批量 CAS(本 feature,单 SQL 多行)
- `DISPATCHED → FAILED`:content 占位符解析失败,逐个 `casFailed`(保留现状 `:211-214`)
- `DISPATCHED → WAITING`:下发失败 `casRequeue`(046,不变)
- `DISPATCHED → SUCCESS/FAILED/STOPPED`:worker 回报终态(不变)

不变量①②③④ 全保持(research R6)。
