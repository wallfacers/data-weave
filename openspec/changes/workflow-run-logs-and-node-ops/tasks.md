## 1. 核对数据通路（前置）

- [x] 1.1 核对工作流实例详情接口返回结构：tasks 含 `id(实例UUID)/taskDefId/taskDefName/state/...`，**无 nodeKey**。决定按 taskDefId 映射（与现有着色键一致），后端零改动
- [x] 1.2 现有 `instanceToTaskDef`(UUID→taskDefId) 够用；新增派生 `taskDefToInstance`(taskDefId→UUID) 供节点取日志流

## 2. 抽取公共日志组件

- [x] 2.1 新建 `frontend/components/workspace/run-logs-tabs.tsx`：迁出 `RunTab` 类型、`LogTab`、`RunLogsTabs`，并抽出状态 hook `useRunLogTabs`（关闭族 + 拖拽高度 + openRunTab 去重激活）
- [x] 2.2 `task-editor-pane.tsx` 改为 import 公共组件/hook，删除内联实现，对外行为不变
- [x] 2.3 `cd frontend && pnpm typecheck` 通过
- [ ] 2.4 浏览器实跑任务编辑器运行一次，确认日志 Tab 渲染/滚屏/续传/关闭零回归（并入 7.2 一次性验证）

## 3. 画布接入日志 Tabs 区

- [x] 3.1 在 `workflow-canvas-view.tsx` 底部接入 `RunLogsTabs` 区（拖拽调高、高度持久化 localStorage，照搬任务编辑器布局）
- [x] 3.2 实现 openRunTab（hook 内）+ `taskDefToInstance` 派生：节点 → 实例 UUID → 建 `RunTab{kind:"log"}`，去重（instanceId 为键），无 Tab 时隐藏日志区
- [x] 3.3 运行时自动顶日志：监听 events/stream，节点进入 RUNNING/DISPATCHED 且未自动开过时建 Tab 并首次抢焦（autoOpenedRef 去重，已存在不打断）

## 4. 节点右键菜单

- [x] 4.1 用既有 `<ContextMenu>` 给 TaskNode/VirtualNode 包右键菜单（经 NodeActionsContext 下发动作）
- [x] 4.2 「查看日志」菜单项 → onViewLog（canViewLog=taskDefToInstance.has 时可用，否则置灰）
- [x] 4.3 「单独运行」菜单项 → onRunNode 调 `POST /api/tasks/{taskId}/run`，按 outcome 分支（EXECUTED 开 Tab / PENDING_APPROVAL / 未执行 / 失败 提示）
- [x] 4.4 「删除节点」菜单项 → deleteElements 走既有 remove 分支置脏
- [x] 4.5 VIRTUAL 节点：菜单仅「删除节点」；TASK 节点「查看日志/单独运行」对 taskId==null 置灰

## 5. 边操作与删除键

- [x] 5.1 React Flow 配置 `deleteKeyCode={["Backspace","Delete"]}`，选中节点/边按两键均删除并经 remove 分支落草稿
- [x] 5.2 边右键浮层「删除」项（onEdgeContextMenu + click-away 关闭，deleteElements 删边置脏）

## 6. i18n

- [x] 6.1 在 `frontend/messages/{zh-CN,en-US}.json` 新增 9 个画布菜单/日志键，`pnpm i18n:lint` 通过（566 keys × zh/en 一致）
- [x] 6.2 无 `…` 进行中省略号、无中文兜底裸串，文案全走 next-intl 键

## 6b. 后端补缺：状态事件发布（apply 阶段发现 dw:evt 频道从无发布者）

- [x] 6b.1 `InstanceStateMachine` 注入 `EventBus`，各 task CAS（casTaskState/casDispatch/casTaskTerminal/casPreempt/casRequeue）成功后发布 `dw:evt:{workflowInstanceId}` 的 `{taskId,taskState}`；单跑实例（workflow_instance_id 为 null）跳过
- [x] 6b.2 `casWorkflowState` 成功后发布 `{workflowState}`（供实例详情视图）
- [x] 6b.3 `WorkerReportService` 失败级联置 STOPPED（绕过状态机的批量 UPDATE）后逐条补发事件
- [x] 6b.4 `./mvnw -pl dataweave-master compile` 通过；`./mvnw install -DskipTests` 通过
- [x] 6b.5 curl 实证：慢任务工作流运行时 events/stream 输出 `{"taskId":…,"taskState":"SUCCESS"}`（修复前 12s 零负载）

## 6c. 停止运行（任务 + 工作流）

- [x] 6c.1 后端 `OpsService.killTask/killWorkflow` 改走 `InstanceStateMachine.casTaskTerminal`（发 STOPPED 事件→画布节点实时变红）+ `casWorkflowState` 发 workflowState；不再直存 repository
- [x] 6c.2 停止时往各实例日志流插「=========== 手动停止 ===========」横幅行（LogBus.append，前端 LogTab 按 === 弱化着色）
- [x] 6c.3 任务编辑器：运行中显示「停止」按钮（停当前激活日志 Tab 的实例，POST /api/ops/task-instances/{id}/kill）
- [x] 6c.4 工作流画布：运行中显示「停止」按钮（POST /api/ops/instances/{id}/kill）+ 整体运行徽标（运行中/成功/失败/已停止，由 workflowState 事件驱动）
- [x] 6c.5 i18n：taskEditor/workflowCanvas 新增 stop/stopping/toastStopped/toastStopFailed + 画布 runState* 标签（zh/en 等量）
- [~] 6c.6 浏览器验证：本轮按用户要求跳过（多人并发跑任务/改码，不重启后端、不跑 typecheck）；停止链路 = 既有 kill 端点 + 已实证的 events/logs 流，逻辑同构

## 7. 验收

- [x] 7.1 `pnpm typecheck` 通过；`pnpm build` 通过
- [x] 7.2 Browser Verification Gate（admin/admin，H2 后端，含 6b 后端 fix）：
  - [x] 节点右键菜单 3 项（View logs 运行前置灰 / Run node / Delete node）
  - [x] 「单独运行」→ 自动开日志 Tab + **真实流式日志**（logs/stream 正常）
  - [x] 日志 Tab 渲染/滚屏/banner 着色/连接态（共享组件，Run node 实证）
  - [x] 边右键「Delete edge」删除 + 置脏；Delete 键删边；Backspace 删节点
  - [x] 虚拟节点菜单仅「Delete node」
  - [x] **运行时自动顶日志**：慢任务(sleep7)工作流运行 → 自动弹出「慢节点 · 时间」日志 Tab 并激活、流式真实日志、运行态点显示 Succeeded（后端补发事件后端到端通过）
  - [x] 「查看日志」右键运行后变为可用（taskDefToInstance 已填充）
  - [x] console 0 errors
- [x] 7.3 截图存 `tmp/` 验证后已清理；`.playwright-cli` 已清
