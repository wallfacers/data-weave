## Context

补数据(`POST /api/ops/backfill`)当前以数字 ID 为中心:`backfill-dialog.tsx` 用 `<Input type="number" placeholder="输入任务或工作流 ID">` 定位目标,用户记不住 ID;「包含下游」`Checkbox` 是死的——`BackfillService.submitBackfill()` 把 `includeDownstream` 存进 `backfill_run.include_downstream`(`:99`),但触发时 `scope` 永远硬编码 `"FULL"`(`:120`),勾选不产生任何行为差异。

已核实的既有能力(本设计的事实基础):
- **按名搜索**:`GET /api/tasks?keyword=` 与 `GET /api/workflows?keyword=` 已支持 `name LIKE %kw%` 分页查询,返回含 `name / status / ownerId / catalogNodeId` —— 选择器可直接复用,无需新搜索端点。
- **血缘**:`LineageGraphService` 是**表级**二部图,`downstream(tableId)` 返回下游**表**;`FlowEdge(fromTableId, toTableId, taskDefId)` 表示「任务 `taskDefId` 读 `fromTable`、写 `toTable`」。
- **DAG scope**:`WorkflowTriggerService.computeSubgraphKeys()` 的 `scope=DOWNSTREAM` 只在**单个 workflow 的 DAG 内**沿后继闭包遍历(`:307-330`),边全部来自该 workflow 自身定义。

## Goals / Non-Goals

**Goals:**
- 补数据目标选择从「手输数字 ID」改为「按名称搜索 + 从对象就地发起」,数字 ID 对用户全程隐形。
- 让 `includeDownstream` 真正生效:沿**血缘**(可跨 workflow)展开下游任务,并允许只补选定的下游子集,提交前看到影响范围。
- 全程复用既有数据与能力,不新增数据表。

**Non-Goals:**
- 不改补数据的核心生成模型(仍是「bizDate × 目标任务 → 一条 BACKFILL 实例 + `backfill_run` 父记录」)。
- 不实装 M2 的硬节流(同时最多 N 个 bizDate),沿用 `backfill-parallelism-throttle` 既定边界。
- 不做血缘自动纠错;下游集合以现有血缘边为准,`UNVERIFIED/CONFLICT` 边按现状呈现。
- 不引入「catalog 任务树子节点」作为下游语义——任务树是组织层级,非数据依赖(用户曾猜测「任务树的下游」,本设计明确否定:下游=血缘下游)。

## Decisions

### D1 目标选择:复用既有列表端点的可搜索选择器
弹窗目标区改为 `targetType` 切换(task/workflow) + **可搜索下拉**:输入关键词 → 防抖请求 `GET /api/tasks?keyword=&size=20`(或 `/api/workflows`)→ 候选项展示 `名称 · catalog 路径 · 负责人`,选中后内部持有 `id`。
- **为何复用而非新建搜索端点**:现有 `keyword` LIKE 查询已满足,新端点是重复造轮子。
- **「最近运行状态」的取舍**:`TaskDef.status` 是任务在线/冻结态,**不是**最近一次运行结果。候选项先展示低成本字段(名称/路径/负责人/在线态);「最近运行状态」需按候选反查最新实例,留作可选增强(见开放问题①),不阻塞核心改造。

### D2 就地补数据入口:复用 BackfillDialog 既有预填 props
`BackfillDialog` 已有 `initialTargetType / initialTargetId`(`ops-alert-card.tsx` 在用)。在三处挂入口,打开即预填、跳过搜索:
- 任务详情视图:`task` + 该 task id;
- 血缘图节点(`lineage-graph.tsx`):节点对应表的产出任务 → `task` + taskDefId;
- catalog 树节点(`catalog-tree.tsx`):任务叶子 → `task` + task id。
就地入口仍打开同一弹窗(用户可再调日期/下游),不做「一键直发」,保证闸门与影响范围确认不被跳过。

### D3 下游语义:在 BackfillService 层沿血缘展开「下游任务」,不复用 scope=DOWNSTREAM
**核心决策。** `scope=DOWNSTREAM` 是 DAG 内闭包,血缘下游是跨 workflow 的表级关系——两者粒度不匹配,不能借用。改为新增「任务级下游解析」,完全基于既有 `FlowEdge` 数据(无需新表):

