## Why

传统数据中台（DataWorks 式）把能力切成给**专家分工**的工具箱：数据开发、运维、集成各占一个操作台，要人去每个台子手动点。数据中台最高频的痛点——「任务又跑挂了，到底为什么」（OOM？资源不足？节点压力？）——也要人去翻日志、查机器、对调度图。DataWeave 的定位是「用 Agent 编织数据」：让**小白**通过和常驻 Agent 多轮对话就能全流程干完数开/运维的活，并且平台能**自己分析自己**——任务失败时自动采集上下文、根因分析、给出一键修复。本变更把这套「Agent 原生 + 自诊断」的产品形态落成可运行的骨架。

## What Changes

- **前端三栏布局重构**：左「功能分组菜单」+ 中「观测台」+ 右「常驻 Agent copilot」。Agent 从 `/agent` 独立菜单项升级为**贯穿全站的右舷面板**。
- **右舷 Agent 可收起**：右上角 `✕` 收起 ⇄ 同位置（右上角贴边）悬浮球展开，原地切换。面板感知当前页面上下文（看着哪个模块/哪条失败任务）并喂给对话。
- **菜单按功能分组**：研发（数据集成·数据开发·调度运维）/ 资产（资产目录·数据血缘·数据质量·指标体系）/ **资源与诊断（集群机器·资源监控·智能诊断）**。本期 MVP 仅打通 `数据开发 → 调度运维 → 集群机器 → 智能诊断` 闭环，其余菜单占位。
- **概览 → 驾驶舱 / 健康中心**：开屏即「今天跑了啥 / 挂了几个 / Agent 正在诊断什么」，把自诊断前置到首页。
- **worker 机器上报**：`dataweave-worker` 增加机器注册 + 心跳 + 资源指标（CPU/内存/磁盘/load）上报；前端「集群机器」可观测节点状态。
- **自诊断闭环**：任务实例失败 → 自动采集（日志/机器指标/调度上下文/历史）→ Agent 根因分析 → 结论 + 修复建议 + 一键执行。新增 AG-UI 结构化结果事件 `dataweave.diagnosis`。
- **Agent 编排扩展**：`IntentRouter` 新增「建任务 / 查机器 / 诊断失败」意图；`AguiOrchestrator` 新增机器状态、任务实例图、诊断结果的 CUSTOM 事件。

## Capabilities

### New Capabilities

- `cockpit-shell`: 三栏应用骨架——左侧功能分组菜单、中部观测台路由、驾驶舱/健康中心首页（全局态势 + 失败 + 诊断中）。
- `copilot-rail`: 右舷常驻 Agent 面板——收起/悬浮球切换、页面上下文感知、跨页驱动各模块操作。
- `worker-fleet`: worker 机器注册、心跳、资源指标上报，以及「集群机器/资源监控」观测视图与 AG-UI 机器状态事件。
- `self-diagnosis`: 任务失败根因自诊断闭环——上下文采集、Agent 根因分析、修复建议与一键执行、`dataweave.diagnosis` 事件。

### Modified Capabilities

<!-- 既有能力（agent-conversation/task-scheduling 等）仍在 dataweave-mvp 变更内、尚未归档进 base specs，故本次相关扩展以新增 capability 承载，不产生 delta。 -->

## Impact

- **前端**：`components/app-shell.tsx`（三栏布局）、`components/app-sidebar.tsx`（分组菜单）、新增右舷面板组件、`app/page.tsx`（驾驶舱）、新增 `app/fleet`·`app/ops` 等观测页；CopilotKit v2 Provider 提升到全局 layout。
- **后端**：`dataweave-worker`（机器注册/心跳/指标上报）、`dataweave-master`（调度运维状态、机器与实例查询）、`dataweave-api` 的 `IntentRouter`·`AguiOrchestrator`（新意图与 CUSTOM 事件）、`dataweave-alert`（演进为诊断信号来源）。
- **AG-UI 协议**：新增 CUSTOM 事件 `dataweave.diagnosis`、机器状态/任务实例图事件；前后端同步、CORS 放行 `http://localhost:3000`。
- **数据模型**：新增 `worker_nodes`（机器注册+心跳+资源指标）、`task_diagnosis`（诊断记录）表；复用既有 `tasks`/`task_instances`。
- **依赖**：无新增重量级依赖；前端沿用 CopilotKit v2 + `@ag-ui/client`（版本须与内部绑定一致）。
- **Gate**：触及 `/agent`/Provider/AG-UI/布局 → 必须过 Browser Verification Gate；触及主题/视觉 → 先读 `DESIGN.md`。
