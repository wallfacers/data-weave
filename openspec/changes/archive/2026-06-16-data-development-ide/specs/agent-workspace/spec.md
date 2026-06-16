## MODIFIED Requirements

### Requirement: Workspace 多 tab 工作区

系统 SHALL 在主区呈现多 tab Workspace：顶部 tab 条 + 下方激活视图渲染区。tab 分两层：**Pinned 层**（恒定常驻、不可关闭）与 **Ephemeral 层**（由 AI 召唤或用户手动打开，可关闭、可 pin 升级为常驻）。Workspace 状态（tab 列表、激活 tab）SHALL 以前端 store 为唯一真相源。Pinned 底座 MUST NOT 再包含已移除的"任务流"视图，移除后 Pinned 为四个：驾驶舱、数据新鲜度、业务报表、系统指标。

#### Scenario: 默认呈现 Pinned 底座

- **WHEN** 用户首次打开应用（无历史快照）
- **THEN** Workspace 呈现四个 Pinned tab：驾驶舱、数据新鲜度、业务报表、系统指标，且激活其一（"任务流"不再在 Pinned 底座）

#### Scenario: Ephemeral tab 可关闭

- **WHEN** 用户点击某 Ephemeral tab 的关闭按钮
- **THEN** 该 tab 被移除，激活态落到相邻 tab；Pinned tab 不出现关闭按钮

#### Scenario: Ephemeral tab 可 pin 升级

- **WHEN** 用户对某 Ephemeral tab 执行 pin 操作
- **THEN** 该 tab 转为常驻（不可关闭、进入快照 pinned 区），且可再 unpin 降回 Ephemeral

### Requirement: 视图注册表

系统 SHALL 维护一个视图注册表，将 `viewType` 映射到 `{ 标题, 图标, 组件, 默认层级 }`。视图组件接收统一的 `params` 入参并自行取数。注册表 MUST 包含一个标题为"数据开发"的视图承载数据开发 IDE（左树 + 内层子 Tab）。注册表 MUST NOT 再包含 `sql-workbench`（原"任务开发"）与 `task-flow`（原"任务流"）视图。

#### Scenario: 注册视图可被打开

- **WHEN** Workspace 被要求打开注册表中存在的 `viewType`（含 `params`）
- **THEN** 对应组件以该 `params` 渲染于新 tab 或既有同键 tab

#### Scenario: 数据开发视图在启动菜单可见

- **WHEN** 用户展开"+"启动菜单
- **THEN** 菜单列出"数据开发"视图，且不再列出"任务开发""任务流"两项

#### Scenario: 未注册视图被安全忽略

- **WHEN** Workspace 被要求打开注册表中不存在的 `viewType`（含已移除的 `sql-workbench`/`task-flow`）
- **THEN** 请求被忽略并记 console 警告，Workspace 不崩溃、现有 tab 不受影响