```
downstreamTasks(taskDefId):
  writeTables = {e.toTableId | FlowEdge e where e.taskDefId == taskDefId}     // A 产出的表
  BFS over FlowEdges:
    frontier = writeTables
    for each FlowEdge e where e.fromTableId ∈ frontier:                        // 读了这些表的任务=下游
       collect e.taskDefId; frontier += e.toTableId                            // 沿其产出继续下钻
  return collected taskDefIds (去重, 去掉自身)
```
- 该方法放进 `LineageGraphService`(它已加载全部 nodes/edges),`BackfillService` 调用它把目标展开成 `targetTaskIds = [原目标] ∪ [选定下游]`,再对每个 (task × bizDate) 走既有 `triggerBackfillTaskRun`。
- **为何不在 SQL 里新增「读某表的任务」查询**:`FlowEdge.taskDefId` 已编码读写关系,内存 BFS 即可,避免新端点/新 SQL。

### D4 下游子集由前端选定、随请求上送
打开目标后,前端调**新增预览端点** `GET /api/ops/backfill/downstream-preview?targetType=task&targetId=`(内部走 D3 解析)返回下游任务列表(id/名称/路径/层级)。前端以**可勾选树/列表**呈现,默认**不全选**(避免误炸全图),用户勾选后:
- 请求体由布尔 `includeDownstream` 升级为 `downstreamTaskIds: number[]`(空=只补自身)。保留 `includeDownstream` 字段做向后兼容(true 且无显式集合时=全下游),前端一律传显式集合。
- **为何前端选定而非后端一刀切**:用户要的正是「看到下游是谁、只补其中某几个」,这是本次改造的价值点。

### D5 闸门携带影响面
下游展开后受影响目标数变大。`OpsController` 构造 `ActionRequest` 时新增 `param("affectedTargetCount", 1 + downstreamTaskIds.size)`,供 `PolicyEngine` 数据驱动分级(大批量补数据可经 `policy_rules` 升级到 L2 审批)。M1 不强制改判级规则,仅把信号喂进闸门。

## Risks / Trade-offs

- **血缘不全 → 下游漏算**:设计时血缘(`recordDesignTimeIo`)若缺边,下游集合不完整。→ 前端预览面板标注「依据现有血缘,可能不完整」,且默认不全选、由用户确认;不把下游展开做成隐式自动。
- **下游 BFS 在大图上的开销**:`LineageGraphService` 全量加载 nodes/edges 后内存 BFS,超大血缘图可能慢。→ 复用其既有加载(全局图本就这么算),M1 可接受;必要时按 tenant/project 维度裁剪。
- **跨 workflow 下游各自独立触发,无统一 DAG 编排**:展开后的下游任务以独立 BACKFILL 实例触发,同 bizDate 内的上下游就绪仍靠既有调度就绪门(上游 SUCCESS 才认领)自然串行——与现有工作流补数据语义一致,不引入新编排。
- **移除 downstream checkbox 的短暂功能空窗**:Phase 1 先去掉假 checkbox,Phase 2 才上真下游树。→ 同一 change 内连续交付,Phase 1 合并时下游能力同步进入或紧随,避免长期空窗。
- **「最近运行状态」未在选择器一期呈现**:可能不及用户预期。→ 文案只承诺名称/路径/负责人,状态作增强项,见开放问题①。

## Migration Plan

分两阶段,同一 change 内交付:
- **Phase 1(纯前端,核心痛点)**:目标输入框 → 可搜索选择器(D1);三处就地入口(D2);移除假 downstream checkbox。请求体暂不带下游集合(等价 includeDownstream=false)。
- **Phase 2(前后端)**:`LineageGraphService.downstreamTasks()`(D3);`/api/ops/backfill/downstream-preview` 端点 + `BackfillService` 下游展开;请求体升级 `downstreamTaskIds`(D4);前端下游可勾选树;闸门携带 `affectedTargetCount`(D5)。
- **回滚**:Phase 2 后端可单独回滚——`BackfillService` 在 `downstreamTaskIds` 为空/字段缺失时退化为「只补自身」,与 Phase 1 行为一致,前端旧请求体仍被接受。

## Open Questions

1. 选择器候选项是否一期就显示「最近运行状态」?需按候选反查最新实例(N 次或一次批量),成本 vs 价值待定。倾向:一期不做,留增强。
2. 下游预览端点是否需要分页/层级折叠?超长下游链路(几十个任务)的 UI 呈现,默认展开几层?倾向:默认展开 1 层 + 「展开更多」。
3. workflow 目标的「下游」如何定义?workflow 已是 DAG 整体,其「血缘下游」是消费该 workflow 产出表的**其它** task/workflow。M1 是否仅对 task 目标支持下游子集,workflow 目标维持整 DAG(FULL)?倾向:M1 下游子集只对 task 目标开放,workflow 目标维持现状。
