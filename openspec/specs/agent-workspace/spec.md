# agent-workspace Specification

## Purpose

定义主区多 tab Workspace 工作区的行为：Pinned/Ephemeral 两层 tab、视图注册表、手动启动菜单与深链逃生舱，以及数据新鲜度、核心业务报表两个最小可用视图。

## Requirements

### Requirement: Workspace 多 tab 工作区

系统 SHALL 在主区呈现多 tab Workspace：顶部 tab 条 + 下方激活视图渲染区。tab 分两层：**Pinned 层**（恒定常驻、不可关闭）与 **Ephemeral 层**（由 AI 召唤或用户手动打开，可关闭、可 pin 升级为常驻）。Workspace 状态（tab 列表、激活 tab）SHALL 以前端 store 为唯一真相源。

#### Scenario: 默认呈现 Pinned 底座

- **WHEN** 用户首次打开应用（无历史快照）
- **THEN** Workspace 呈现四个 Pinned tab：驾驶舱（全局健康）、任务流总览、数据新鲜度、核心业务报表，且激活其一

#### Scenario: Ephemeral tab 可关闭

- **WHEN** 用户点击某 Ephemeral tab 的关闭按钮
- **THEN** 该 tab 被移除，激活态落到相邻 tab；Pinned tab 不出现关闭按钮

#### Scenario: Ephemeral tab 可 pin 升级

- **WHEN** 用户对某 Ephemeral tab 执行 pin 操作
- **THEN** 该 tab 转为常驻（不可关闭、进入快照 pinned 区），且可再 unpin 降回 Ephemeral

### Requirement: 视图注册表

系统 SHALL 维护一个视图注册表，将 `viewType` 映射到 `{ 标题, 图标, 组件, 默认层级 }`。视图组件接收统一的 `params` 入参并自行取数。注册表 SHALL 至少包含：`cockpit`、`task-flow`、`freshness`、`reports`（Pinned）与 `sql-workbench`、`diagnosis`、`fleet`、`lineage`、`catalog`、`quality`、`integration`、`service`（Ephemeral，未实现者以占位视图呈现）。

#### Scenario: 注册视图可被打开

- **WHEN** Workspace 被要求打开注册表中存在的 `viewType`（含 `params`）
- **THEN** 对应组件以该 `params` 渲染于新 tab 或既有同键 tab

#### Scenario: 未注册视图被安全忽略

- **WHEN** Workspace 被要求打开注册表中不存在的 `viewType`
- **THEN** 请求被忽略并记 console 警告，Workspace 不崩溃、现有 tab 不受影响

### Requirement: 手动启动菜单与深链逃生舱

tab 条 SHALL 提供 "+" 启动菜单，列出注册表全部视图供用户不经 AI 手动打开。旧模块路由（`/tasks`、`/ops`、`/fleet` 等）SHALL 重定向为 `/?open=<view>` 深链；Workspace 挂载时 SHALL 消费 `?open=` 参数打开对应视图。

#### Scenario: 手动打开视图

- **WHEN** 用户点击 "+" 菜单中的「数据血缘」
- **THEN** Workspace 打开 `lineage` tab 并激活，不发生 Agent 对话

#### Scenario: 旧路由深链兜底

- **WHEN** 用户访问 `/ops`
- **THEN** 浏览器重定向到 `/?open=task-flow`，Workspace 激活任务流视图

#### Scenario: 非法深链回退默认

- **WHEN** 用户访问 `/?open=不存在的视图`
- **THEN** Workspace 呈现默认 Pinned 布局，不报错

### Requirement: 数据新鲜度最小视图

系统 SHALL 提供 `freshness` Pinned 视图：基于任务实例的最近成功时间，列出各任务产出的更新时效（任务名、最近成功时间、距今时长），按时效倒序。该视图为最小可用版，不含告警阈值配置。

#### Scenario: 展示产出时效列表

- **WHEN** 用户激活「数据新鲜度」tab 且存在已成功的任务实例
- **THEN** 视图列出各任务的最近成功时间与距今时长，最久未更新者居前

### Requirement: 核心业务报表最小视图

系统 SHALL 提供 `reports` Pinned 视图：以卡片网格展示 metrics 领域的指标（名称、口径版本、最新值/占位态）。该视图为最小可用版，不含图表钻取。

#### Scenario: 指标卡片网格

- **WHEN** 用户激活「核心业务报表」tab
- **THEN** 视图以卡片网格展示已定义指标；无数据的指标呈现明确空态而非空白
