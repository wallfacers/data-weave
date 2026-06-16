## MODIFIED Requirements

### Requirement: 可视化 DAG 画布编辑

系统 SHALL 提供基于 React Flow（`@xyflow/react` v12+）的可视化工作流画布。画布 MUST 支持：从左侧**类目树**（`<CatalogTree>`，按文件夹组织的 `task_def`）拖入节点（建 `TASK` 节点并绑定该 task）、从工具栏放置 `VIRTUAL` 节点、节点间拉线建边、节点拖动改变位置。节点位置变化 MUST 反映到内存图并在保存时回写 `pos_x/pos_y`。画布 MUST 区分两种拖拽语义：在类目树内拖动节点表示移动归属（调归类接口），从类目树拖任务到画布表示建 DAG 节点；以 drop target（树容器 vs ReactFlow pane）判定意图。画布 MUST NOT 渲染常驻拖拽提示文案。画布 MUST 作为"数据开发"IDE 内的一种**子 Tab** 呈现（一个工作流对应一个画布子 Tab），不再作为独立顶层 workspace 视图。

#### Scenario: 拖入任务建节点
- **WHEN** 用户从左侧类目树将一个 `task_def` 拖到画布（ReactFlow pane）
- **THEN** 画布在落点创建一个 `TASK` 节点，绑定该 task_id，记录落点坐标，且不改变该 task 的类目归属

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

### Requirement: 画布叠加 DAG 运行态

画布子 Tab SHALL 能订阅其所属工作流某次运行实例的事件流（`/api/ops/workflow-instances/{id}/events/stream`），并把每个节点的运行态实时叠加渲染到对应 DAG 节点上（按 node_key 对应）。运行态 MUST 以语义化设计 token 区分（运行中/成功/失败/等待），与既有节点类型样式叠加而不冲突。断线 MUST 支持续传（Last-Event-ID）。

#### Scenario: 节点随事件变色
- **WHEN** 画布订阅了一个运行中工作流实例的事件流，某节点状态从等待变为运行中再到成功
- **THEN** 画布上对应 node_key 的节点依次切换为运行中/成功的运行态样式

#### Scenario: 编辑态与运行态可区分
- **WHEN** 画布同时处于可编辑状态并叠加了运行态
- **THEN** 运行态着色不掩盖节点类型与选中态，用户仍能识别 TASK/VIRTUAL 与当前选择
