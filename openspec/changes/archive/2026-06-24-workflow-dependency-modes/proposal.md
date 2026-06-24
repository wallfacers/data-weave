## Why

DataWeave 的工作流当前只有一种依赖语义：同周期、强依赖（下游等所有上游 `SUCCESS` 才解锁，硬编码在 `SchedulerKernel` 就绪门 `pred.state<>'SUCCESS'`），且每次运行只能整图全量跑、手动/测试运行无法选择节点范围。对照 DataWorks，这缺了两类关键能力：

1. **跨周期依赖**——库里 `workflow_dependency` 表已预留 `dep_type`/`date_offset` 字段，但运行态从不读它，`WorkflowGraphValidator` 甚至主动禁止自依赖。小时/分钟级增量、日级累加这类「本周期依赖上一周期产出」的场景完全无法表达，且因首周期永远等不到上一周期会死锁 WAITING。
2. **弱依赖**（同周期，下游只等上游跑完而非成功）与 **子图运行范围**（运行到本节点 / 运行下游 / 单独运行）——通知/监控类任务不应被上游失败阻塞；手动验证一个节点时不该被迫整图重跑。

现在补齐，是因为调度内核（`SchedulerKernel` CAS 就绪门、`workflow_instance/task_instance` 实例模型）与运行/编辑态（`WorkflowTriggerService`、`workflow-canvas` 画布）都已成熟，落地所需的表字段与接缝已存在，是性价比最高的时机。

## What Changes

### In Scope

- **同周期弱依赖**：`workflow_edge` 新增 `strength`（`STRONG` 默认 / `WEAK`）。就绪门按边强度判定：`STRONG` 边等前驱 `SUCCESS`；`WEAK` 边等前驱到达终态（`SUCCESS/FAILED/STOPPED`）即放行。弱依赖不改变工作流整体成败聚合（下游照常跑，整体态仍由各节点态聚合）。
- **跨周期依赖（启用现成表）**：激活 `workflow_dependency`，支持两种形态：
  - **自依赖**：本节点本周期实例 ← 本节点上一周期实例（`depend_workflow_id` = 自身、`date_offset=LAST_DAY`），放开 `WorkflowGraphValidator` 对自指的禁止。
  - **依赖上一周期（上游）**：本节点本周期 ← 指定上游节点上一周期实例。
  - **最早回溯时间（首周期豁免）**：自依赖/跨周期依赖 MUST 配一个回溯起点 bizDate，该 bizDate 及之前的实例不检查上一周期、直接可运行，否则首周期永远 WAITING 死锁。
- **手动运行子图范围**：`POST /api/workflows/{id}/run` 与节点右键运行新增 `scope` 参数（`FULL` 默认 / `TO_NODE` 含上游 / `DOWNSTREAM` 含下游 / `ONLY_NODE` 脱离依赖单跑）。`WorkflowTriggerService.trigger` 由「物化全部节点」改为「按 scope 物化节点子集」，`ONLY_NODE`/子集内不在子图的节点不生成 task_instance。
- **运行方式 × 依赖语义对齐**：
  - 周期运行：全遵守（同周期强/弱 + 跨周期）。
  - 测试运行（`run_mode=TEST`）：忽略跨周期依赖（沿用现状孤立实例），同周期依赖按子图。
  - 手动-单独运行 / 运行到本节点 / 运行下游：忽略跨周期依赖；同周期依赖在子图内遵守。
- **配置与 UI**：DAG 读写与发布快照携带边 `strength`；`workflow-canvas` 边右键菜单可设强/弱；节点右键菜单与运行入口支持选择运行范围；配置面板可配跨周期依赖（自依赖/上游上一周期 + 最早回溯）。
- **i18n / 闸门**：新增前端 UI copy 双语（zh-CN/en-US）；手动运行范围属写操作，沿用 `GatedActionService` 闸门与 `agent_action` 审计，不新增绕过路径。

### Out of Scope（留作后续 change）

- **补数据（bizDate 日期区间回刷）**：当前 `biz_date` 单 scalar，区间循环触发与排程整合体量大，单独立项。
- **冒烟测试子图化**：当前 `task-test-run` 仅单任务试跑，「本节点及下游」子图冒烟留待补数据一起做。
- **产出物（output name）依赖**：DataWorks 跨工作流依赖的基石，但 `workflow_dependency` 的 workflow 级依赖已够用，避免 over-engineering。

