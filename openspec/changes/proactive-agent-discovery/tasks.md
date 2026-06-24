# 实施任务

> **并行切分**：Group 0 是两包共同的契约约定（已冻结于 design.md D5，无需改动，仅对齐认知）。
> **🅰 后端窗口**做 Group 1–5（只动 `backend/` + `schema.sql`/`data.sql` + `scripts/`）。
> **🅱 前端窗口**做 Group 6–9（只动 `frontend/`）。
> 两包仅经 design.md D5 冻结契约耦合；🅱 开发期可 mock `/api/agent/stream` 与 `/api/findings`。
> 收尾联调（Group 10）两窗口合流后一起做。

## 0. 契约对齐（两包共同前提，不写代码）

- [ ] 0.1 双方确认 design.md §D5 冻结契约：`/agui`(不变)、`GET /api/agent/stream`、`GET /api/findings`、`POST /api/findings/{id}/apply`、`/api/agent/sessions*`；`Finding` 与 SSE 事件形状
- [ ] 0.2 约定文件归属：🅰 owns `backend/`+sql+scripts+CLAUDE.md 导航行；🅱 owns `frontend/`+CLAUDE.md 前端栈门小节（不同小节，git 不冲突）

## 1. 🅰 Finding 模型与持久化

- [x] 1.1 `finding` 表加入 `schema.sql`（含顶部 `DROP TABLE IF EXISTS finding;` 幂等；列见 design D1），自检 CREATE/DROP 配对 — 45/45 配对
- [x] 1.2 `Finding` domain + `FindingRepository`（Spring Data JDBC），字段含 evidenceJson/actionsJson/status/announced
- [x] 1.3 `data.sql` 视需要播种少量首屏 Finding（或留空由巡检填充，按 D 决策）；H2/PG 两库各验一遍 — 播种 OOM Finding(id=1) 对应现有诊断
- [x] 1.4 单测：Finding 存取 + status 流转 — 落为 `FindingService`(去重/resolve/markAnnounced) + `FindingServiceTest` 4 通过

## 2. 🅰 Inspector SPI 与失败巡检器

- [x] 2.1 `Inspector` SPI 接口（`source()` + `inspect(): List<Finding>`）
- [x] 2.2 `TaskFailureInspector`：扫未诊断 FAILED 实例 → 调 `DiagnosisService.diagnoseInstance` → 映射 `TaskDiagnosis`→`Finding`（不重写诊断）；suggestions→actions(key/label/actionType) 映射
- [x] 2.3 去重：inspect 内 `findingService.exists`(任意状态)跳过已处理目标 + `recordIfNew` 按 (source,targetType,targetId) 对 OPEN/ANNOUNCED 去重（双层）
- [x] 2.4 单测：未处理 FAILED→映射 Finding；已处理跳过不诊断；mapActions 回退 — TaskFailureInspectorTest 3 通过

## 3. 🅰 巡检调度

- [x] 3.1 `InspectorScheduler`：`@Scheduled(fixedDelay)` 遍历所有 `Inspector` Bean 执行巡检并落库；runOnce 返回本轮新建（供 Group 4 推送）
- [x] 3.2 失败事件加速触发：`InstanceStateMachine` casTaskTerminal→FAILED 发 `TaskInstanceFailedEvent`，`@Async @EventListener` 加速；定时兜底保底（异步竞态下轮补）；api 加 `@EnableAsync`
- [x] 3.3 单测：遍历所有 Inspector + 新增第二个 Inspector(DATA_QUALITY)自动纳入 + 单个巡检器抛错隔离 — InspectorSchedulerTest 2 通过

## 4. 🅰 真推通道与 Findings/会话 API

- [x] 4.1 `AgentNotifier`：经现有 `EventBus`(InMemory/Redis 双实现) 广播到 `dw:agent:notify`，信封 `{event,data}`；finding()/message() 两出口
- [x] 4.2 `GET /api/agent/stream` 持久 SSE 控制器：订阅 EventBus → Flux fan-out `agent.finding`/`agent.message`/`keepalive`(20s)，断线关订阅
- [x] 4.3 巡检落库后 `AgentNotifier.finding` + 主动开口 `message`(i18n `finding.proactive.announce`) + `markAnnounced` 去重
- [x] 4.4 `GET /api/findings`(active) + `POST /api/findings/{id}/apply`：`FindingActionService`→`DiagnosisService.submitFix`(抽出返回完整 GateResult)→闸门，EXECUTED 置 RESOLVED；返回 outcome 分流
- [x] 4.5 多会话持久化：新建 `agent_chat_session`/`agent_chat_message` 两表 + domain/repo/`AgentSessionService` + `AgentSessionController`(增/列/删/历史/追加消息)
- [x] 4.6 测试：FindingEndpointTest(list+apply 经闸门) + AgentSessionEndpointTest(增→追加→历史→删全链) h2 通过

