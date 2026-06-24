## Why

工作流触发当前是「半活引用」：`WorkflowTriggerService.trigger` 物化的是 **live `workflow_node` 拓扑 + live `task_def.current_version_no`**，却给实例打 `workflow_version_no = current_version_no` 的标签——名不副实。这带来三个问题：①**潜在 bug**：画布加了节点但没发布工作流，下一次周期调度也会把它跑进去，且被错标成旧版本号；②**不可复现**：工作流 vN 实际跑哪组任务版本取决于"此刻各任务最新发布版"，无法从版本号还原一次运行；③**无意外组合防护**：发了任务 A 没发 B，生产下一周期就跑未经联调的 `A(v4)+B(v2)`，且无任何可见信号。

虽然 `workflow_def_version.dag_snapshot_json` 早已在发布时冻结了整张 DAG（含各节点 task 版本号），但它**从未被运行期读取**——是描述性的死数据。现在把它转成「规定性唯一真相」，是补齐版本一致性与开发/生产隔离的最低成本时机：基建已存在，只需让触发器读它、加漂移可视化、加 `env` 维度。

## What Changes

- **触发器只认已发布快照（规定性快照）**：周期/正式运行的 `trigger` 改为从 `workflow_def.current_version_no` 对应的 `dag_snapshot_json` 物化——拓扑、各节点 `task_version_no` 全部来自快照，**不再读 live `workflow_node` / `task_def.current_version_no`**。工作流 vN 永远跑同一组确定版本，`workflow_version_no` 从此名副其实。修掉「草稿拓扑混入周期运行」的潜在 bug。
- **任务发新版 → 工作流"漂移"，不自动生效**：任务重新发布只让依赖它的工作流标记 `DRIFTED`（有更新 task 版本可用）；要采纳，须**重新晋级**（republish 工作流，重新冻结快照 + 校验）。这就是开发/生产隔离的晋级闸——草稿/新版改动碰不到生产调度，除非主动晋级。
- **漂移检测 + 一键重新晋级**：工作流读侧对比「快照钉死版本 vs 任务最新发布版」「快照 DAG vs live DAG（复用 `has_draft_change`）」算出统一的「需要晋级」状态；画布展示 badge；提供「重新晋级到最新」动作（重建快照）。
- **`env`（DEV/PROD）逻辑环境隔离维度**：`workflow_instance` / `task_instance` 新增 `env`。cron / 正式手动运行 = `PROD`（跑钉死快照）；画布试跑 / `run_mode=TEST` = `DEV`（跑草稿）。这期只做**逻辑隔离**（语义标签 + 区分），但为后续**物理隔离**（env 作用域 datasource 绑定、调度分区）预留模型挂载点，不必回头改实例模型。
- **与在途 `workflow-dependency-modes` 的共享契约**：两者都改 `trigger` 与 `dag_snapshot_json`。确立共享契约——**`trigger` 全程以已发布快照为唯一真相（拓扑+版本+边+强度）**；dependency-modes 的子图 `scope` 闭包（管"物化哪些节点"）与本 change 的版本钉死（管"每节点跑哪版"）正交叠加，互不冲突。详见 design「协调」小节。
- **非破坏性**：旧工作流首次按本逻辑运行前，对未发布过的工作流回退当前行为（无快照则不强制）；`env` 缺省 `PROD`，历史实例语义不变。无 BREAKING。

### Out of Scope（留作后续 change）

- **物理 dev/prod 双环境**：env 作用域的 datasource 绑定、独立调度分区、跨环境晋级流水线——体量大，单独立项。本 change 仅铺 `env` 列与逻辑语义。
- **任务级独立钉版/锁版 UI**：本 change 在工作流晋级时整组钉死即可，不做按节点手动选版本。

## Capabilities

### New Capabilities

- `workflow-version-binding`: 工作流运行的版本绑定语义——触发器以已发布快照为唯一真相物化拓扑与各节点 task 版本（硬钉死）；任务/DAG 漂移检测；「重新晋级」重建快照；各运行方式（周期/正式手动 vs 试跑）对快照真相源的遵守规则。
- `execution-environment`: 运行实例的 `env`（DEV/PROD）维度与逻辑隔离语义——草稿/试跑落 DEV、cron/正式手动落 PROD；为后续物理环境隔离预留模型挂载点。

### Modified Capabilities

- `workflow-authoring`: 发布快照由「描述性」升级为「规定性」（运行期唯一真相源）；发布即「晋级到生产」语义。
- `manual-run-trigger`: 触发物化由「读 live 拓扑/版本」改为「从已发布快照取拓扑与各节点 task 版本」；与 `workflow-dependency-modes` 的 `scope` 子图共享快照真相源。

> `workflow_instance` / `task_instance` 新增 `env` 列与 `workflow_version_no` 名副其实语义，由新增能力 `execution-environment` 与 `workflow-version-binding` 承载，不单独切 `instance-lifecycle` delta（其需求未变）。

## Impact

**Schema（`schema.sql` + H2/PG 双方言）**
- `workflow_instance` 加 `env VARCHAR(8) NOT NULL DEFAULT 'PROD'`；`task_instance` 加 `env VARCHAR(8) NOT NULL DEFAULT 'PROD'`（非破坏，旧数据全 PROD）。
- 漂移检测为读侧计算，无需新增列（复用 `task_def.current_version_no` / `workflow_def.has_draft_change`）。

**后端（`dataweave-master` / `dataweave-api`）**
- `WorkflowTriggerService.trigger`：物化源由 `nodeRepository.findByWorkflowIdAndDeleted`(live) + `taskDefRepository...current_version_no`(live) 改为解析 `workflow_def_version.dag_snapshot_json`；填 `env`。无发布快照时按现状回退（兼容）。
- `WorkflowService`：漂移检测读侧方法（对比快照版本 vs 任务最新发布版）；「重新晋级」复用现有 publish/`buildSnapshotJson` 路径，经 `GatedActionService` 闸门。
- `WorkflowDefVersionRepository`：按 `(workflowId, versionNo)` 取快照供 trigger 读取。
- 触发入口（cron `SchedulerKernel`/`CronScheduler`、agent、手动）传 `env`；`SchedulerKernel` 就绪门**不改**（钉死版本顺 `task_version_no` 透传）。

**前端（`frontend`）**
- `workflow-canvas-view.tsx` / 工作流列表：漂移 badge + 「重新晋级到最新」动作；`types.ts` 工作流详情增漂移字段。
- i18n：`messages/{zh-CN,en-US}.json` 新增漂移/重新晋级/环境相关 key，两 bundle 等集。

**测试**
- 后端 JUnit：trigger 从快照物化（拓扑+版本钉死）、live DAG 改动不影响周期运行、漂移检测（任务发新版后 DRIFTED）、重新晋级重建快照、`env` 落值、无快照回退兼容、CAS 死锁防御不回归。
- 前端：vitest（漂移 badge / 重新晋级交互）+ 浏览器验证门（漂移展示 + 重新晋级实际下发）。

**与 `workflow-dependency-modes` 协调**：两 change 共改 `trigger` 与快照格式。落地顺序与共享契约见 design「协调」小节——核心是先确立「trigger 只认快照」，两者朝同一真相源写、改互补区段。

**非破坏性**：`env` 缺省 PROD、无快照回退现状，旧接口与历史数据行为不变；无 BREAKING。
