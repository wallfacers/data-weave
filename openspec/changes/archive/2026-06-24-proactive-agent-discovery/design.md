## Context

驾驶舱诊断引擎与 `applyFix` 闸门已是真链路，但喂给它的数据是 `data.sql` 死种子，且没有任何"主动发现"机制——`DiagnosisService.diagnoseInstance` 幂等但**被动**（无 `@Scheduled`、无失败事件监听），举手台只 poll `/api/diagnosis` 被动展示。前端 CopilotKit v2 无法在 run 之外注入消息，封死了"Agent 主动开口"。

隔壁 `workhorse/workhorse-assistant` 已用 Tauri + 自研聊天验证过完整的多会话聊天架构（`src/session/types.ts` 的 `MessagePart` 联合、`ChatRuntime` 每会话缓冲、`SessionProvider` 给每个活会话挂 SSE 后台并流），是本次自有聊天台的直接范本。

## Goals / Non-Goals

**Goals:**
- 通用「主动发现」框架：任何模块写一个 `Inspector` 即接入，产出统一 `Finding`，下游（举手台/播报/修复）零改动。
- Agent 真主动：平台自己发现失败 → 自动诊断 → 举手台冒泡 + 左栏主动开口 → 一键经闸门修复，端到端竖切跑通。
- 诊断证据从死种子变真数据（L1 真采集）。
- 自有多会话聊天台替换 CopilotKit，掌管消息存储。

**Non-Goals:**
- 数据质量/SLA/血缘巡检器（本次只首发 `TaskFailureInspector`，验证框架可插拔即可，其余 Inspector 后续 change）。
- 每会话绑不同厂商后端（多会话本次仅多聊天线程，单一 `agent.mode`）。
- `SqlTaskExecutor` 行数回报通道（L1 的 row_count 链路延后；本次 history/节点指标足以撑真证据）。
- 故障注入做成运行时组件（本次仅测试期脚本）。

## Decisions

### D1 — Finding 是窄腰
统一发现模型，上游任意 Inspector、下游举手台/播报/闸门全部围绕它：
```
Finding {
  id, source:"TASK_FAILURE"|"DATA_QUALITY"|"SLA"|..., severity:"INFO"|"WARN"|"CRITICAL",
  targetType, targetId, title, rootCause,
  evidenceJson:{nodeMem,nodeCpu,nodeLoad,concurrentTasks,history,...},
  actionsJson:[{key,label,actionType}],
  status:"OPEN"|"ANNOUNCED"|"RESOLVED", announced:bool, createdAt, updatedAt
}
```
`TaskDiagnosis` **不重写**：`TaskFailureInspector` 内部调 `DiagnosisService.diagnoseInstance(instanceId)`，把 `TaskDiagnosis`（title/rootCause/contextJson/suggestionsJson）映射成一行 `Finding`（source=TASK_FAILURE）。复用诊断、不推倒。

### D2 — Inspector SPI + 调度
```java
interface Inspector {
  String source();                 // "TASK_FAILURE"
  List<Finding> inspect();         // 扫描自己负责的域，产出未落库的新发现
}
```
`InspectorScheduler`：`@Scheduled(fixedDelay)` 定时兜底遍历所有 `Inspector`（地基）；失败事件（`InstanceStateMachine` CAS→FAILED 后发布）加速触发（锦上添花，定时兜底保证不漏）。落库前以 `(source,targetType,targetId)` 去重——已有 OPEN/ANNOUNCED 的 Finding 不重复建。

### D3 — 真推通道（AgentNotifier + 持久 SSE）
新增 `GET /api/agent/stream`（`text/event-stream`，长连）。后端 `AgentNotifier` 用 Reactor `Sinks.many().multicast()` 广播；新 Finding 落库后发 `agent.finding`，需要主动开口时发 `agent.message`。all-in-one 单 JVM 直接走进程内 Sink；分布式模式经 Redis pub/sub 桥接（复用现有 `dw:evt` 模式）。`/agui` 契约不变。

