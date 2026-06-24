## Context

DataWeave 调度内核已成熟：`SchedulerKernel` 以 pull-based SKIP LOCKED 认领 + 乐观 CAS 推进状态，就绪门是一条 `NOT EXISTS(... pred.state<>'SUCCESS')` 的 SQL（`SchedulerKernel.selectRunnable`，`SchedulerKernel.java:248-267`），把「所有同周期上游 SUCCESS」作为唯一解锁条件。`workflow_instance`/`task_instance` 实例模型完备，`WorkflowTriggerService.trigger` 每次物化**全部** `workflow_node`。

库里已存在两张依赖表，但成熟度悬殊：
- `workflow_edge`（同周期 DAG 边）：只有 `from_node_id→to_node_id`，无强度/类型列；运行态完全生效。
- `workflow_dependency`（跨工作流/跨周期依赖）：**已预留** `dep_type`(默认 `ALL_SUCCESS`)、`date_offset`(`CURRENT_DAY`/`LAST_DAY`)、`enabled`——几乎是为跨周期依赖量身定做。但 `SchedulerKernel` **从不读它**，`WorkflowGraphValidator.java:57-59` 还主动抛 `workflow.graph.self_dependency` 禁止自指。即：表造好了、逻辑没接、还把最关键的自依赖入口封了。

运行方式现状：`trigger_type`（MANUAL/CRON 裸字符串）+ `run_mode`（NORMAL/TEST）。测试运行 = 单任务孤立 TEST 实例；手动 = 整图全量或单任务；无子图范围、无 bizDate 区间。`cron-scheduler` spec 仍残留「DAG engine 尚未可用、按创建顺序串行」的过时描述，落后于现网。

约束（不可违背）：死锁防御四不变量（SKIP LOCKED 认领、乐观 CAS、锁序 task→workflow、事务内只落库）、i18n 三规则、写操作经 `GatedActionService` 闸门、H2/PG 双方言、指标定义不可变（本 change 不动指标）。

## Goals / Non-Goals

**Goals:**
- 同周期弱依赖：`workflow_edge.strength`（STRONG/WEAK），就绪门按强度分叉，非破坏。
- 跨周期依赖落地：启用 `workflow_dependency`，支持自依赖 + 依赖上游上一周期，含首周期豁免（最早回溯 bizDate），放开自指禁止。
- 手动子图运行范围：`TO_NODE`（含上游闭包）/ `DOWNSTREAM`（含下游闭包），`ONLY_NODE` 复用现成孤立单任务跑、`FULL` 现状。
- 各运行方式对依赖语义的正确遵守（周期全遵守；测试/手动子图忽略跨周期、子图内遵守同周期）。
- 配置与 UI：边强/弱、运行范围、跨周期依赖配置；i18n 双语；闸门不绕过。

**Non-Goals:**
- 补数据（bizDate 区间回刷）——单独立项。
- 冒烟测试子图化——随补数据一起。
- 产出物（output name）依赖——`workflow_dependency` 工作流级依赖够用。
- 小时/分钟级跨周期（offset 非 -1 day）的完整调度周期换算——日级（`LAST_DAY` = -1 day）先落地，高级周期留 Open Question。

## Decisions

### D1. 弱依赖落 `workflow_edge.strength`，就绪门 CASE 分叉
弱依赖是**同周期边属性**（下游对「这次」上游成功与否的容忍度），加列最小侵入、语义内聚。
- 备选：新建 `edge_dependency_strength` 关联表——过度设计，边本就是 1:1 属性，否决。
- 就绪门把单一 `pred.state<>'SUCCESS'` 拆成按 `e.strength` 的 CASE：
  - `STRONG`：`pred.state<>'SUCCESS'` 阻塞（现状）。
  - `WEAK`：`pred.state NOT IN ('SUCCESS','FAILED','STOPPED')` 阻塞——即前驱到达终态即放行，不要求成功。
- 弱依赖不改 `WorkflowStateService.aggregate`：整体态仍按各节点态聚合（上游 FAILED + 下游 SUCCESS → 整体 FAILED，如实反映「有节点失败」）。

