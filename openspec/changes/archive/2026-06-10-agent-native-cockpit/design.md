## Context

DataWeave 现状：前端是平铺 5 项菜单（概览/Agent对话/任务开发/指标体系/数据血缘），`/agent` 是独立页；后端 `dataweave-master`（scheduler/workflow）、`dataweave-worker`（执行器）、`dataweave-alert`（告警）均为骨架。本变更把产品形态从「专家工具箱」转向「Agent 原生 + 自诊断」：右舷常驻 Agent 驱动全流程，平台能自分析任务失败根因。

约束（来自 CLAUDE.md，硬性）：
- 对话只用 **CopilotKit v2**（`@copilotkit/react-core/v2`），`selfManagedAgents={{ dataweave: httpAgent }}`，禁止 v1；`@ag-ui/client` 版本须与 CopilotKit 内部绑定一致。
- 依赖方向 `domain ← application ← infrastructure ← interfaces`，绝不反向。
- AG-UI 事件：SCREAMING_SNAKE_CASE，序列 `RUN_STARTED…RUN_FINISHED` 完整，文本走 Markdown，结构化走 CUSTOM。
- Spring Boot 4 / Java 25 / Jackson 3 注记照旧；改 domain/application/infra 后须 `./mvnw install`。
- 触及 `/agent`/Provider/AG-UI/布局 → 过 Browser Verification Gate；改主题/视觉 → 先读 `DESIGN.md`。

## Goals / Non-Goals

**Goals:**
- 三栏布局：左功能分组菜单 + 中观测台 + 右可收起常驻 Agent。
- 打通最小可炫闭环：`数据开发 → 调度运维 → 集群机器 → 智能诊断`，全程右舷驱动。
- worker 机器注册/心跳/资源指标上报；前端可观测节点状态。
- 失败自诊断：上下文采集 → 根因分析（mock）→ 修复建议 → 一键执行；`dataweave.diagnosis` 事件。
- 驾驶舱首页把「运行概况/失败/诊断中」前置。

**Non-Goals:**
- 数据集成 / 资产目录 / 数据质量 / 数据服务 / 数据安全治理的实质功能（本期仅菜单占位）。
- 真实 LLM 推理（仍 mock，预留 `LlmClient`）。
- worker 真实远程部署/编排到物理机的完整运维（本期为注册+心跳+指标上报骨架，远程部署留接缝）。
- 字段级血缘、指标市场等已在 dataweave-mvp 范围或更后期的能力。

## Decisions

### D1: 右舷 Agent 提升为全局布局组件，而非 `/agent` 路由
将 CopilotKit v2 `CopilotKitProvider` + `HttpAgent` 提到根 `layout`（或 app-shell 客户端边界），右舷面板在所有路由常驻。**Why**：跨页对话状态保持、页面上下文感知（用户「就当前所见」提问）是产品灵魂。**Alternative**：保留 `/agent` 独立页——被否，无法跨页常驻、上下文割裂。**注意**：Provider 必须在客户端边界；app-shell 已是 `"use client"`，将 Provider 收在此层，避免污染 server components。

### D2: 收起态用「同位悬浮球」
`✕` 与悬浮球同处右上角贴边，原地切换（用户明确要求开合零位移）。**Why**：开合落点一致，手不来回跑。状态用本地 UI state（是否收起），无需后端。

### D3: 页面上下文经 CopilotKit context 注入
当前模块、选中对象（taskId / instanceId / nodeId）通过 CopilotKit 的 readable context（或随消息附带的 metadata）传给后端 `IntentRouter`。**Why**：让「为什么挂了」无需复述对象。**Alternative**：纯靠用户文字描述——体验差、易歧义。

### D4: 自诊断 = 编排骨架 + mock 推理 + 结构化事件
`IntentRouter` 新增 `DIAGNOSE` 意图分支：调 master 取失败实例上下文（日志+机器指标+调度争抢+历史）→ 规则 mock 产出根因+建议 → `AguiOrchestrator` 发 `dataweave.diagnosis` CUSTOM 事件。推理走 `LlmClient` 接口（默认 mock）。**Why**：符合「真模型只换 `LlmClient` 不动骨架」的既定约定。

