## Context

运营中心(`ops` view + `OpsController`/`OpsService`)由先前的 `data-ops-center` 变更落成,形态对标标准大数据中台,但与平台真实数据模型有几处错位:

- `OpsService.tasks()` = `taskDefRepository.findAll()`,无任何状态过滤,把开发态 `DRAFT` 草稿端进运维侧 → 违反「开发/运维隔离、没发布的不展示」。
- 「周期任务列表」按 `TaskDef` 列对象,但平台周期性在 `WorkflowDef.scheduleType=CRON` 上,`TaskDef` 无任何调度字段 → 对象选错。
- 「手动·测试」Tab 把「调度类型=MANUAL 的任务流(运维对象)」与「DRAFT 的 TEST_RUN(开发态自测)」两件正交的事钉成一个抽屉,几乎是空占位。
- 任务级 `freeze`(`/api/ops/tasks/{id}/freeze` + `task_def.frozen`)粒度错,运维实际需要的是「在 DAG 里冻住任意节点」。

平台既有不可变约束(本变更 MUST 不破坏):工作流发布即冻结 `dag_snapshot_json` 为运行期唯一真相源(`workflow-version-binding`);ONLINE 工作流禁删、删除先下线;所有写经 `GatedActionService` 闸门留痕;调度死锁防御不变式(SKIP LOCKED 认领、乐观 CAS、固定锁序、事务内只落状态)。

## Goals / Non-Goals

**Goals:**
- 运营中心校正为「已发布(ONLINE)对象的运行时投影」,以**周期任务流**为运维主体,任务/任务流概念彻底分离。
- 运维查询一律按 `status=ONLINE` 过滤,草稿与 TEST_RUN 不进运维。
- 「手动·测试」拆为「手动任务流列表」(ONLINE & MANUAL)+ 测试逐出 + 手动触发动作化。
- 冻结迁到节点级 DAG overlay(不污染发布快照),级联跳过语义明确。
- 收口引用完整性:发布工作流校验所有 TASK 节点引用 ONLINE;被 ONLINE 工作流引用的任务禁止下线。

**Non-Goals:**
- 不改发布快照机制本身(`dag_snapshot_json` 仍是运行真相源)。
- 不让独立 `TaskDef` 具备周期调度能力(周期性仍只在 workflow 上)。
- 不重写调度内核;只在物化阶段叠加节点冻结 overlay。
- 不改开发态画布的连线/版本/运行范围等既有能力(仅加未发布标记与发布拦截反馈)。

## Decisions

### D1: 运维查询统一 ONLINE 过滤,任务不作为独立运维对象
`OpsService` 的任务流列表查询加 `status=ONLINE` 过滤;按 `schedule_type` 分流为「周期任务流列表(CRON)」与「手动任务流列表(MANUAL)」两个查询。实例/补数据查询按 `runMode` 区分来源,且 `TEST_RUN` 在运维侧一律排除。原 `/api/ops/tasks`(全量 TaskDef)端点废弃或改为「仅作为任务流节点的只读引用」,不再作为运维一等对象。
- **备选**: 在前端过滤草稿。否决——口径必须在后端收口,否则任何调用方都能端出草稿,违反隔离原则。

### D2: 节点冻结存独立 overlay 表,不进发布快照
新增运维侧表(暂名 `workflow_node_freeze`),键 `(workflow_id, node_key)` + `frozen` + 审计列。调度器在从 `dag_snapshot_json` 物化运行实例时,左叠加该 overlay,把冻结节点标 `SKIPPED` 并级联。
- **为什么不写进快照**: 快照「发布即不可变」是运行真相源的根基;冻结是临时运维管控,若写快照就逼人重新发布,且破坏版本回滚语义。overlay 与快照正交,互不污染。
- **备选**: 复用 `task_def.frozen`。否决——粒度错(任务级 vs 节点级),且同一任务被多工作流多节点引用时无法分别冻结。

### D3: 级联跳过沿强依赖边传播
物化时按快照 DAG 的边做拓扑:冻结节点 N → N `SKIPPED`,沿**强依赖**出边可达的后继闭包因依赖未满足一并 `SKIPPED`。这与现有「上游未成功则下游不满足依赖」的调度语义同构,实现上等价于把冻结节点视作「永不成功」。

### D4: 引用完整性收口在两道既有闸门
- **发布侧**(Q1): `WorkflowService.publish()` 在无环校验旁加一条——遍历 TASK 节点,任一引用任务 `status!=ONLINE` 即拒绝(`workflow.node_task_not_online`)。开发态画布保持宽松(允许拖 DRAFT + 视觉标记),完整性只在「晋级生产」这一步卡。
- **下线侧**(Q2): `TaskService.offline()` 前置校验——查 `WorkflowNode` 中 `task_id=该任务` 且所属 `workflow_def.status=ONLINE` 的引用,存在即拒绝(`task.referenced_by_online_workflow`),对称「ONLINE 工作流禁删」。
- **为什么放这两处**: 与现有 publish 无环校验、ONLINE 禁删是同一套心智,复用闸门与审计,无新增旁路。