### D2. 跨周期依赖落现成 `workflow_dependency`（启用 `dep_type`/`date_offset`），自指放行
表已为此设计，启用它而非另造。
- 备选：给 `workflow_edge` 加 `cross_cycle` 标记——把同周期/跨周期两种正交语义塞进一张边，模型混乱，否决。
- **自依赖** = `workflow_dependency` 自指（`workflow_id == depend_workflow_id`，`node_id == depend_node_id`，`date_offset=LAST_DAY`）。`WorkflowGraphValidator` 放开「自指即拒绝」，改为：自指边**合法**（不构成跨节点环），仅保留跨工作流全局环检测。
- **依赖上游上一周期** = `depend_workflow_id`/`depend_node_id` 指向另一节点，`date_offset=LAST_DAY`。

### D3. 最早回溯时间（首周期豁免）落 `workflow_dependency.earliest_biz_date`
跨周期依赖的死穴：首周期没有「上一周期实例」可等，会永久 WAITING（与记忆中 H2 长跑卡 WAITING 同类坑）。
- 每条跨周期依赖配自己的回溯起点 `earliest_biz_date`：`ti.biz_date < earliest_biz_date` 的实例**跳过**上一周期检查、直接可运行；`>=` 才检查。
- 备选：`workflow_def` 级全局回溯——粒度太粗，一个工作流不同节点起点不同，否决。
- 该列可空：空 = 不启用该跨周期检查（向后兼容，旧行为）。

### D4. 跨周期就绪判定：就绪门新增一段跨周期 `EXISTS`，按 bizDate-offset 查历史实例
在现有同周期 `NOT EXISTS` 之外，NORMAL 周期实例再加一段：
```
AND NOT EXISTS (  -- 跨周期：本节点任一启用的跨周期依赖，其上一周期实例未 SUCCESS
  SELECT 1 FROM workflow_dependency d
  WHERE d.node_id = ti.workflow_node_id  -- 或本节点归属的 dependency
    AND d.enabled=1 AND ti.biz_date >= COALESCE(d.earliest_biz_date, '9999-12-31')
    AND NOT EXISTS (
      SELECT 1 FROM task_instance prev
      WHERE prev.workflow_node_id = d.depend_node_id   -- 自依赖时指向自己
        AND prev.biz_date = <ti.biz_date 按 d.date_offset 偏移>  -- LAST_DAY => -1 day
        AND prev.state='SUCCESS' AND prev.deleted=0))
```
- 仅对 `trigger_type='CRON'`（周期）实例生效；测试/手动子图实例不挂跨周期检查（见 D6）。
- 性能：`(workflow_node_id, biz_date)` 复合索引支撑上一周期实例查询（`task_instance` 已有 `idx_task_instance_claim`，需补 node+bizDate 维度索引）。
- 漏跑语义：`>= earliest` 之后若上一周期实例确实未跑，节点正确地保持 WAITING（数据依赖未满足）；解除靠后续「补数据」change 或运维手动 rerun——本 change 不提供跳过手段，避免误吞数据缺口。

### D5. 子图运行范围：trigger 按 scope 物化节点子集，闭包在内存算
`WorkflowTriggerService.trigger` 由「物化全部节点」改为接受 `scope` + `targetNodeKey`，按**已发布快照的 snapshot edges** 计算闭包：
- `FULL`：全部节点（现状，默认，兼容）。
- `TO_NODE`：target + 全部前驱闭包（跑通上游链路到本节点）。
- `DOWNSTREAM`：target + 全部后继闭包（从本节点跑到末端）。
- `ONLY_NODE`：**不建 workflow_instance**，复用现成孤立单任务跑路径（`/api/tasks/{id}/run` → `triggerManualTaskRun`，`workflow_instance_id=null`）——画布节点右键「单独运行」现状即是。
- 闭包计算在内存（DAG 发布时已保证无环），节点规模可控；物化时只为子集节点建 task_instance，子集外节点不参与。
- 子图实例仍是正规 `workflow_instance`（`trigger_type='MANUAL'`），事件流/节点变色/停止照常。

