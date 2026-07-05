# Data Model: selectRunnable 优化

## 持久化层（无 schema 变更）

本 feature **不改动任何表结构**(无新列、无新表、schema_version 不变)。复用既有表:

### task_instance（认领候选 + 上游 pred）

- 认领候选:selectRunnable 查 `state='WAITING'` + `run_mode='NORMAL'/'BACKFILL'` + `deleted=0` 行(Index Scan idx_task_instance_claim)
- 上游就绪判定:batchUpstreamReady 读同 workflow_instance 内 pred 节点的 `state`(`SUCCESS`/`FAILED`)
- 状态推进:不变(casDispatchBatch 048,WAITING→DISPATCHED)

### workflow_edge（上游依赖,只读）

字段(已有,不变):`workflow_id`、`from_node_id`(上游)、`to_node_id`(下游=认领候选的 node)、`strength`(STRONG/WEAK)、`deleted`。

batchUpstreamReady 批量查:`WHERE deleted=0 AND to_node_id IN (...)`(行构造器 IN)。

### workflow_instance（认领候选查询时读）

`priority`/`trigger_type`/`workflow_id`/`state` —— selectRunnable 标量子查询读(PK Index Scan,快)。

## 进程内结构（新增,无持久化）

### Row（已 package-private,048）
复用 048 的 Row(workflowId 等字段已齐)。batchUpstreamReady 不需新字段。

### batchUpstreamReady 中间 Map
- `Map<Long toNodeId, List<Edge>>` edgesByToNode(批量查 workflow_edge 组装);Edge = (fromNodeId, strength)
- `Map<(wi, nodeId), Set<state>>` predStates(批量查 pred task_instance 组装)
- 输出 `Set<UUID readyIds>`

## 状态转换（不变）

```
WAITING ──claim(批量 CAS 048)──▶ DISPATCHED ──content 失败──▶ FAILED
                                      │
                                      └──下发失败 casRequeue──▶ WAITING
```

不变量①②③④ 全保持(research R5)。batchUpstreamReady 是 SELECT(无锁),不改状态转换。
