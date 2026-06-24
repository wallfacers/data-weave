## 1. 后端:最近活跃实例查询

- [x] 1.1 `TaskInstanceRepository` 加 `Optional<TaskInstance> findFirstByTaskIdAndRunModeOrderByIdDesc(Long taskId, String runMode)`
- [x] 1.2 `WorkflowInstanceRepository` 加 `Optional<WorkflowInstance> findFirstByWorkflowIdOrderByIdDesc(Long workflowId)`
- [x] 1.3 `OpsService` 加薄包装查询方法(latestTaskInstance(taskDefId, runMode 默认 NORMAL)、latestWorkflowInstance(workflowId))
- [x] 1.4 `OpsController` 加 `GET /api/ops/tasks/{taskDefId}/latest-instance?runMode=` 返回 `{id,state,runMode}`(无则 data=null)
- [x] 1.5 `OpsController` 加 `GET /api/ops/workflows/{workflowId}/latest-instance` 返回 `{id,state}`(无则 data=null)
- [x] 1.6 `cd backend && ./mvnw -q -pl dataweave-master,dataweave-api -am compile` 确认零编译错误

## 2. 后端:测试

- [x] 2.1 Repository 单测:NORMAL/TEST 过滤、取最新(UUIDv7 id DESC)、无实例返回空(经 OpsLatestInstanceTest 在 H2 验证;PG 未跑——本环境无 Docker,派生查询方言无关)
- [x] 2.2 OpsController WebTestClient 单测(带 JwtTestSupport Bearer):任务/工作流最近实例 200+$.data 契约、runMode 过滤、无实例 data=null、未鉴权 401
- [x] 2.3 `./mvnw -pl dataweave-master,dataweave-api -am install -DskipTests` 使运行进程加载新类

## 3. 前端:运行态来源上提

- [x] 3.1 `run-logs-tabs.tsx` 的 `RunLogsTabs` 加可选 `onDotChange?(dot: Record<string, RunDotState>)`,在内部 `dot` 变更时上报(向后兼容)
- [x] 3.2 导出/复用终态判定:`run-dot-state.ts` 或同处提供 `isTerminalDotState` / `isTerminalInstanceState`(SUCCESS/FAILED/STOPPED 为终态)
- [x] 3.3 `cd frontend && pnpm typecheck`

## 4. 前端:任务编辑器单按钮 + 续接

- [x] 4.1 `task-editor-pane.tsx` 运行按钮图标 `RocketIcon`→`PlayIcon`
- [x] 4.2 引入 `currentRunInstanceId` 状态;`handleRun` EXECUTED 时同时设置它
- [x] 4.3 接收 `RunLogsTabs` 上提的 dot map,派生按钮态;合并为单按钮:非终态显示「停止」(StopIcon)、终态/无实例显示「运行/试跑」(PlayIcon)
- [x] 4.4 「停止」改为 kill `currentRunInstanceId`(而非 `activeRunTab`);关运行中 Tab 即放弃跟踪(currentRunInstanceId 对应 tab 不存在则按钮回落)
- [x] 4.5 挂载续接:`loadTask` 后查 `GET /api/ops/tasks/{taskId}/latest-instance`(NORMAL),非终态则 setCurrentRunInstanceId + openRunTab 续流;终态/无则不开 Tab
- [x] 4.6 `lib/types.ts` 加 latest-instance 返回类型;运维查询调用收口到合适的 api 模块
- [x] 4.7 `pnpm typecheck`

## 5. 前端:工作流画布单按钮 + 续接

- [x] 5.1 `workflow-canvas-view.tsx` 运行按钮图标 `RocketIcon`→`PlayIcon`(发布按钮 RocketIcon 保留不动)
- [x] 5.2 把 `runWorkflow` 内「setRunWfId + 拉 workflow-instances/{id} 建映射 + setRunStateByTaskDef + setRunStatus + 自动顶日志」抽成 `attachRunningInstance(wiId, state)`,首跑与续接共用
- [x] 5.3 运行按钮按 `runStatus`/`canStop` 合并为单按钮 Run⇄Stop(复用既有 canStop 判定)
- [x] 5.4 挂载续接:查 `GET /api/ops/workflows/{workflowId}/latest-instance`,非终态则 `attachRunningInstance` 续订阅事件流 + 节点重新着色
- [x] 5.5 `pnpm typecheck`

## 6. i18n 与验证

- [x] 6.1 核对按钮文案复用既有键;如新增文案则 zh-CN/en-US 双 bundle 同步,无 zh-only/en-only 缺键
- [x] 6.2 Browser Verification Gate:实跑长任务,验证 运行→停止切换、停止生效
- [x] 6.3 Browser Verification Gate:刷新页面重开任务,验证按钮续显「停止」+ 日志流续上;重开终态任务验证按钮为「运行」且不弹历史日志
- [x] 6.4 Browser Verification Gate:工作流运行中刷新重开画布,验证节点着色 + 整体徽标 + 停止按钮续接;截图入 tmp/ 后清理
- [x] 6.5 控制台无错误,SSE 直连后端(SSE_BASE)续传正常
