## Context

数据开发编辑子 Tab 的「每次运行一个日志 Tab」（`task-editor-pane.tsx` 的 `RunLogsTabs`）左侧已有一个状态圆点，但它反映的是 **SSE 日志流的连接状态** `ConnState`（live/ended/error/connecting），不是任务实例的执行结果。前端 `LogTab` 用 `useEventSource` 订阅 `/api/ops/instances/{id}/logs/stream`，仅以 `events.some(e => e.type === "end")` 判定结束——而后端这个 `end` 事件 `data:""` 不带任何结果（`OpsController.endEvent()`，`data:""`）。

更深一层：日志流的 **live 路径**（`OpsController.logStream`，实例连接时仍 RUNNING）只用 `Flux.interval` 轮询 logBus 推日志，**永不查 state、永不 emit `end`、永不关流**。只有「连接时实例已终态」的快照路径（`streamEndedLogs`）才回放归档日志后 emit `end`。这导致实时观看的运行走不到干净「已结束」态，SSE 连接可能悬挂——是一个独立潜伏 bug。

另一条本可携带状态变迁的通道 `/api/ops/workflow-instances/{id}/events/stream` 监听的 `dw:evt:{id}` 通道，全代码库零 publisher（仅 scheduler 内部 `dw:wake` 在用），是死管道，且按 workflow_instance 而非 task_instance 索引。

实例终态写入路径已明确：worker `WorkerReportService.reportFinished/reportFailed` → `InstanceStateMachine.casTaskTerminal` 乐观 CAS 写 SUCCESS/FAILED（kill 写 STOPPED），均为事务内原子落库，终态在前端任何读取前已提交。

约束：① 圆点改反映 `InstanceStates`（RUNNING/SUCCESS/FAILED/STOPPED）；② 配色须用 DESIGN.md 语义 token（现 `task-editor-pane.tsx:625` 的 `bg-emerald-500`/`bg-amber-500` 是硬编码违例，`log-panel.tsx` 的 `StatusDot` 用 `bg-success`/`bg-warning` 才合规）；③ i18n 文案复用既有 state key（`stateRunning`/`stateSuccess`/`stateFailed`/`stateStopped` 均已存在，无需新增）；④ 后端改动须非破坏性（`end` 事件 data 丰化，不影响只看 `event==="end"` 的客户端）。

## Goals / Non-Goals

**Goals:**
- 运行 Tab 圆点反映实例真实状态：RUNNING（绿脉冲）/ SUCCESS（绿稳态）/ FAILED（红）/ STOPPED（灰），一眼区分「远程完成」与「运行错误」。
- 后端日志流在实例终态时携带结果并关闭——顺带修掉 live 路径永不结束、连接悬挂的潜伏 bug。
- push-based，零前端轮询、无新 REST 端点、无 DB schema 变更。
- 圆点配色统一到语义 token，满足 DESIGN.md。

**Non-Goals:**
- 不修 `workflow-instances/{id}/events/stream` 的死管道（`realtime-streams` spec 声称推送状态变迁但 impl 零 publisher 的既有 spec/impl 缺口）。
- 不给工作区顶层 Tab（导航层）加状态圆点——本变更只管编辑子 Tab 内的运行 Tab。
- 不做 WAITING/DISPATCHED/PAUSED 等中间态的独立圆点颜色（运行 Tab 场景下这些是瞬时态，且 SSE 此时正在 connecting/live，统一按「运行中」处理）。
- 不新增「失败原因」展示（仅用颜色区分成败；原因已在日志正文里）。

## Decisions

### 决策 1：终态结果从「日志流自身」获取（Path B），而非前端轮询或激活事件流

**选择**：改造 `OpsController.logStream` 的 live 路径为状态感知——tick 内顺带读实例 `state`，终态时做一次 logBus 尾部排空后 emit 携带结果的 `end` 并 complete 流。

**备选 A（前端轮询 REST）**：被否。无干净的单实例 state 端点（`/api/cli/instances/{id}/logs` 附带整坨日志 blob；`/api/ops/instances` 是列表要前端过滤）；轮询有延迟和并发压力；且修不了 live 永不结束的 bug。

**备选 C（激活 `dw:evt:{id}` 死管道 / 新建 task_instance 事件流）**：被否。工作量最大，通道当前未接线，按 workflow_instance 索引而非 task_instance；为了一颗圆点过度设计。

**为何 B 胜出**：日志流**本该**在任务结束时关闭并报告结果——这是正确性修复而非为圆点硬加；push-based 无轮询；改动局部在单个 SSE 端点；同时消灭连接悬挂 bug。

### 决策 2：live 路径的状态查询节流，避免每 tick 一次 DB 读

`Flux.interval` 日志轮询是亚秒级；若每 tick 都 `OpsService.findInstance(id)` 会变成「每个连接观看者 × 亚秒 × 全行读取」的 DB 压力。

**选择**：状态查询走独立、较慢的节拍（约每 2s 一次），与日志轮询解耦；日志延迟不受影响。终态检出后，先做一次 logBus 尾部排空（避免丢尾日志），再 emit `end{state}` 并 complete。

**备选**：仅在「logBus 连续若干 tick 无新行」时才查 state（启发式：日志停了大概率任务结束了）。被否——长任务静默期会误判，且无法可靠区分「任务结束」与「任务在跑但没输出」。

**实现提示**：复用现成 `OpsService.findInstance`；若后续压测有需求，可加一个只读 `state` 列的轻量查询（本变更不做）。终态读取无竞态——CAS 在 worker 侧已先于任何前端读取提交。

