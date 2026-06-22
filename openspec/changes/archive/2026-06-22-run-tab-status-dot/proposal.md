## Why

数据开发编辑子 Tab 里「每次运行一个日志 Tab」左侧的状态圆点，目前反映的是 **SSE 日志流的连接状态**（live/ended/error/connecting），不是任务实例的执行结果。后果：一次**成功**的运行和一次**失败**的运行，在流关闭后都显示成同一个灰色 `ended`——用户扫一眼 Tab 根本分不清「远程完成」还是「运行错误」。

根因有二：① 后端没有任何 push 通道携带实例的终态结果（日志流 `end` 事件 `data:""` 纯流关闭信号；`workflow-instances/{id}/events/stream` 的通道 `dw:evt:{id}` 全代码库零 publisher，是死管道）；② 日志流的 live 路径任务结束后**根本不 emit `end`、也不关流**（`OpsController.logStream` 的 `Flux.interval` 只轮询 logBus），这是一个独立潜伏 bug——实时观看的运行走不到干净的「已结束」态，SSE 连接还可能悬挂不释放。

本变更让圆点反映实例真实状态（RUNNING/SUCCESS/FAILED/STOPPED），并顺带修掉 live 流永不结束的 bug。

## What Changes

- **后端日志流携带终态结果并按终态关闭**（`realtime-streams`）：`/api/ops/instances/{id}/logs/stream` 的 live 路径 SHALL 每个 tick 顺带读一次实例 `state`；当实例进入终态（SUCCESS/FAILED/STOPPED）时，emit 一个携带结果的 `end` 事件（`data: {"state":"SUCCESS"}`）并 complete 流。已终态连接路径的 `end` 事件同样携带结果。非破坏性契约增强：只检查 `event==="end"` 的客户端不受影响。
- **运行 Tab 圆点反映实例状态**（`data-development-ide`）：运行 Tab 左侧圆点 SHALL 反映实例状态而非连接状态——RUNNING=绿色脉冲、SUCCESS=绿色稳态（远程完成）、FAILED=红色（运行错误）、STOPPED=灰色（已终止）。语义混合：SSE 仍连着 ⇒ RUNNING；收到终态 outcome ⇒ 终态颜色覆盖。
- **配色统一**：圆点硬编码的 `bg-emerald-500`/`bg-amber-500` 迁移到语义 token（`bg-success`/`bg-warning`/`bg-destructive`），与 `log-panel.tsx` 的 `StatusDot` 一致（DESIGN.md 规范）。
- **可访问性**：圆点加 tooltip/`title`（复用既有 `stateRunning`/`stateSuccess`/`stateFailed` 文案，`stateStopped` 若缺则补）。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `realtime-streams`: 日志流契约增强——live 路径按终态关闭并在 `end` 事件携带实例终态结果；新增「日志流按终态关闭并携带结果」requirement（挂在既有「任务日志实时管道 / 前端日志滚屏」之上）。
- `data-development-ide`: 编辑子 Tab 的运行 Tab 状态圆点反映实例状态——新增「运行 Tab 状态圆点反映实例状态」requirement（挂在既有「就地观测」之上）。

## Impact

- **后端**：`dataweave-api` 的 `OpsController.logStream`（interfaces 层）——live 路径 tick 内加一次 `OpsService.findInstance` 状态查询、终态时 emit 带 outcome 的 `end` 并 complete；复用现成 `OpsService.findInstance`，无新依赖。tick 频率/state 查询节流策略见 design.md。
- **前端**：`frontend/components/workspace/task-editor-pane.tsx` 的 `RunLogsTabs`/`LogTab`——解析终态 outcome、混合圆点语义、配色迁移；考虑抽取/复用 `log-panel.tsx` 的 `StatusDot`。
- **i18n**：`frontend/messages/{zh-CN,en-US}.json`——`stateStopped` 若缺则补双语；圆点 tooltip 复用既有 state 文案（ICU 占位符、双 bundle 同 key 集）。
- **测试**：后端 SSE 测试（终态时 emit 带 state 的 `end` + 流关闭）；前端 vitest（state→颜色映射、终态覆盖 live）。
- **契约影响**：仅 `end` 事件 data 由 `""` 丰化为 JSON state 字符串，非破坏性。无 DB schema 变更、无新 API 路径、无新依赖。
- **范围外**：`workflow-instances/{id}/events/stream` 的死管道（spec 声称会推送状态变迁但 impl 零 publisher）是既有的 spec/impl 缺口，本变更不修——见 design.md「相关已知问题」。
