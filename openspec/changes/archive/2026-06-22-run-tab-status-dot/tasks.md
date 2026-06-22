## 1. 后端日志流终态语义

- [x] 1.1 改 `OpsController.endEvent()` 接受 `state` 参数，emit `event:"end"` + `data: "{\"state\":\"<InstanceStates>\"}"`（event 名保持 `end` 不变）
- [x] 1.2 「连接时已终态」快照路径 `streamEndedLogs` 调用 `endEvent` 时传入 `inst.getState()`（其作用域内已有该值）
- [x] 1.3 `logStream` live 路径（实例连接时仍 RUNNING）：并入一个独立节拍（约 2s）的 `opsService.findInstance(id)` 状态查询；日志轮询保持亚秒级不受影响
- [x] 1.4 live 路径检出终态时：先排空 logBus 尾部日志（避免丢尾）→ emit 带 state 的 `end` → complete 流
- [x] 1.5 复用现成 `OpsService.findInstance`，确认无新依赖、无新 REST 端点；`cd backend && ./mvnw -q -pl dataweave-api compile` 零错误

## 2. 后端测试

- [x] 2.1 SSE 测试：实例 RUNNING 连接 → 推进至 SUCCESS 终态 → 断言 emit `event:"end"` 且 `data` 含 `{"state":"SUCCESS"}` 且流关闭（WebTestClient / `@SpringBootTest`）
- [x] 2.2 测试：连接时实例已 FAILED → 断言回放归档日志后 `end.data.state == "FAILED"`
- [x] 2.3 测试：实例持续 RUNNING 且有日志输出时，流持续推 `log`、不 emit `end`、不关闭
- [x] 2.4 测试：终态为 STOPPED → `end.data.state == "STOPPED"`
- [x] 2.5 `cd backend && ./mvnw -q -pl dataweave-api test`（相关测试类）通过 — **6/6 通过**

## 3. 前端圆点状态合成与配色

- [x] 3.1 `LogTab`（`run-logs-tabs.tsx`）：解析 `end` 事件 `data` 中的 `state`（`try { JSON.parse } catch` 容错空/旧负载），经回调把终态 outcome 上抛给 `RunLogsTabs`
- [x] 3.2 `RunLogsTabs`：把单一 `ConnState` 升级为合成的 `RunDotState`（running/success/failed/stopped/connecting）——SSE 连接且未收到终态=running；终态 outcome 覆盖（success/failed/stopped）且不再回退
- [x] 3.3 圆点配色迁移到语义 token：running→`bg-success animate-pulse`、success→`bg-success`、failed→`bg-destructive`、stopped→`bg-muted-foreground`、connecting→`bg-warning animate-pulse`（对齐 `log-panel.tsx` 的 `StatusDot`）
- [x] 3.4 圆点加原生 `title` tooltip，复用既有 i18n key（`instanceTable.stateRunning/stateSuccess/stateFailed/stateStopped` + `taskEditor.logConnectingShort`，双 bundle 均已存在）
- [x] 3.5 纯派生逻辑 + 颜色映射抽到 `lib/workspace/run-dot-state.ts`（`parseEndState`/`deriveRunDotState`/`runDotColor`），组件引用，便于单测

## 4. 前端测试

- [x] 4.1 vitest：`RunDotState` → 圆点 class 映射（running 脉冲/success 稳态/failed/stopped/connecting 脉冲）
- [x] 4.2 vitest：终态 outcome 覆盖连接态、终态后不回退为 running
- [x] 4.3 vitest：`end.data` 为空或非 JSON 旧负载时不崩溃、不误判终态（`parseEndState` 容错）
- [x] 4.4 `cd frontend && pnpm typecheck` 零错误 — **通过**；vitest **3 文件 33 测试全过**

## 5. 验收门（含浏览器实证）

- [x] 5.1 i18n 校验：双 bundle key 集一致（566 keys × zh/en）、`stateStopped` 等复用 key 齐备；`pnpm i18n:lint` 查 zh-only/en-only 为空、无硬编码中文（本变更未新增 key）— **通过**
- [x] 5.3 后端 SSE 终态契约线上验证：后端运行时 curl 终态实例 `/logs/stream` 实测末事件为 `event:end` + `data:{"state":"SUCCESS"}`（改前为 `data:""`）；流随即关闭（curl 返回）；后端测试 `live路径_终态时emit带outcome的end并关流` 断言 `verifyComplete()`（flux 关闭、无悬挂）— **通过**（DevTools Network 视觉观测因 5.2 环境阻塞未单独做，但行为已由后端测试 + curl 双证）
- [ ] 5.2 浏览器视觉门（圆点变色）：**阻塞**——你的未提交 `workflow-canvas-view.tsx`（+240 行画布变色 WIP）有 SWC 语法错（裸 `}`），数据开发视图加载失败（console: `workflow-canvas-view.tsx:825 Unexpected token`），进不去任务编辑器/运行 Tab；且后端多次被重启/重编到不可达。待你修好该文件 + 稳定后端，我即可用 playwright 实证（token+user 注入登录已打通、playwright v1.59.1 + chromium 就绪）
- [ ] 5.4 浏览器视觉门（tooltip/locale）：**阻塞**（同 5.2）。注：App 整体加载 0 console 报错（`pw-recon2`），仅你的 canvas WIP 文件报错
