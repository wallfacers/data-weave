## ADDED Requirements

### Requirement: 类目树本地搜索

`<CatalogTree>` 组件 SHALL 在顶部提供搜索输入框，按名称对已加载的任务与工作流叶子做本地过滤（大小写不敏感，子串匹配）。命中叶子所在的祖先文件夹 MUST 自动保持可见/展开以便定位；搜索清空 SHALL 恢复原树展开态。

#### Scenario: 按名称过滤叶子

- **WHEN** 用户在类目树搜索框输入关键字
- **THEN** 树内仅保留名称包含该关键字的任务/工作流叶子可见，且其祖先文件夹展开以可见

#### Scenario: 清空搜索恢复

- **WHEN** 用户清空搜索框
- **THEN** 树恢复全量展示与原展开态

### Requirement: 叶子（任务/工作流）重命名与删除

`<CatalogTree>` SHALL 支持对任务与工作流叶子就地重命名与删除，入口经行内操作（hover/右键），交互经 `Dialog`（非原生弹框）。重命名 MUST 调用对应写接口（任务 `PUT /api/tasks/{id}`、工作流 `PUT /api/workflows/{id}`）；删除 MUST 调用软删接口（`DELETE /api/tasks/{id}`、`DELETE /api/workflows/{id}`）并二次确认。成功后树 MUST 刷新。

#### Scenario: 重命名任务

- **WHEN** 用户对一个任务叶子选择重命名并在 Dialog 提交新名
- **THEN** 系统调用任务写接口更新名称，树刷新显示新名

#### Scenario: 删除工作流需确认

- **WHEN** 用户对一个工作流叶子选择删除
- **THEN** 系统弹 `Dialog` 二次确认，确认后软删该工作流，树不再显示

## MODIFIED Requirements

### Requirement: 前端可复用类目树组件与画布集成

前端 SHALL 提供可复用的 `<CatalogTree>` 组件，并接入"数据开发"IDE 左侧常驻面板。组件 MUST 区分两种拖拽语义：树内拖动节点表示移动归属，从树拖任务到画布表示新建 DAG 节点；以 drop target（树容器 vs ReactFlow pane）判定意图。组件 MUST NOT 渲染常驻的拖拽提示文案（原"拖任务到画布建节点 · 拖入文件夹改归属"一行移除）。组件缩进 MUST 保证叶子与同级子文件夹左对齐、相对父级缩进一级，层级视觉一致。

#### Scenario: IDE 面板按类目树展示资产
- **WHEN** 用户打开数据开发 IDE
- **THEN** 左侧面板以类目树（文件夹 + 任务 + 工作流，区分图标）组织资产，而非扁平列表，且不渲染拖拽提示文案

#### Scenario: 树内拖动移动归属
- **WHEN** 用户在类目树内把一个任务节点拖入另一个文件夹
- **THEN** 前端调用归类接口更新该任务归属，不在画布上新建节点

#### Scenario: 拖任务到画布建节点
- **WHEN** 用户把一个任务从类目树拖到 ReactFlow 画布区域
- **THEN** 前端按既有逻辑在画布新建该任务的 DAG 节点，不改变该任务的类目归属

#### Scenario: 按标签横切过滤树
- **WHEN** 用户在面板顶部选中某标签 chip
- **THEN** 树内仅保留贴有该标签的任务/工作流可见

#### Scenario: 缩进层级对齐
- **WHEN** 类目树渲染嵌套文件夹与其下叶子
- **THEN** 叶子图标与同级子文件夹图标左对齐，且相对父文件夹缩进恰好一级，无错位