### D4 — 自有聊天台掌管消息存储
前端弃用 CopilotKit `CopilotChat`，按 workhorse-assistant 范本自研：
- `MessagePart` 联合：`text | reasoning | tool_call | permission | error | pending`
- `ChatRuntime{messages[], streaming:Set}`，多会话 `runtimes: Map<sessionId, ChatRuntime>`
- 用户发问：`POST /agui` → 解析 AG-UI 事件流 → 追加 parts（沿用现有 SCREAMING_SNAKE_CASE 事件）
- 持久订阅 `/api/agent/stream`：收到 `agent.message` 就 push 进对应 runtime（**主动开口 = 往消息数组 push**）；收到 `agent.finding` 刷新举手台
- `permission` part 内联：闸门返回 PENDING_APPROVAL 时渲染同意/拒绝按钮，点击即决策（替代现有 CUSTOM 拼装）

### D5 — 冻结契约（两个 Agent 的唯一接缝）
```
POST   /agui                              用户对话（现有，不变）
GET    /api/agent/stream      [新·持久SSE] event: agent.finding | agent.message | keepalive
GET    /api/findings                      举手台列表 → Finding[]（默认 status in OPEN,ANNOUNCED）
POST   /api/findings/{id}/apply           body {actionKey} → 闸门 → {executed,message,outcome,newInstanceId?}
GET    /api/agent/sessions                多会话列表
POST   /api/agent/sessions                新建会话 → {id,title,createdAt}
DELETE /api/agent/sessions/{id}           删除会话
GET    /api/agent/sessions/{id}/history   会话历史（重水合 messages）
```
统一信封沿用 `ApiResponse{code,data,message}`；SSE event 名小写点分（`agent.finding`），与 AG-UI 大写下划线事件区分。前端开发期可 mock `/api/agent/stream` 与 `/api/findings`。

### D6 — L1 真采集
- `HeartbeatReporter`：用 `com.sun.management.OperatingSystemMXBean` 采进程 cpu/系统 load/内存占用，替换硬编码 0.3/0.45/1.2。
- `FleetService`/诊断证据：master 端按 `worker_node_code` 聚合 `task_instance` 中 DISPATCHED/RUNNING 计数得真实 `concurrentTasks`，不再信任 worker 上报。
- history：近 7 天该 `task_id`×该 `worker_node_code` 的 FAILED 计数，写入 Finding evidence。

### D7 — 故障注入 = 测试期脚本
`scripts/` 下提供可重跑脚本（或测试 fixture）：插入真实 FAILED `task_instance`（日志含 OOM 堆栈）+ 拉高目标节点 `worker_nodes` 指标。仅测试/demo 期手动跑，无 `@Component`、prod profile 不加载，杜绝污染真实采集。

## Risks / Trade-offs

- **偏离 CLAUDE.md 前端硬门**：弃 CopilotKit 是有意决定（换来主动播报/多会话/审批内联的掌控权），随本次改门并写明理由。代价：聊天台的流式渲染/markdown/工具块要自己维护——以 workhorse-assistant 为范本降低风险。
- **all-in-one 真采集偏"真实但平淡"**：单 JVM 下真指标只有宿主一台，不会自然出现 node-3 95% 争抢。故障注入脚本（D7）补足可复现 demo；两者职责不同，都保留。
- **持久 SSE 连接管理**：长连需处理断线重连、心跳保活、多客户端 fan-out。用 Reactor Sinks 多播 + keepalive 事件；前端断线指数退避重连。
- **巡检去重边界**：去重键 `(source,targetType,targetId)`。同一实例反复失败重跑会产生新 instanceId → 新 targetId → 新 Finding，符合预期（每次失败都该被发现）；但需保证已 RESOLVED 的不被同 key 复活。
- **并行协作的共享文件**：`CLAUDE.md` 两包都要改（🅱 改前端门、🅰 加导航行），分属不同小节，git 不冲突；`schema.sql`/`data.sql` 仅 🅰 动。约定：🅰 owns 所有 backend+sql，🅱 owns 所有 frontend。
