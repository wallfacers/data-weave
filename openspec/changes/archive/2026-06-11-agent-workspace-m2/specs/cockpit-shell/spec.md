# cockpit-shell Delta Specification

## MODIFIED Requirements

### Requirement: 三栏应用骨架

应用 SHALL 采用「左侧 Agent 对话主驾 + 右侧 Workspace 工作区」双栏布局，作为全站统一框架。左栏为常驻 Agent 对话（可拖拽调宽，见 `copilot-rail`）；右侧主区为多 tab Workspace（见 `agent-workspace`），承载全部模块视图。独立模块路由页不再作为主承载——根路由 `/` 即 Workspace。

#### Scenario: 任意入口均呈现双栏框架

- **WHEN** 用户打开 `/` 或任一旧模块路由深链
- **THEN** 页面渲染左侧 Agent 对话栏与右侧 Workspace，旧路由经重定向落入对应视图 tab

#### Scenario: 视图切换不重置对话

- **WHEN** 用户在 Workspace 中切换或开关 tab
- **THEN** 仅 Workspace 区内容更新，左栏对话历史与输入状态不被重置

### Requirement: 驾驶舱健康中心首页

驾驶舱（健康中心）SHALL 作为 Workspace 的 Pinned tab 之一（`cockpit` 视图）常驻呈现：展示「今日任务运行概况（成功/失败/运行中数量）」「失败任务列表」「Agent 正在诊断中的事项」三个区块，把自诊断能力前置。首次打开应用（无快照）时 SHALL 默认激活驾驶舱 tab。

#### Scenario: 开屏即见全局态势

- **WHEN** 用户首次访问 `/`
- **THEN** Workspace 默认激活驾驶舱 tab，展示今日运行概况计数、失败任务清单、诊断中事项三区块

#### Scenario: 从失败项直达诊断

- **WHEN** 用户在驾驶舱点击某条失败任务
- **THEN** Workspace 打开（或激活）该实例的 `diagnosis` tab（见 `self-diagnosis`），且左栏 Agent 上下文同步到该失败实例

## REMOVED Requirements

### Requirement: 功能分组菜单

**Reason**: 布局反转后左侧由 Agent 对话主驾占据，模块导航不再是主路径——AI 召唤与 "+" 启动菜单取代人找功能的导航范式。
**Migration**: 全部视图入口迁移至 Workspace tab 条 "+" 启动菜单（见 `agent-workspace`）；旧模块路由保留为 `/?open=<view>` 重定向深链，外部书签不失效。
