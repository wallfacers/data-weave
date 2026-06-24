## 1. 前置与开放问题收口

- [x] 1.1 确认 `data-ops-center` 归档顺序:不阻塞写代码,记为本变更 archive 前的流程项(先归档 data-ops-center)
- [x] 1.2 冻结作用域 = **定义级 + 实例级都要** → `workflow_node_freeze` 带可空 `instance_id`
- [x] 1.3 冻结级联 **穿透弱依赖**(下游照跳);并核实常态弱依赖:发现手动停止(STOPPED)误放行下游的 bug,纳入 3.x 修正
- [x] 1.4 `task_def.frozen` **直接弃用**(seed 无 frozen=1,无历史数据),保留列以便回滚

## 2. 后端数据模型与迁移

- [x] 2.1 新增 `workflow_node_freeze` 表 DDL(键 `workflow_id`+`node_key`+可空 `instance_id`、`frozen`、审计列),PG 与 H2 双兼容
- [x] 2.2 新增 `WorkflowNodeFreeze` 实体 + Repository(Spring Data JDBC)
- [x] 2.3 退役 `task_def.frozen` 调度门(保留列以便回滚);无 frozen=1 种子,无需迁移脚本
- [x] 2.4 新增错误码 `workflow.node_task_not_online`、`task.referenced_by_online_workflow` 到 backend `Messages` 双语

## 3. 后端运维查询 ONLINE 收口(D1)

- [x] 3.1 `OpsService` 任务流列表查询加 `status=ONLINE` 过滤,按 `schedule_type` 分流 `periodicWorkflows()`(CRON)/`manualWorkflows()`(MANUAL)
- [x] 3.2 实例查询排除 `TEST` 来源(`instances()` 仅 NORMAL;认领 SQL `run_mode IN ('NORMAL','BACKFILL')`)——运维侧不返回开发态自测实例
- [x] 3.3 废弃 `OpsService.tasks()` 全量 `findAll`(标 @Deprecated);`/api/ops/tasks` 端点标弃用,新增工作流列表端点取代
- [x] 3.4 `OpsController` 暴露 `GET /api/ops/periodic-workflows`、`GET /api/ops/manual-workflows`
- [x] 3.5 修正弱依赖就绪(`SchedulerKernel.selectRunnable` NORMAL 分支):弱依赖就绪集 `('SUCCESS','FAILED','STOPPED')`→`('SUCCESS','FAILED')`,手动停止不放行下游

## 4. 后端节点冻结(D2/D3)

- [x] 4.1 新增 `NodeFreezeService`:freeze/unfreeze overlay 写入(定义级/实例级),实例级即时级联 SKIPPED;经 `GatedActionService` 闸门留痕(controller 前置)
- [x] 4.2 `OpsController` 新增 `POST /api/ops/workflows/{workflowId}/nodes/{nodeKey}/freeze`,下线 `/api/ops/tasks/{id}/freeze`
- [x] 4.3 调度物化(`WorkflowTriggerService` 从 `dag_snapshot_json` 物化处)叠加定义级 overlay:冻结节点标 `SKIPPED` + 新增 `InstanceStates.SKIPPED` 终态 + `WorkflowStateService` 聚合
- [x] 4.4 级联跳过实现:`downstreamClosure` 沿出边把冻结节点的传递下游闭包标 `SKIPPED`,**穿透弱依赖**(1.3 定:下游照跳)
- [x] 4.5 守住调度不变式:overlay 在物化期一次性读、实例级走状态机 CAS(WAITING→SKIPPED),不改认领 SKIP LOCKED/锁序

## 5. 后端引用完整性(D4)

- [x] 5.1 `WorkflowService.publish()` 在无环校验旁加:遍历 TASK 节点,任一引用任务 `status!=ONLINE` 拒绝 `workflow.node_task_not_online`(列出节点)
- [x] 5.2 `TaskService.offline()` 前置校验:存在 `task_id=本任务` 且所属 workflow `status=ONLINE` 的 `WorkflowNode` 引用即拒绝 `task.referenced_by_online_workflow`(列出工作流)

## 5b. MCP 工具（留待全局统一重写）

- [x] 5b.1 MCP `freeze_task` 工具加 TODO 标记(已退役/待重写为 `freeze_node`)——按用户要求暂留,全局梳理时统一改;调度门已退役故无副作用

## 6. 前端运维中心 tab 重排(D5)

- [x] 6.1 `ops-view.tsx` tab 改为「周期任务流列表/手动任务流列表/任务流实例/补数据实例」,移除「手动·测试」
- [x] 6.2 `periodic-tasks-panel`→`periodic-workflows-panel`:数据源改 `GET /api/ops/periodic-workflows`,移除任务级 freeze 开关,「查看 DAG」入口进画布
- [x] 6.3 新增 `manual-workflows-panel`:`GET /api/ops/manual-workflows` + 「运行一次」(`POST /api/workflows/{id}/run`,按 outcome 分流)
- [x] 6.4 删除 `manual-tests-panel.tsx`+`periodic-tasks-panel.tsx`;TEST_RUN 自测归数据开发侧
- [ ] 6.5 任务流实例视图:手动补跑作为动作(重跑/补跑入口),手动来源实例按 `runMode` 可筛 — **DEFERRED**(后续 UI 增强;实例视图既有重跑入口已在)
- [ ] 6.6 DAG 实例视图节点级冻结/解冻入口 + 级联 SKIPPED 可视化 — **DEFERRED**(ReactFlow 节点右键菜单接 `/nodes/{nodeKey}/freeze`;后端已全支持)

## 7. 前端开发侧(画布与下线)

- [x] 7.1 画布对引用未发布(DRAFT)任务的 TASK 节点渲染「未发布」标记 + 黄色虚线边框；`DagNodeDto` 增 `taskStatus` 透传
- [x] 7.2 发布被 `workflow.node_task_not_online` 拒绝时:错误 toast(后端本地化,列出节点)+ 未发布节点持续以标记可辨
- [x] 7.3 `task-editor-pane` 下线按钮:`TaskDetail` 增 `referencedByOnlineWorkflows`,非空时禁用按钮 + title 提示引用工作流名单

## 8. i18n

- [x] 8.1 前端 next-intl 新增 copy(周期/手动任务流列表、查看 DAG、运行一次等)`zh-CN`/`en-US` 各 +16 键
- [x] 8.2 校验:`i18n:lint` 键集一致(921×zh/en);后端错误码双语齐全(注:`periodic-instances-panel` cron 描述有 4 处既有硬编码中文残留,非本次引入,留待清理)

## 9. 测试与验证

- [ ] 9.1 后端单测:运维查询 ONLINE 过滤、CRON/MANUAL 分流 — **DEFERRED**(H2 集成;已浏览器实证 `/periodic-workflows` 仅返 ONLINE CRON、`/manual-workflows` 返 ONLINE MANUAL)
- [ ] 9.2 后端单测:发布拦截未发布节点、被引用任务禁止下线 — **DEFERRED**(H2 集成;逻辑已实现)
- [x] 9.3 后端单测:节点冻结级联 + SKIPPED 聚合 — `NodeFreezeCascadeTest` 6/6 通过
- [x] 9.4 浏览器验证门:四 tab 渲染 ✓、手动任务流空态 ✓、周期任务流 ONLINE 列表 ✓、「查看 DAG」开画布(6 节点)✓、`taskStatus`/`referencedByOnlineWorkflows` 字段实证 ✓、0 console error ✓(未发布徽标因 seed 全 ONLINE 无可视样本,代码路径已验;DAG 节点冻结 UI=6.6 仍 DEFERRED)
