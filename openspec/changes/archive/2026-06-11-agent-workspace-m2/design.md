# Design: agent-workspace-m2

## Context

现状三栏：`AppSidebar`（11 项模块导航）| 模块路由页 | `AgentRail`（右舷悬浮可拖拽面板，内嵌 `AgentChat`）。Agent 管道已就绪：后端 `AguiEvents.custom(name, value)` 发 CUSTOM 事件（`dataweave.diagnosis/fleet/approval` 等已在用）；前端 `agent.subscribe({ onCustomEvent })` 已消费 `dataweave.approval`。真实视图组件 4 个（驾驶舱 FleetCard+DiagnosisCard、ops InstanceTable、tasks SqlWorkbench+TaskDefList、diagnosis 卡片），其余 6 页为 ComingSoon 占位。`agent_session` 表按 `conversation_id`（AG-UI threadId）落审计，`AgentAuditService.getOrCreateSession` 已有。前端无全局状态库。

经 brainstorming 确认的决策输入：四个 Pinned 视图全要、tab 状态随对话会话持久化到后端、M2 直接布局反转一步到位、workspace 真相源选客户端状态机（方案 A）、删 sidebar 并入 "+" 菜单、引入 zustand。

## Goals / Non-Goals

**Goals:**

- 交互范式反转：对话为主驾（左栏常驻），Workspace 多 tab 为工作区（右侧主区）。
- AI 能经 `dataweave.ui.open` 召唤视图；mock / workhorse 两模式同构发射。
- Pinned 恒定底座 + Ephemeral 召唤层；Ephemeral 可关、可 pin 升级。
- workspace 状态随会话持久化、按 conversationId 恢复。
- 手动逃生舱保留："+" 启动菜单 + 旧路由深链。

**Non-Goals:**

- 不做 `ui.close` / `ui.update`（AI 不替用户关 tab，YAGNI）。
- 不做 AI 动态生成 UI（schema 渲染引擎）——只做「预制视图 + AI 传参召唤」。
- 不做跨会话/多设备 workspace 同步策略（一个会话一份快照，够用）。
- 新鲜度、报表两视图只做最小可用版，不做完整功能模块。
- 不改 AG-UI 事件序列、闸门与审计链路。

## Decisions

### D1. workspace 真相源 = 前端 zustand store（方案 A）

`{ tabs: [{id, view, params, pinned}], activeTabId }` 放 zustand。AI 的 `ui.open` 事件与用户手动操作驱动同一个 store；后端只存恢复用快照。

- 弃 URL 驱动（方案 B）：与「随会话持久化」双真相源冲突，URL 容不下选中态。
- 弃 AG-UI 共享状态 STATE_DELTA（方案 C）：手动操作要绕后端往返，CopilotKit v2 支持成熟度与 mock 模式实现成本都高。
- 弃 Context+useReducer：快照防抖、事件订阅回调里跨组件取状态，store API 干净得多，成本 ~1kB。

### D2. `dataweave.ui.open` 事件契约

```
CUSTOM { name: "dataweave.ui.open",
         value: { view: string, params?: object, activate?: boolean (默认 true) } }
```

- 去重键 = `view + 规范化 params`：已存在则激活既有 tab，不重复开。
- 未知 `view` / params 不合法：前端忽略 + console.warn，workspace 不崩。
- 发射点：
  - **mock**：`IntentRouter` 意图分支在现有结构化事件之外补发（诊断→`diagnosis{instanceId}`、查机器→`fleet`、建任务→`task-flow{highlightTaskId}`、指标→`reports`、血缘→`lineage`、Text-to-SQL→`sql-workbench`）。
  - **workhorse**：`WorkhorseBridge` 在 `tool_call_done` 按静态「工具名→视图」映射表补发（如 `create_task`→`task-flow`）。确定性映射，不依赖 LLM 自觉、不改提示词；映射表与 MCP 工具注册同处维护。
- 弃「新增 open_view MCP 工具让 LLM 主动开视图」：M2 不需要，桥接层映射已覆盖；留作后续增强。

### D3. 视图注册表与两层注意力模型

`frontend/lib/workspace/registry.tsx`：`viewType → { title, icon, component, defaultPinned }`。

