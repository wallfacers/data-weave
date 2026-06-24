## Context

DataWeave 任务与工作流均有「草稿 / 已发布」分层：
- `task_def`（草稿）发布后写 `task_def_version`，`current_version_no` 指向最后发布版。
- `workflow_def`（草稿 DAG）发布后写 `workflow_def_version`，其 `dag_snapshot_json` 由 `WorkflowService.buildSnapshotJson` 冻结整张 DAG——含各 TASK 节点 `current_version_no`（见 `WorkflowService.java` 的 `SnapshotNode`）。

但运行期从不读快照。`WorkflowTriggerService.trigger`（`WorkflowTriggerService.java:62-115`）：
1. `nodeRepository.findByWorkflowIdAndDeleted(wf.getId(), 0)` —— 物化 **live** 节点；
2. 每节点 `taskDefRepository.findById(node.getTaskId()).map(TaskDef::getCurrentVersionNo)` —— 读 **live** 任务最新发布版；
3. 却 `wi.setWorkflowVersionNo(wf.getCurrentVersionNo())` —— 打已发布版本号标签。

后果：①草稿 DAG（未发布的加/删节点）会混入周期运行并被错标版本号；②工作流 vN 实际跑哪组版本不确定，不可复现；③`A(v4)+B(v2)` 这类未联调组合无声跑出。`SchedulerKernel.contentOf` 已按 `(task_id, task_version_no) → task_def_version` 取内容，所以**只要把"哪版"喂对，调度内核无需改**。

并行约束：在途 change `workflow-dependency-modes`（§1-2 已实现，§3-8 在途）**也改 `trigger` 与 `dag_snapshot_json`**——它给快照加 `edge.strength`，并把 trigger 改为按 `scope` 物化节点子集（D5「闭包走已发布快照边」）。两 change 在 `trigger` 与快照格式上交汇，必须共享真相源契约。

不可违背的既有约束：调度死锁防御四不变量（SKIP LOCKED 认领、乐观 CAS、锁序 task→workflow、事务内只落库）、i18n 三规则、写操作经 `GatedActionService` 闸门、H2/PG 双方言、指标定义不可变（本 change 不动指标）。

## Goals / Non-Goals

**Goals:**
- 触发器以**已发布快照为唯一真相**物化拓扑 + 各节点 task 版本（硬钉死），工作流 vN 可复现、`workflow_version_no` 名副其实。
- 任务发新版只触发工作流**漂移**，需**重新晋级**才进生产——开发/生产逻辑隔离。
- 漂移检测（任务版本漂移 + DAG 草稿漂移统一）+ 一键重新晋级。
- `env`（DEV/PROD）维度落实例，逻辑隔离，且为物理隔离预留模型挂载点。
- 与 `workflow-dependency-modes` 共享「trigger 只认快照」契约，二者正交叠加。

**Non-Goals:**
- 物理 dev/prod 双环境（env 作用域 datasource、调度分区、跨环境晋级流水线）——单独立项。
- 按节点手动选版本/锁版 UI——本 change 整组随工作流晋级钉死。
- 改 `SchedulerKernel` 就绪门——钉死版本顺 `task_version_no` 透传，不碰。

## Decisions

### D1. `trigger` 物化源切到已发布快照（规定性快照）—— 共享契约的核心
**确立契约：`trigger` 的拓扑、各节点 `task_version_no`、边（及未来强度）全部来自 `workflow_def.current_version_no` 对应的 `dag_snapshot_json`，不再读 live `workflow_node` / `task_def.current_version_no`。**

- 实现：`trigger` 入参或内部按 `workflowId + currentVersionNo` 取 `workflow_def_version`，反序列化 `dag_snapshot_json` → 遍历 `SnapshotNode` 建 `task_instance`，`task_id`/`task_version_no` 直接取快照值；VIRTUAL 节点判定沿用 `node_type`。
- `workflow_node` 的 DB 主键 `node.getId()` 仍是 `task_instance.workflow_node_id` 外键来源——快照只存 `nodeKey`，需建立 `nodeKey → workflow_node.id` 映射（按 `workflowId + nodeKey` 查 live node 取 id；nodeKey 在工作流内稳定）。**只用 live node 取主键 id，拓扑与版本仍以快照为准**——这样事件流/节点变色（按 workflow_node_id）不破。
- 备选：在快照里额外冻结 `workflow_node.id`。否决——node id 是物理主键，快照应以稳定 `nodeKey` 为锚，避免快照绑死物理 id。
- **回退兼容**：工作流从未发布（`current_version_no=0`/无 `workflow_def_version`）时按现状物化 live（避免无快照可跑）。正式 ONLINE 工作流必有快照。

### D2. 与 `workflow-dependency-modes` 的协调 —— 正交叠加，不抢真相源
两 change 改 `trigger` 同一方法、`dag_snapshot_json` 同一 JSON，但职责正交：