### D6. 运行方式 × 依赖语义的接缝
- **周期（CRON）**：同周期强/弱 + 跨周期全检查（D4）。
- **测试（TEST）**：孤立实例（`workflow_instance_id=null`），天然无同周期边、无跨周期——保持现状，忽略一切依赖。
- **手动 FULL/TO_NODE/DOWNSTREAM**：同周期依赖在子图内遵守（就绪门对子集内边生效）；**跨周期依赖不检查**（手动跑是即席验证，不应被「等昨天」卡住）——通过给手动实例的跨周期 `EXISTS` 段加 `trigger_type='CRON'` 前提实现。
- **手动 ONLY_NODE**：孤立实例，不检查任何依赖。

### D7. 发布快照策略：边 strength 入快照，跨周期依赖随 workflow_def 当前态生效
- 边 `strength` 是 DAG 结构属性 → 纳入 `dag_snapshot_json`（`SnapshotEdge` 增字段），随版本冻结，回滚能还原。
- 跨周期依赖（`workflow_dependency`）是**调度属性**（类似 `cron`/`schedule_type`），不纳入 DAG 快照，随 `workflow_def` 当前态生效——与现有 `schedule` 字段同生命周期，编辑即生效、不需发布才生效（与 cron 一致）。

### D8. API 形态
- `PUT /api/workflows/{id}/dag`：`DagEdge` 增 `strength`（缺省 STRONG）。
- `POST /api/workflows/{id}/run`：body 增 `{scope?: 'FULL'|'TO_NODE'|'DOWNSTREAM', targetNodeKey?: string}`（ONLY_NODE 走 `/api/tasks/{id}/run`，不在此接口）。
- 跨周期依赖 CRUD：`GET/POST/DELETE /api/workflows/{id}/dependencies`（独立资源，不混入 dag 整图写）。

## Risks / Trade-offs

- **[跨周期就绪查询性能]** 每个周期实例就绪判定多一段历史实例 EXISTS → 补 `(workflow_node_id, biz_date)` 索引；就绪门本就是 SKIP LOCKED 批量扫，增量在索引内可控。
- **[首周期豁免配置遗漏致死锁]** 用户配了自依赖但忘配 `earliest_biz_date` → 默认 `earliest` 为空=不启用（安全降级）；UI 强制配 + validate 提示。
- **[弱依赖语义被误用]** 用户以为弱依赖=下游无条件跑 → spec scenario + UI 明示「上游跑完即放行，不保证上游成功」。
- **[自指放开后的环检测]** 自指边合法后，全局跨流环检测须跳过自指边（自指不构成跨节点环）→ validator 显式排除 `workflow_id==depend_workflow_id` 的自指行。
- **[漏跑致永久 WAITING]** 周期内上一周期实例未跑 → 节点正确等待；本 change 不提供跳过手段（防误吞数据缺口），靠后续补数据或运维 rerun。
- **[scope 闭包在大图上的开销]** 闭包在内存算，DAG 已无环、节点量级可控；超大图可后续加缓存，本 change 不做。

## Migration Plan

1. Schema（PG + H2 双方言）：`workflow_edge` 加 `strength VARCHAR(16) DEFAULT 'STRONG' NOT NULL`；`workflow_dependency` 加 `earliest_biz_date DATE`（可空）；补 `task_instance(workflow_node_id, biz_date)` 索引。
2. 旧行为：所有现存边 `strength=STRONG`、所有依赖 `earliest_biz_date=null`（=不启用跨周期），就绪门等价于现状，零行为回归。
3. 代码：就绪门 SQL 升级、validator 放开自指、trigger 支持 scope、DAG 读写/快照带 strength、跨周期依赖 CRUD、UI、i18n。
4. 回滚：删新增列与索引、回退就绪门 SQL 即恢复；跨周期依赖此前无运行态数据依赖，回滚无损。

## Open Questions

- **小时/分钟级跨周期**：`date_offset=LAST_DAY` 日级先落地；小时/分钟级「上一周期」的 offset 换算（按 cron 周期推上一触发点）是否纳入本 change？倾向：列为后续，本 change 仅日级。
- **`ONLY_NODE` 是否统一进 run scope**：当前复用 `/api/tasks/{id}/run`。是否要在画布运行 Dialog 里把 ONLY_NODE 也作为 scope 选项（内部转发到单任务接口）以统一入口？倾向：是，UI 统一、后端分流。
- **最早回溯时间 UI 形态**：日期选择器（绝对 bizDate）还是「首次发布日 / N 天前」相对值？倾向：绝对日期选择器，直观可控。
