## MODIFIED Requirements

### Requirement: 工作流状态实时流

workflow_instance 与其下 task_instance 的每次状态变化 SHALL 发布到事件流，前端经 SSE 订阅后实时刷新任务流视图（节点状态逐个变化），范式与日志流一致。

发布契约（本次补全 —— 此前实现侧无任何发布者，频道恒空）：所有 task_instance 状态推进 MUST 经 `InstanceStateMachine` 的 CAS 方法，且每次 CAS 成功后 MUST 向频道 `dw:evt:{workflowInstanceId}` 发布一条事件名为 `status`、data 为 `{"taskId":"<task_instance UUID>","taskState":"<新状态>"}` 的消息；workflow_instance 状态推进 MUST 向同名频道发布 `{"workflowState":"<新状态>"}`。脱离工作流的单跑实例（`workflow_instance_id` 为 null）MUST 跳过发布。绕过状态机的批量状态更新（如失败级联置 STOPPED）MUST 在更新处补发等价事件。事件仅作 UI 辅助，发布失败或事务回滚导致的偶发误差由前端下次拉取自愈，MUST NOT 影响状态机的 CAS 纪律。

#### Scenario: DAG 节点状态实时变化

- **WHEN** 一条流的某节点从 RUNNING 变为 SUCCESS、下游节点开始 RUNNING
- **THEN** 前端任务流视图在亚秒级内反映两个节点的状态变化，无需轮询

#### Scenario: 任务状态变迁发布到工作流事件频道

- **WHEN** 某 task_instance 经 `InstanceStateMachine` CAS 从 DISPATCHED 变为 RUNNING（其 workflow_instance_id 非空）
- **THEN** 系统向 `dw:evt:{workflowInstanceId}` 发布 `{"taskId":"<该实例UUID>","taskState":"RUNNING"}`，订阅 events/stream 的画布据此给对应节点变色并自动顶起该节点日志 Tab

#### Scenario: 单跑实例不发布工作流事件

- **WHEN** 一个 `workflow_instance_id` 为 null 的单跑（含画布「单独运行」）任务实例发生状态变迁
- **THEN** 系统不向任何 `dw:evt:` 频道发布（其日志经 logs/stream 单独订阅，不依赖工作流事件流）