### D5: 「手动」拆成对象与动作两个正交概念
- **手动任务流列表** = 按「定义 `schedule_type=MANUAL`」列的对象清单(运维一等对象,有「运行一次」)。
- **手动触发** = 任意 ONLINE 工作流(含 CRON)被人点出来的实例,在「任务流实例」视图按 `runMode` 筛,是动作不是 Tab。
两者都叫「手动」但口径不同,UI 上严格分开,避免回到「手动·测试」那种糊抽屉。

## Risks / Trade-offs

- **依赖 `data-ops-center` 未归档** → 本变更的 `ops-center-view`/`task-freeze` 是对其能力的 MODIFIED/REMOVED 增量;归档前这些 capability 不在 `openspec/specs/` 基线里。Mitigation: 归档顺序上先归档 `data-ops-center` 再归档本变更(见开放问题),或在 apply 阶段确认基线已落。
- **`task_def.frozen` 列退役** → 历史可能已有 `frozen=true` 的任务。Mitigation: 迁移脚本将其语义迁到对应工作流节点的 overlay,或明确弃用并文档化(取决于线上是否已用 freeze)。
- **同一任务被多工作流引用,下线被禁** → 用户可能困惑「为什么下不了线」。Mitigation: 错误信息列出全部引用工作流,前端下线按钮在被引用时禁用并提示路径。
- **级联跳过遇弱依赖边语义未定** → 见开放问题;实现前必须拍定,否则「冻一个节点连带跳哪些」不确定。Mitigation: 默认弱依赖阻断传播,作为可讨论项。
- **overlay 与物化的并发** → 冻结写入与调度物化读 overlay 的时序。Mitigation: 物化在事务内一次性读 overlay 快照,冻结作用于「下一次物化」,在途实例不追溯(除非实例级作用域,见开放问题)。

## Migration Plan

1. 先确认/归档 `data-ops-center`,使其 capability 进基线(见开放问题)。
2. 后端: 新增 `workflow_node_freeze` 表 DDL(PG + H2 兼容);`OpsService` 加 ONLINE 过滤与 CRON/MANUAL 分流查询;`WorkflowService.publish()` 加 TASK 节点 ONLINE 校验;`TaskService.offline()` 加引用校验;调度物化叠加 overlay + 级联。
3. 退役 `POST /api/ops/tasks/{id}/freeze` 与 `task_def.frozen` 调度门;新增节点冻结端点。
4. 前端: `ops-view` tab 重排;`periodic-tasks-panel`→`periodic-workflows-panel`;新增 manual-workflows 面板;移除 `manual-tests-panel`;DAG 实例视图加节点冻结/解冻入口与级联可视化;画布未发布节点标记 + 发布拦截反馈;下线按钮被引用时禁用。
5. i18n: 新增/更名 copy 与错误码 `workflow.node_task_not_online`、`task.referenced_by_online_workflow` 双语齐全。
6. 回滚: 各项以 overlay/校验为主,可独立开关;DDL 加表不删旧列(frozen 列保留以便回滚),前端 tab 改名为纯展示层可快速回退。

## Resolved Decisions（apply 阶段拍定）

- **冻结作用域 = 定义级 + 实例级都要**：overlay 表 `workflow_node_freeze` 键带可空 `instance_id`（NULL=定义级、非空=实例级）；物化某实例时叠加「该工作流定义级冻结 ∪ 该实例实例级冻结」。周期任务流列表入口偏定义级，DAG 实例视图点节点偏实例级。
- **冻结级联穿透弱依赖**：冻结优先于依赖强弱，级联跳过沿出边闭包（含弱依赖边）传播，下游一律 `SKIPPED`。区别于常态调度弱依赖"上游跑完即放行"。
- **常态弱依赖就绪修正**（核实既有实现发现的 bug）：`SchedulerKernel.selectRunnable` 弱依赖就绪集从 `('SUCCESS','FAILED','STOPPED')` 改为 `('SUCCESS','FAILED')`——手动停止(`STOPPED`/MANUAL_STOP)不算"跑完"，不放行弱依赖下游（下游停 `WAITING`，不自动 `SKIPPED`）。手动置成功落 `SUCCESS`、手动停止落 `STOPPED`（已核 `OpsService`）。
- **`task_def.frozen` 直接弃用**：`schema.sql` 有 DDL 但 seed/`data.sql` 无任何 `frozen=1`，无历史数据，直接退役调度门、保留列以便回滚，不需迁移脚本。

## Open Questions

1. **`data-ops-center` 归档顺序** — 本变更增量基于其能力,需确认先归档它再归档本变更,或在 apply 时基线已就绪。（不阻塞写代码,仅影响本变更 archive。）
2. **被手动停止上游的弱依赖下游是否标 SKIPPED?** 当前决定:停 `WAITING` 不自动 `SKIPPED`,避免误伤。是否需要后续兜底清理悬挂 `WAITING` 为可选增强。