| 维度 | dependency-modes 负责 | 本 change 负责 |
|---|---|---|
| 物化**哪些**节点 | `scope`（FULL/TO_NODE/DOWNSTREAM 闭包） | —（默认 FULL） |
| 每节点跑**哪版** | —（保持读 live ❌） | **快照钉死** ✅ |
| 快照 JSON 字段 | 加 `SnapshotEdge.strength` | 依赖 `SnapshotNode.taskVersion` 规定性 |
| 就绪门 | 大改（强弱+跨周期） | 不碰 |

- **契约统一后**：dependency-modes 的 D5 已声明「闭包走已发布快照边」——本 change 把它外推为「节点集与版本也走快照」。两者都从快照物化，dependency-modes 管子集筛选、本 change 管版本解析，merge 是机械活（改互补区段）。
- **落地顺序（推荐）**：本 change 的 D1 物化重写作为**地基先落**（或与 dependency-modes 的 §4 trigger 改造合并提交），再叠 `scope`。若 dependency-modes 先行，其 §4.1 trigger 改造**必须**预留「物化源 = 快照」抽象（把"从哪取节点/版本"收敛成一处），否则本 change 翻规定性时要重写其子图逻辑。
- **并行执行**：trigger 之外（漂移检测、`env` 列、前端 badge、重新晋级）与 dependency-modes 零交集，可独立并行。trigger 一处因契约已定可两边协同。

### D3. 漂移检测：读侧计算，统一「任务版本漂移 + DAG 草稿漂移」
工作流「需要晋级」= 满足任一：
- **任务版本漂移**：快照中任一 `SnapshotNode.taskVersion < 该 task 当前 current_version_no`（任务发了更新的已发布版，工作流还钉在旧版）。
- **DAG 草稿漂移**：`workflow_def.has_draft_change=1`（live DAG/属性改了未发布）——复用现有标志。

- 读侧实现：`WorkflowService` 新增 `computeDrift(workflowId)` → 取当前快照 + 批量查相关 task 的 `current_version_no` 比对，返回 `{drifted: boolean, driftedNodes: [{nodeKey, pinned, latest}], dagDraft: boolean}`。
- 暴露在工作流详情/列表 DTO，前端渲染 badge。不落库（每次读时算，避免与发布动作竞态产生陈旧标志）。
- 备选：发布任务时反向写 dependent 工作流的 `drifted` 列。否决——引入跨实体写放大与一致性维护成本，读侧计算更简单且永远准确。

### D4. 重新晋级：复用 publish 端点重建快照（UI 编辑类操作，不经闸门）
「重新晋级到最新」= 对工作流重新执行发布（`POST /api/workflows/{id}/publish`）：重新 `buildSnapshotJson`（从各任务**最新** `current_version_no` + 当前 live DAG 重新冻结）+ 校验无环 + bump `workflow_def.current_version_no` + 清 `has_draft_change`。

- 与普通「发布工作流」是同一动作，只是 UI 入口语义化为「重新晋级」（采纳漂移的任务新版）。**无需新 endpoint，复用 publish**。
- **不经闸门**：现有架构里 UI 编辑类写操作（`create`/`update`/`saveDag`/`publish`/`offline`）均直执行不经 `GatedActionService`；只有 rollback/run（产生运行副作用、起实例）与 agent/MCP 工具写才经闸门。重新晋级属编辑类（重建快照、bump 版本），与 publish 同类，故沿用非闸门直执行。
- **审计**：每次晋级新建一条 `workflow_def_version`（含新快照 + `published_at`），即天然版本审计轨；与普通发布一致。
- 备选：为重新晋级单设 `PROMOTE_WORKFLOW` 闸门动作。否决——与 publish 重复且和现有编辑类操作的非闸门约定不一致，over-engineering。

### D5. `env`（DEV/PROD）维度：实例列 + 触发入口判定
- Schema：`workflow_instance` / `task_instance` 加 `env VARCHAR(8) NOT NULL DEFAULT 'PROD'`。
- 落值规则（触发入口判定，不靠运行期推断）：
  - cron 周期 + 正式手动运行（`run_mode=NORMAL`）→ `env=PROD`。
  - 画布试跑 / `run_mode=TEST`（含 `triggerTestRun`）→ `env=DEV`。
- 这期 `env` 是**语义标签**：用于实例列表区分、运维统计口径、为物理隔离铺路。**不**驱动 datasource 选择（留后续 change）。
- 备选：复用 `run_mode`（TEST≈DEV）不加列。否决——`run_mode`（NORMAL/TEST）语义是"计不计统计/跑草稿与否"，与"环境"正交；物理隔离需要独立 env 轴，提前铺列避免回头改实例模型与索引。
- 为何 `DEFAULT 'PROD'`：历史实例与未显式传 env 的路径默认生产语义，向后兼容；DEV 仅试跑显式落。

### D6. 运行方式 × 真相源对齐
- **周期（CRON）/ 正式手动 FULL**：从快照物化（钉死版本），`env=PROD`。
- **试跑（TEST）**：孤立实例（`workflow_instance_id=null`），跑草稿 `content_override`/`version=null`——**不走快照**，保持现状，`env=DEV`。试跑本就是验证草稿，钉死无意义。
- **手动子图（dependency-modes 的 TO_NODE/DOWNSTREAM）**：从快照物化子集（钉死版本），`env=PROD`（正式运行）；版本真相源与周期一致。
- **手动 ONLY_NODE / 单任务运行**：复用 `triggerManualTaskRun`（跑任务 `current_version_no`）——单任务无工作流快照语境，沿用现状；`env` 按 run_mode 落（NORMAL→PROD）。