## Capabilities

### New Capabilities

- `cross-cycle-dependency`: 跨周期依赖语义——自依赖、依赖上游上一周期、最早回溯时间（首周期豁免）、运行态按 `date_offset` 查询上一周期实例判定就绪，以及各运行方式（测试/周期/手动）对跨周期依赖的遵守规则。

### Modified Capabilities

- `scheduler-core`: 就绪门由「全前驱 `SUCCESS`」升级为「按边 `strength`（STRONG/WEAK）判定 + 跨周期依赖（查上一周期实例 `SUCCESS`）」；修正「虚拟节点」requirement 里隐含的就绪门描述。
- `manual-run-trigger`: 新增手动运行 `scope`（FULL/TO_NODE/DOWNSTREAM/ONLY_NODE）与节点子集物化；`ONLY_NODE` 脱离依赖。
- `workflow-authoring`: DAG 整图读写与发布快照携带边 `strength`；新增跨周期依赖（`workflow_dependency`）的配置读写；放开自依赖禁止。
- `workflow-canvas`: 边右键设强/弱依赖；节点右键与运行入口选择运行范围；配置面板配跨周期依赖与最早回溯。
- `cron-scheduler`: 周期触发的实例遵守跨周期依赖（自依赖时实例就绪需等上一周期 SUCCESS），首周期豁免；修正过时的「DAG engine 尚未可用、按创建顺序串行执行」描述以反映现网 `SchedulerKernel` DAG 就绪门。

## Impact

**Schema（`schema.sql` + H2/PG 双方言）**
- `workflow_edge` 增 `strength VARCHAR(16) DEFAULT 'STRONG'`（非破坏，旧数据全 STRONG，行为不变）。
- `workflow_dependency`：`date_offset`（已有 `CURRENT_DAY/LAST_DAY`）扩展为支持 `LAST_DAY` 语义并落地运行态；新增「最早回溯 bizDate」落点（`workflow_def` 或 `workflow_dependency` 列，design 定）。
- 启用 `schedule_type='DEPENDENCY'` 死值或保持其语义在本 change 内不强制（跨周期依赖挂在 CRON 节点上，不依赖该值）。

**后端（`dataweave-master` / `dataweave-api`）**
- `SchedulerKernel.selectRunnable` 就绪门 SQL 重写（弱依赖分叉 + 跨周期历史实例 EXISTS 查询）。
- `WorkflowGraphValidator`：放开自依赖（自指 `workflow_dependency` 合法），保留全局跨流环检测。
- `WorkflowTriggerService.trigger`：支持 `scope` → 节点子集物化（TO_NODE/DOWNSTREAM 需 DAG 前驱/后继闭包计算）。
- `WorkflowController` / `TaskController` run 接口增 `scope` + `targetNodeKey`。
- `WorkflowService`：DAG 整图读写与 `dag_snapshot_json` 携带边 `strength`；跨周期依赖 CRUD（新 endpoint 或并入 dag）。
- 上一周期实例查询：`TaskInstanceRepository` / `WorkflowInstanceRepository` 新增「按 workflow+node+bizDate-offset 查 SUCCESS 实例」方法。

**前端（`frontend`）**
- `types.ts`：`DagEdge` 增 `strength`；运行请求增 `scope`。
- `workflow-canvas-view.tsx`：边右键菜单（强/弱切换）、节点右键运行范围、运行 Dialog 选 scope、配置面板跨周期依赖。
- i18n：`messages/{zh-CN,en-US}.json` 新增 `workflowCanvas` 命名空间下相关 key，两 bundle 等集。

**测试**
- 后端 JUnit：弱依赖就绪、自依赖就绪 + 首周期豁免、子图触发（TO_NODE/DOWNSTREAM/ONLY_NODE）、跨周期上一周期实例查询、CAS 死锁防御不回归。
- 前端：vitest（边强度/运行范围交互）+ 浏览器验证门（运行范围实际下发 + 节点变色）。

**非破坏性**：边默认 STRONG、运行默认 FULL，旧接口调用与历史数据行为不变；无 BREAKING。