### D5: 修复执行需用户确认（人在环）
有副作用的修复（重跑/迁移/调权重）不自动执行，右舷给「执行」按钮，确认后触发 master 领域操作。**Why**：避免 Agent 误操作生产；符合「对外/不可逆动作先确认」。**Trade-off**：牺牲一点「全自动」观感换安全；自愈全自动留后期开关。

### D6: 机器上报走 master 端点 + JDBC 持久化
worker 周期 POST 心跳到 master（`worker_nodes` 表存最新指标 + 心跳时间），master 后台判离线（心跳超时阈值）。**Why**：MVP 零外部依赖（H2 可跑），Redis 队列留占位接缝。**Alternative**：Redis/MQ 上报——本期过重，留接缝注释。

### D7: 数据模型新增两表，复用既有
- `worker_nodes(id, node_id, host, capacity, cpu, mem, disk, load, status, last_heartbeat)`
- `task_diagnosis(id, instance_id, context_json, root_cause, suggestions_json, status, created_at)`
复用 `tasks` / `task_instances`。DDL 兼容 PG（开发 H2）。**指标口径表 `metrics` 不动**。

### D8: 菜单分组 + 占位路由
分组（研发/资产/资源与诊断）在 `app-sidebar` 数据结构里用分组 label。占位项指向占位页（统一「即将上线」组件），不抛错。MVP 闭环路由：`/`（驾驶舱）、`/tasks`（数据开发）、`/ops`（调度运维）、`/fleet`（集群机器）、`/diagnosis`（智能诊断，亦可由失败项进入）。

## Risks / Trade-offs

- **[Browser-only seam] CopilotKit/AG-UI 只在浏览器暴露的缝，build 测不出** → 完成后必过 Browser Verification Gate（Playwright 实跑，确认输入框渲染、console 无 error、能流式收发）。
- **[版本错配] `@ag-ui/client` 与 CopilotKit 内部绑定不一致 → `tsc` 报 `_debug` 私有属性冲突** → 不擅自升级，对齐当前绑定版本。
- **[Provider 提升引发 hydration/server-client 边界问题]** → Provider 收在已有 `"use client"` 的 app-shell 层，先 typecheck 再浏览器验证。
- **[install 陷阱] 改 master/worker domain 后单模块 run 用旧 class** → 改完先 `./mvnw install -DskipTests` 再 run。
- **[范围膨胀] 占位菜单诱导本期实现** → specs 明确占位项仅「即将上线」，不纳入 tasks 实现项。
- **[mock 诊断显得假] 规则 mock 根因可能牵强** → 用种子数据构造可信场景（如 3 号节点内存满 + 同节点并发），证据可视化增强可信；真模型后续替换。

## Migration Plan

1. 后端先行：新增 `worker_nodes`/`task_diagnosis` DDL + 种子 → `dataweave-worker` 注册/心跳 → master 查询与判离线 → `IntentRouter`/`AguiOrchestrator` 新意图与事件 → `./mvnw install` + 模块 compile。
2. 前端：app-shell 三栏 + Provider 提升 + 右舷面板（收起/悬浮球/上下文）→ 分组菜单 + 占位页 → 驾驶舱首页 → fleet/ops/diagnosis 观测页。
3. 联调：前后端 AG-UI 对齐（`dataweave.diagnosis` + 机器状态事件），CORS 放行 `:3000`。
4. 验证：后端 JUnit + WebTestClient；前端 typecheck + Browser Verification Gate 端到端实跑小白旅程。
5. 回滚：变更集中在新增组件/路由/表与 IntentRouter 新分支，保留原 `/agent` 逻辑可快速恢复为独立页；新表不影响既有 MVP 数据。

## Open Questions

- 心跳周期与离线阈值取值（建议 10s 心跳 / 30s 离线，可配）——实现时定默认。
- 页面上下文注入用 CopilotKit readable context 还是消息 metadata——实现时按 v2 API 实际能力择优。
- 「调度运维」`/ops` 与「数据开发」`/tasks` 的边界：本期 `/tasks` 管定义+SQL，`/ops` 管实例+调度日历+补数据，是否合并留实现微调。