## Risks / Trade-offs

- **[与 dependency-modes 的 trigger 双改撞车]** → 先确立 D1/D2 共享契约「trigger 只认快照」，两边朝同一真相源写、改互补区段（子集筛选 vs 版本解析）；落地顺序优先把物化重写作地基，或合并提交 trigger 改造。
- **[无快照工作流触发]** 从未发布的工作流无 `dag_snapshot_json` → D1 回退 live 物化（兼容），仅 ONLINE（必有快照）走规定性路径。
- **[nodeKey→node.id 映射失配]** 快照存 nodeKey，需查 live node 取主键 → nodeKey 在工作流内稳定；若 live 已删该 node（快照有、live 无）则该节点不可物化 → 视为发布后又删节点未重晋级，按「DAG 草稿漂移」提示用户重新晋级；trigger 对缺失 node 跳过并告警，不静默丢。
- **[漂移读侧计算开销]** 每次工作流详情/列表读都算漂移 → 批量查 task `current_version_no`（按快照节点 taskId 集合一次 IN 查询），量级可控；列表页可仅算"是否漂移"布尔、详情页再算明细。
- **[env 列加了却不驱动行为，似冗余]** → 明确这期是逻辑隔离 + 物理隔离的模型挂载点；提前铺列避免后续改实例模型/索引（破坏性更大）。spec 明示当前仅语义标签。
- **[用户误以为改任务发布即生效]** 行为从"自动流入"变"需重新晋级" → 漂移 badge + 「重新晋级」动作显式提示；i18n copy 明示"工作流仍跑已晋级版本，点重新晋级采纳任务新版"。

## Migration Plan

1. Schema（PG + H2 双方言，单一 `schema.sql` 靠 `IF NOT EXISTS`/列默认）：`workflow_instance` `task_instance` 各加 `env VARCHAR(8) NOT NULL DEFAULT 'PROD'`。
2. 旧行为：所有现存实例 `env=PROD`；未发布工作流走 D1 回退 live 物化——零行为回归。
3. 代码：D1 trigger 物化重写（含 nodeKey→id 映射与回退）→ D5 env 落值 → D3 漂移读侧 → D4 重新晋级 UI 接线 → 前端 badge/i18n。
4. 回滚：删 `env` 列、回退 trigger 物化为读 live 即恢复现状；漂移检测为读侧无持久化，回滚无残留。

## Open Questions

- **与 dependency-modes 的合并 vs 排序**：trigger 物化重写是合进 dependency-modes §4 一起提交，还是本 change 先落地铺地基？倾向：本 change 的 D1 先落（最小、是 dependency-modes 子图的前提），dependency-modes §4 在其上叠 scope。需与 dependency-modes 在途负责人对齐。
- **漂移粒度展示**：列表页是否只显示布尔 badge、详情页才展开"哪些节点 pinned vN < latest vM"？倾向：是，控读侧开销。
- **重新晋级是否需要 diff 预览**：晋级前是否给用户看"将从 A(v3→v4)、B(v2→v2) 变化"的确认弹窗？倾向：MVP 先直接晋级 + toast，diff 预览留后续增强。
- **`env` 是否进 `workflow_instance` 与 `task_instance` 两处**：可否只在 workflow_instance 落、task_instance 派生？倾向：两处都落（孤立单任务实例无 workflow_instance，仍需 env），冗余但查询简单。

## 决议（实现落定 2026-06-24）

四问均按上述「倾向」落定：

1. **合并 vs 排序 → 排序**：D1（trigger 物化重写）先落地铺地基，dependency-modes 的 Slice（`workflow_edge.strength` / `workflow_dependency.earliest_biz_date`）在其上叠加。二者共存零冲突，由 206 个后端测试全绿（master 178 + api 26）+ 无 git 冲突标记实证。`WorkflowDagSnapshot.Edge` 抽为共享 record，version-binding 用其 `taskVersionNo`、dependency-modes 用其 `strength`，纯字段叠加。
2. **漂移粒度 → 布尔 + 明细分层**：`DriftResult{drifted:boolean, driftedNodes:[{nodeKey,pinned,latest}]}`；画布 badge 显示漂移节点数，`title` tooltip 展开逐节点 `nodeKey: vP→vL`。读侧计算不落库。
3. **diff 预览 → MVP 不做**：重新晋级直接复用 publish + toast，无晋级前 diff 确认弹窗；留后续增强。
4. **env 两处都落**：`workflow_instance` 与 `task_instance` 均有 `env`（孤立单任务实例无 workflow_instance，仍需环境标签），冗余但查询简单；列可空，`NULL≡PROD`，不强制所有触发路径 set（兼容并发 agent 不设 env 的 trigger 路径）。
