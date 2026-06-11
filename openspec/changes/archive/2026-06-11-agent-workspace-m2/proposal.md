# Proposal: agent-workspace-m2

## Why

当前形态是「传统中台 + AI 副驾」：左 sidebar 模块导航为主、Agent 居右舷辅助，人找功能、AI 在旁帮忙。DataWeave 的定位是 **一个人的 AI-first 数据中台**——数据集成、开发、调度到产出整条流由 Agent 完成，UI 的角色应从「操作界面」反转为「观察与确认界面」：人说意图，AI 干活并**主动推出**可视化视图作工作汇报；用户常驻只看监控与任务流。M1 已打通 Agent 大脑（mock/workhorse 双模式）与闸门审计，前端交互范式反转是下一块拼图。

## What Changes

- **布局反转**（**BREAKING**，前端信息架构重构）：Agent 对话从右舷悬浮面板变为左栏常驻主驾（可调宽）；右侧主区变为多 tab Workspace；**删除 AppSidebar**，导航职责由 tab 条 "+" 启动菜单承接；路由收敛为 `/` 单页，旧路由（`/tasks`、`/ops` 等）变为 redirect 深链 `/?open=<view>`。
- **视图注册表 + 两层注意力模型**：现有页面降级为可装载视图组件；Pinned 层（驾驶舱、任务流、数据新鲜度、核心报表）恒定常驻，Ephemeral 层由 AI 召唤或手动打开、可关可 pin。新建「数据新鲜度」「核心业务报表」两个最小可用视图。
- **AG-UI 事件契约扩展**：新增 `CUSTOM(name="dataweave.ui.open")` 事件，载荷 `{ view, params, activate }`，同 view+params 去重激活。mock 模式由 `IntentRouter` 意图分支发射；workhorse 模式由 `WorkhorseBridge` 按「工具名→视图」映射在 `tool_call_done` 时发射。两模式同构。
- **workspace 会话持久化**：`agent_session` 表新增 `workspace_state` 列；新增 REST `GET/PUT /api/agent/sessions/{conversationId}/workspace`；前端防抖同步、按会话恢复（Pinned 不依赖快照，快照仅恢复 Ephemeral 与激活态）。
- **双向上下文**：现有 `properties.dataweave` 页面上下文管道数据源切换为 workspace store 的激活 tab 与选中对象。
- 前端引入 `zustand` 作为 workspace 状态机。

## Capabilities

### New Capabilities

- `agent-workspace`: 前端 Workspace 外壳——tab manager、视图注册表、Pinned/Ephemeral 两层、"+" 启动菜单、深链 redirect、新鲜度/报表最小视图、双向上下文回流。
- `agent-ui-events`: AI 召唤视图的 AG-UI 事件契约——`dataweave.ui.open` 载荷与去重语义、IntentRouter（mock）与 WorkhorseBridge（workhorse）两侧发射规则、前端消费行为。
- `workspace-persistence`: workspace 状态随对话会话持久化——`agent_session.workspace_state` 存储、REST 快照接口、防抖同步与恢复/回退策略。

### Modified Capabilities

- `cockpit-shell`: 三栏（sidebar + 内容 + 右舷 rail）shell 反转为「左对话主驾 + 右 Workspace」双栏；sidebar 导航移除，模块页路由收敛为视图深链。
- `copilot-rail`: 右舷悬浮可拖拽面板改为左栏常驻主驾（保留可调宽）；逐消息页面上下文来源从路由页改为 workspace 激活 tab。

## Impact

- **前端**：`components/app-shell.tsx`、`app-sidebar.tsx`（删）、`agent-rail.tsx`（重做为左栏）、`app/*/page.tsx` 全部路由（降级为 redirect）、新增 `lib/workspace/`（store + registry）与 `components/workspace/`（tab 条、视图容器、新鲜度/报表视图）；新依赖 `zustand`。
- **后端**：`IntentRouter`（各意图补发 ui.open）、`WorkhorseBridge`（工具→视图映射）、`schema.sql`（agent_session 加列）、master 新增 workspace 快照服务、api 新增 WorkspaceController；`AguiEvents.custom` 复用无改动。
- **协议**：AG-UI 事件序列不变，仅新增一个 CUSTOM 事件名；CORS/端点无变化。
- **测试**：后端 JUnit（事件发射、映射、REST）；前端 vitest（store 逻辑）；Browser Verification Gate 实跑。
