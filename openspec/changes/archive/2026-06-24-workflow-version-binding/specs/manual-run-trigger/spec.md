## MODIFIED Requirements

### Requirement: 工作流手动触发正式实例

系统 SHALL 提供 `POST /api/workflows/{id}/run`，对一个**已上线**工作流即时起一个正式 workflow_instance。实现 MUST **薄包装现成的** `WorkflowTriggerService.trigger(wf, "MANUAL", bizDate, null)`（`workflow_instance.trigger_type` 列已存在，传 `"MANUAL"`），不另起平行触发服务。触发物化 MUST 以该工作流 `current_version_no` 对应的 `dag_snapshot_json` 为唯一真相源——节点拓扑与各节点 `task_version_no` 取自快照，不读 live `workflow_node`/`task_def.current_version_no`（见 `workflow-version-binding` 能力）；`workflow_instance.workflow_version_no` 等于所物化的快照版本号。实例创建与下发 MUST 遵守既有调度死锁防御不变量（认领用 SKIP LOCKED、状态推进用乐观 CAS、锁序 task→workflow、事务内只落库 HTTP 下发在事务外）。接口 MUST 返回 `workflowInstanceId`，供前端订阅 DAG 事件流给节点变色。

#### Scenario: 手动起正式工作流实例

- **WHEN** 用户对一个已上线工作流 `POST /api/workflows/{id}/run`
- **THEN** 系统按当前发布版本的快照物化（拓扑与各节点 task 版本钉死）创建一个 `trigger_type=MANUAL` 的 workflow_instance 并交由 `SchedulerKernel` 调度，返回 `workflowInstanceId`

#### Scenario: 手动工作流实例可被事件流观测

- **WHEN** 手动触发的工作流实例开始执行
- **THEN** `/api/ops/workflow-instances/{id}/events/stream` 推送其节点状态变迁事件

#### Scenario: 未上线工作流拒绝手动正式触发

- **WHEN** 用户对一个无已发布版本的工作流请求手动正式运行
- **THEN** 系统拒绝并提示需先发布上线

#### Scenario: 手动正式运行不受未晋级的任务新版影响

- **WHEN** 工作流某节点任务在工作流发布后又发了新版（未重新晋级），用户手动正式运行该工作流
- **THEN** 系统按快照钉死的旧版本物化该节点，不自动采用任务新版