## 5. 🅰 L1 真采集 + 故障注入脚本

- [ ] 5.1 `HeartbeatReporter` 接 `com.sun.management.OperatingSystemMXBean` 采真实 cpu/load/mem，替换硬编码
- [ ] 5.2 master 端按 `worker_node_code` 聚合 DISPATCHED/RUNNING 计数 → 真实 `concurrentTasks` 入诊断证据
- [ ] 5.3 近 7 天 `task_id`×`worker_node_code` FAILED 计数 → 真实 `history` 入 Finding evidence
- [ ] 5.4 `scripts/` 故障注入脚本：插入真实 FAILED 实例（日志含 OOM 堆栈）+ 拉高目标节点指标；非运行时、prod 不加载
- [ ] 5.5 测试：真采集值随环境变化（非常量）；脚本跑完巡检能产出真证据 Finding

## 6. 🅱 自有聊天台（替换 CopilotKit）

- [ ] 6.1 移除 `@copilotkit/*` 聊天用法与 `agent-rail.tsx` 旧实现（参照 `workhorse/workhorse-assistant/src/session/types.ts` 与 `components/chat/`）
- [ ] 6.2 `MessagePart` 联合（text/reasoning/tool_call/permission/error/pending）+ `ChatMessage` + `ChatRuntime` 类型
- [ ] 6.3 聊天台组件：消息列表渲染（markdown/工具块/reasoning）、输入框、流式增量追加
- [ ] 6.4 AG-UI 流消费：`POST /agui` → 解析 SCREAMING_SNAKE_CASE 事件序列 → 追加 parts（RUN_STARTED…RUN_FINISHED）
- [ ] 6.5 CLAUDE.md 前端栈门小节：CopilotKit→自有聊天台，写明偏离理由（指向本 change design）

## 7. 🅱 多会话 store 与侧栏

- [ ] 7.1 `runtimes: Map<sessionId, ChatRuntime>` store（zustand，与现有 workspace store 风格一致）
- [ ] 7.2 会话侧栏：新建/切换/删除；切换保留各自缓冲
- [ ] 7.3 持久化：走 `/api/agent/sessions*`；重开会话经 history 端点重水合
- [ ] 7.4 后台并流：非可见会话的流继续接收（参照 SessionProvider「每活会话挂监听」）

## 8. 🅱 真推订阅与主动开口

- [ ] 8.1 持久订阅 `GET /api/agent/stream`（EventSource 直连后端 SSE_BASE，避开 Next 代理缓冲；断线指数退避重连）
- [ ] 8.2 收到 `agent.message` → push 进目标会话 runtime（Agent 无人发问主动开口）
- [ ] 8.3 收到 `agent.finding` → 刷新举手台

## 9. 🅱 举手台通用化 + 闸门内联

- [ ] 9.1 举手台从 `/api/diagnosis` 改为渲染 `GET /api/findings` 的通用 `Finding[]`（title/severity/rootCause/evidence 卡片）
- [ ] 9.2 一键修复 → `POST /api/findings/{id}/apply`，按 outcome 分流（executed / PENDING_APPROVAL / rejected）
- [ ] 9.3 `permission` part 内联审批：PENDING_APPROVAL 时渲染同意/拒绝并提交决策
- [ ] 9.4 i18n：新增用户可见文案进 `messages/{zh-CN,en-US}.json`（双 bundle 键集一致），无 `…` 进行中态

## 10. 收尾联调（两窗口合流后）

- [ ] 10.1 后端 `./mvnw install -DskipTests` + 各模块 `compile` 零错；前端 `pnpm typecheck` 零错
- [ ] 10.2 端到端竖切（浏览器门，真跑）：故障注入脚本 → 巡检发现 → 真证据诊断 → 举手台冒泡 + 左栏主动开口 → 点修复 → 闸门执行/审批分流
- [ ] 10.3 H2/PG 双库各启一遍（schema 幂等、PG 二次启动不撞表）
- [ ] 10.4 浏览器验证产物入 `tmp/`，验完清理；CLAUDE.md 导航行（🅰）补 Finding/巡检/主动发现入口
