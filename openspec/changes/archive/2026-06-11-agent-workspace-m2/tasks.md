# Tasks: agent-workspace-m2

## 1. 前端 Workspace 骨架（先立外壳，功能不丢）

- [x] 1.1 `pnpm add zustand`；新建 `lib/workspace/store.ts`——`{ tabs, activeTabId }` 状态机：open（view+规范化 params 去重激活）、close、activate、pin/unpin、序列化/恢复；附 vitest 单测（去重、pin 升级、未知视图忽略、损坏快照回退）
- [x] 1.2 新建 `lib/workspace/registry.tsx`——viewType→{title, icon, component, defaultPinned}；登记 `cockpit`/`task-flow`/`sql-workbench`/`diagnosis`/`fleet`（复用现有组件）与 `lineage`/`catalog`/`quality`/`integration`/`service`（占位视图）
- [x] 1.3 新建 `components/workspace/`——tab 条（Pinned 无关闭钮、Ephemeral 可关可 pin、"+" 启动菜单）+ 视图容器；`app/page.tsx` 改为渲染 Workspace，消费 `?open=` 深链（非法值回退默认布局）
- [x] 1.4 重做 `AppShell`：左 = 常驻 Agent 对话栏（复用 AgentChat，迁移拖拽调宽与宽度持久化，移除悬浮球/收起逻辑），右 = Workspace；删除 `AppSidebar`；旧路由 `app/{tasks,ops,fleet,diagnosis,metrics,lineage,catalog,quality,integration,service}/page.tsx` 改为 `redirect("/?open=<view>")`
- [x] 1.5 `pnpm typecheck` + 浏览器实跑：双栏渲染、手动 "+" 开关 tab、深链重定向、对话流式回复正常、console 零 error（截图入 `tmp/`）

## 2. 新 Pinned 视图（最小版）

- [x] 2.1 `freshness` 数据新鲜度视图：按任务实例最近成功时间列「任务名/最近成功/距今时长」，时效最差居前（数据来源：现有任务实例查询接口，不足则 master 补最小查询）
- [x] 2.2 `reports` 核心业务报表视图：metrics 指标卡片网格（名称/口径版本/最新值或空态）
- [x] 2.3 两视图登记入 registry（defaultPinned），与 `cockpit`/`task-flow` 共同构成四 Pinned 底座；`pnpm typecheck` + 浏览器确认

## 3. AG-UI ui.open 事件（AI 召唤）

- [x] 3.1 mock 侧：`IntentRouter` 各意图分支补发 `dataweave.ui.open`（诊断→diagnosis{instanceId}、查机器→fleet、建任务→task-flow{highlightTaskId}、指标→reports、血缘→lineage、Text-to-SQL→sql-workbench）；JUnit 断言各意图事件流含正确载荷
- [x] 3.2 workhorse 侧：`WorkhorseBridge` 增加静态「工具名→viewType」映射，`tool_call_done` 时补发 `ui.open`；未映射工具不发；JUnit 单测映射与透传
- [x] 3.3 前端订阅：`onCustomEvent` 处理 `dataweave.ui.open` → store.open；同一 run 内重复事件合并；未知 view 忽略 + console.warn
- [x] 3.4 `./mvnw install -DskipTests` 后 mock 模式端到端浏览器验证：「看下集群机器」→ fleet tab 自动打开；重复说一遍 → 去重激活不新开

## 4. 会话持久化

- [x] 4.1 `schema.sql`：`agent_session` 加 `workspace_state TEXT`（H2/PG 兼容）；master 增加按 conversationId get/put workspace_state 服务（复用 getOrCreateSession）
- [x] 4.2 api 模块新增 `WorkspaceController`：`GET/PUT /api/agent/sessions/{conversationId}/workspace`，blob 透传、无快照返回空态、CORS 对齐；WebTestClient 测试（写读回环、空态、覆盖更新）
- [x] 4.3 前端：threadId 提升共享；store 变更防抖 ~1s PUT；挂载 GET 恢复（仅 Ephemeral+激活态，Pinned 不依赖快照；失败/损坏回退底座）；vitest 覆盖恢复回退
- [x] 4.4 浏览器验证：开若干 Ephemeral tab → 刷新 → 恢复如初；清空快照 → 仅 Pinned 底座

## 5. 双向上下文与收尾

- [x] 5.1 `properties.dataweave` 数据源切换为 store 激活 tab（view/params/选中对象）；验证「点中失败实例后问『为什么挂了』」上下文直达
- [x] 5.2 清理：移除 AppSidebar 及死代码、弃用的 localStorage 键；`pnpm design:lint` 通过
- [x] 5.3 文档同步：CLAUDE.md（Repository Layout/运行入口的布局描述）、docs/architecture.md 前端形态章节
- [x] 5.4 全量验证：后端 `./mvnw install` 全测试 + 前端 typecheck/vitest + Browser Verification Gate 完整走查（mock 模式召唤、去重、持久化、深链、审批卡回归），截图入 `tmp/` 后清理
