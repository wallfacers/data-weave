## MODIFIED Requirements

### Requirement: 可视化 DAG 画布编辑

系统 SHALL 提供基于 React Flow（`@xyflow/react` v12+）的可视化工作流画布。画布 MUST 支持：从左侧**类目树**（`<CatalogTree>`，按文件夹组织的 `task_def`）拖入节点（建 `TASK` 节点并绑定该 task）、从工具栏放置 `VIRTUAL` 节点、节点间拉线建边、节点拖动改变位置。节点位置变化 MUST 反映到内存图并在保存时回写 `pos_x/pos_y`。画布 MUST 区分两种拖拽语义：在类目树内拖动节点表示移动归属（调归类接口），从类目树拖任务到画布表示建 DAG 节点；以 drop target（树容器 vs ReactFlow pane）判定意图。画布 MUST NOT 渲染常驻拖拽提示文案。画布 MUST 作为"数据开发"IDE 内的一种**子 Tab** 呈现（一个工作流对应一个画布子 Tab），不再作为独立顶层 workspace 视图。

画布 MUST 允许把 `status=DRAFT`（未发布）的任务拖入并建 `TASK` 节点——开发态编排自由，不在画布层阻止。引用了未发布任务的 TASK 节点 MUST 给出「未发布」视觉标记（语义化设计 token），使发布前一眼可辨；引用完整性由后端 `workflow.publish` 校验收口（见 `workflow-authoring`），画布不重复拦截放置。

#### Scenario: 拖入任务建节点
- **WHEN** 用户从左侧类目树将一个 `task_def` 拖到画布（ReactFlow pane）
- **THEN** 画布在落点创建一个 `TASK` 节点，绑定该 task_id，记录落点坐标，且不改变该 task 的类目归属

#### Scenario: 拖入未发布任务给出标记
- **WHEN** 用户把一个 `status=DRAFT` 的任务拖入画布建 TASK 节点
- **THEN** 画布正常创建该节点并对其渲染「未发布」视觉标记，不阻止放置

#### Scenario: 树内拖动移动归属而非建节点
- **WHEN** 用户在左侧类目树内把一个任务节点拖入另一个文件夹（drop target 在树容器内）
- **THEN** 前端调用归类接口更新该任务归属，画布上不新建任何节点

#### Scenario: 放置虚拟节点并连线
- **WHEN** 用户从工具栏添加 `VIRTUAL` 节点并从它连线到一个 `TASK` 节点
- **THEN** 画布创建虚拟节点与一条有向边（虚拟节点→任务节点）

#### Scenario: 节点类型可视区分
- **WHEN** 画布渲染节点
- **THEN** `TASK` 与 `VIRTUAL` 节点以不同的视觉样式（图标/形状）区分，使用语义化设计 token

#### Scenario: 画布以子 Tab 承载
- **WHEN** 用户点击类目树中一个工作流叶子
- **THEN** 该工作流的画布在数据开发 IDE 内层区以一个画布子 Tab 打开/激活，而非独立顶层视图

## ADDED Requirements

### Requirement: 未发布节点的发布前反馈

画布 SHALL 在用户尝试发布含未发布任务节点的工作流时给出可读反馈。当后端 `workflow.publish` 因存在引用 `DRAFT` 任务的 TASK 节点返回拒绝（code `workflow.node_task_not_online`）时，画布 MUST 展示本地化错误并高亮相关「未发布」节点，且不静默丢弃编辑态。UI copy MUST 走 next-intl 双语 key（`zh-CN`/`en-US` 等集），MUST NOT 硬编码中文回退。

#### Scenario: 发布被未发布节点拦截
- **WHEN** 用户对含未发布任务节点的工作流点「发布」，后端返回 `workflow.node_task_not_online`
- **THEN** 画布展示本地化错误、高亮相关未发布节点，编辑态保留不丢失

#### Scenario: 未发布节点反馈双语
- **WHEN** 用户切换 UI 语言（zh-CN/en-US）
- **THEN** 未发布标记与发布拦截提示均按当前 locale 本地化，console 无 missing-key 报错