| viewType | 层 | 来源 |
|---|---|---|
| `cockpit` | Pinned | 复用驾驶舱（FleetCard + DiagnosisCard） |
| `task-flow` | Pinned | 复用 InstanceTable + TaskDefList |
| `freshness` | Pinned | **新建最小版**：按任务实例最近成功时间列各任务产出时效 |
| `reports` | Pinned | **新建最小版**：metrics 领域数据卡片看板 |
| `sql-workbench` | Ephemeral | 复用 SqlWorkbench |
| `diagnosis` | Ephemeral | 复用诊断卡片，接受 `instanceId` |
| `fleet` | Ephemeral | 复用 FleetCard 全量视图 |
| `lineage`/`catalog`/`quality`/`integration`/`service` | Ephemeral | ComingSoon 占位视图 |

- Pinned 四 tab 恒定存在、不可关闭；Ephemeral 可关、可 pin（pin 后进快照的 pinned 区，仍可 unpin）。
- 视图组件 props 统一为 `{ params }`，内部自取数据——与现页面组件行为一致，降级成本最低。

### D4. 布局与外壳

- `AppShell` 重做：左 = Agent 对话栏（常驻、可拖拽调宽、复用 `AgentChat` 与现有宽度持久化逻辑），右 = `Workspace`（tab 条 + 视图容器）。
- `AppSidebar` 删除；tab 条右端 "+" 菜单列出注册表全部视图（手动逃生舱，不经 AI）。
- 路由收敛：`/` 渲染 workspace；旧路由页改为 `redirect("/?open=<view>")`，workspace 挂载时消费 `?open=` 开 tab。
- 视图内轻操作（重跑、确认、kill）保留；重操作（建任务、改调度）仍走对话——手动路径天然过闸门。

### D5. 会话持久化

- `schema.sql`：`agent_session` 加 `workspace_state TEXT`（H2/PG 兼容）。
- master：`AgentAuditService`（或新 `WorkspaceStateService`）加 get/put workspace_state，按 `conversation_id` 寻址，复用 `getOrCreateSession`。
- api：新增 `WorkspaceController`——`GET/PUT /api/agent/sessions/{conversationId}/workspace`，body 为前端序列化 JSON，后端不解析语义（透明 blob），CORS 与现有一致。
- 前端：store 变更防抖 ~1s PUT；挂载时 GET 恢复。**Pinned 不依赖快照**——快照只存 Ephemeral tabs 与 activeTabId；GET 失败/快照损坏 → 回退纯 Pinned 布局。
- conversationId 对齐：`AgentChat` 的 threadId 提升共享（store 或 context），确保对话与 workspace 同 key。

### D6. 双向上下文

现有 `properties.dataweave`（→ 后端 forwardedProps → PageContext）管道不动，数据源从「路由页 + searchParams」换为「store 激活 tab 的 view/params + 选中对象」。用户在 tab 里点中实例后问「为什么挂了」，Agent 拿到的上下文与 M1 同构。

## Risks / Trade-offs

- [布局反转一步到位，改动面大，期间 `/agent` 体验可能断档] → 实现顺序先立新外壳与 registry（旧页面组件复用、功能不丢），AG-UI 事件与持久化随后；每步过编译 + Browser Verification Gate。
- [新鲜度/报表无现成领域接口，最小版可能两头不靠] → 明确最小口径：新鲜度 = 任务实例最近成功时间衍生列表；报表 = metrics 现有数据卡片网格。超出口径的留后续变更。
- [CUSTOM 事件乱发导致 tab 爆炸] → 去重激活语义 + 每轮 run 内同一 view 只开一次；Ephemeral 可一键关闭。
- [快照与 registry 演进不兼容（旧快照引用已删视图）] → 恢复时按 registry 过滤未知 viewType，静默丢弃。
- [zustand 与 React 19/Next 16 RSC 边界] → store 只在 client 组件用；workspace 整体 `"use client"`，与现状（页面本就 client 重）一致。
- [删 sidebar 后用户失去熟悉导航] → "+" 菜单 + 深链兜底；一个人的中台，导航本就是逃生舱而非主路径。

## Migration Plan

1. 前端先行：registry + store + 新 AppShell/Workspace（手动开 tab 可用，AI 召唤未接）→ 浏览器验证。
2. 后端事件：IntentRouter 补发 + WorkhorseBridge 映射 → mock 模式端到端验证「说一句话，tab 自己开」。
3. 持久化：schema 加列 + REST + 前端同步恢复。
4. 收尾：旧路由 redirect、删 AppSidebar、文档（CLAUDE.md/architecture.md 布局描述）更新。

回滚：纯增量分层，任一步可独立回退；schema 加列向后兼容（旧代码忽略该列）。

## Open Questions

- 无（brainstorming 已敲定全部关键决策）。
