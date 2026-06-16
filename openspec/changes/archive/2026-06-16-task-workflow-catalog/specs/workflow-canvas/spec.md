## MODIFIED Requirements

### Requirement: 可视化 DAG 画布编辑

系统 SHALL 提供基于 React Flow（`@xyflow/react` v12+）的可视化工作流画布。画布 MUST 支持：从左侧**类目树**（`<CatalogTree>`，按文件夹组织的 `task_def`）拖入节点（建 `TASK` 节点并绑定该 task）、从工具栏放置 `VIRTUAL` 节点、节点间拉线建边、节点拖动改变位置。节点位置变化 MUST 反映到内存图并在保存时回写 `pos_x/pos_y`。左侧来源从扁平 `task_def` 列表升级为类目树，但「从左侧拖入即建 `TASK` 节点并绑定 task」的行为契约不变。画布 MUST 区分两种拖拽语义：在类目树内拖动节点表示移动归属（调归类接口），从类目树拖任务到画布表示建 DAG 节点；以 drop target（树容器 vs ReactFlow pane）判定意图。画布视图 MUST 作为独立 workspace 视图注册，不改动既有 `task-flow-view`。

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