### 决策 3：`end` 事件 payload 丰化为 `{state}`，保持非破坏性

- 现：`event:"end", data:""`（`OpsController.endEvent()`）。
- 新：`event:"end", data:"{\"state\":\"SUCCESS\"}"`。`event` 名不变（客户端按它判定结束），仅 `data` 丰化。
- 「连接时已终态」的快照路径 `streamEndedLogs`（其作用域内已有 `inst.getState()`）同样 emit 带结果的 `end`。
- 前端 `LogTab` 解析：`type==="end"` 时 `try { JSON.parse(e.data).state } catch { undefined }`，兼容老的空 data。

唯一已知消费者（当前 `LogTab`）只检查 `event==="end"`，故非破坏。无客户端依赖 `end.data===""`。

### 决策 4：圆点状态语义「混合」——SSE 活着 ⇒ 运行中，终态 outcome 覆盖

引入单一 `RunDotState`，由两个输入合成：

```
  SSE 仍 connected 且未收到终态 outcome ──▶ running   🟢 bg-success animate-pulse
  收到 outcome=SUCCESS                  ──▶ success   🟢 bg-success（稳态）
  收到 outcome=FAILED                   ──▶ failed    🔴 bg-destructive
  收到 outcome=STOPPED                  ──▶ stopped   ⚫ bg-muted-foreground
  正在连接（connecting）                ──▶ connecting🟡 bg-warning animate-pulse
  流报错但无 outcome                    ──▶ 保持 connecting/中性，绝不臆测成败
```

终态 outcome 一旦出现即覆盖（success/failed/stopped 三态），此后不再回退。SSE 连接信号不浪费——它继续承担「是否还在跑」的判定，避免再引入第二个「运行中」数据源。

### 决策 5：配色迁移到语义 token（不强制跨文件抽取共享组件）

将 `task-editor-pane.tsx:625` 的 `dotColor` 值改为语义 token，与 `log-panel.tsx` 的 `StatusDot` 一致（`bg-success`/`bg-warning`/`bg-destructive`/`bg-muted-foreground`）。

**备选**：把 `StatusDot` 抽成共享 `components/ui/status-dot.tsx`，两处共用。被否（本变更内）——两处的 state 集合不同（log-panel 是 `TabConnStatus`，run-tab 是含终态的 `RunDotState`），强行合并会引入联合类型与分支膨胀；先对齐 token，共享抽取留作后续可选清理。设计上预留：两处 token 表一致，未来抽取无障碍。

### 决策 6：可访问性——圆点加原生 `title` tooltip

圆点视觉 span 保持 `aria-hidden`，但在 `indicator` 外层 span 加 `title={t(stateLabelKey)}`，复用既有 i18n state 文案（`stateRunning`/`stateSuccess`/`stateFailed`/`stateStopped`，均已在 `frontend/messages/*.json` 存在）。悬停即可知含义，AT 用户亦可达。**无需新增任何 i18n key。**

## Risks / Trade-offs

- **[live 路径状态查询带来 DB 读]** → 每 ~2s 一次 `findInstance`（非每日志 tick）；日志轮询保持亚秒级不受影响。若压测有问题可换轻量 state 列查询（本变更不做）。
- **[终态读取与尾日志落盘的竞态]** → 检出终态后先排空 logBus 再 emit `end`，避免丢尾日志。CAS 终态写入先于前端读取，无状态竞态。
- **[live 流现在会按终态关闭，改变了「永不断」的既有行为]** → 这正是要修的 bug（连接悬挂）；非回归。旧客户端若假设流永不断，其 `end` 处理路径本就已存在（快照路径一直在 emit `end`），无新破坏面。
- **[混合语义比纯实例状态略复杂]** → 但复用了既有 live 信号判定「运行中」，避免引入冗余数据源；复杂度可接受。
- **[`end.data` 丰化的向前兼容]** → 仅丰化 `data`，`event` 名不变；唯一已知消费者只看 `event==="end"`。无破坏。

## Migration Plan

1. 后端先行：改 `OpsController.logStream` live 路径（状态节流查询 + 终态 `end{state}` + complete）与 `streamEndedLogs`/`endEvent`（带 state）。老前端部署下：仍只看 `event==="end"`，行为不变（只是依旧区分不出成败——无回归）。
2. 前端跟进：`LogTab` 解析 `end.data.state` → `RunLogsTabs` 合成 `RunDotState` → 圆点配色迁移 + tooltip。
3. 无 DB migration、无新端点、无依赖变更。回滚：还原 `OpsController.logStream` live 路径状态分支与 `endEvent` 的 data；前端还原圆点逻辑。两端均可独立回滚。

## Open Questions

- 状态查询节拍（暂定 ~2s）——实现时按日志延迟/DB 负载实测微调。
- 是否把 `StatusDot` 抽成共享组件——本变更不做，留作后续可选清理（决策 5）。
- `realtime-streams` spec 声称的「工作流状态实时流推送」与 impl 死管道的既有缺口——独立议题，不在本变更内。

## 相关已知问题（范围外）

`realtime-streams` 的「工作流状态实时流」requirement 声称 task_instance 状态变化 SHALL 发布到事件流，但 `dw:evt:{workflowInstanceId}` 通道在全代码库零 publisher（仅 `dw:wake` 调度内部信号在用）。这是 spec/impl 既有的不对齐，画布节点实时变色目前可能不工作。本变更**不**修它——本变更的终态结果走日志流 `end` 事件，与该事件流通道无关。建议作为独立 change 跟进。
