# scheduler-metrics Delta

## ADDED Requirements

### Requirement: 调度性能指标

系统 SHALL 采集并暴露：调度延迟（实例可运行→DISPATCHED）与下发延迟（DISPATCHED→worker 启动）的 p50/p99/p999 分布、当前队列深度、最长等待者年龄、调度吞吐（dispatch/s、completion/s）、单轮调度耗时、DAG 状态聚合耗时、`SKIP LOCKED` 空抢率。

#### Scenario: 调度延迟可观测

- **WHEN** 运维查询调度性能指标
- **THEN** 可看到调度延迟 p50/p99/p999 与当前队列深度的实时值

#### Scenario: 饥饿可被发现

- **WHEN** 某低优先级实例等待超过告警阈值
- **THEN** 最长等待者年龄指标反映该值，可据此告警

### Requirement: 资源与执行指标

系统 SHALL 采集：槽位利用率（全局与每节点）、资源碎片率（有空槽但派不出去的占比）、按任务定义维度的成功率/重试率/超时率、worker 心跳延迟分布、租约过期回收次数。

#### Scenario: 定位惯犯任务

- **WHEN** 运维按失败率排序任务定义维度指标
- **THEN** 可识别出重试率/失败率异常的具体任务

### Requirement: 管道健康指标

系统 SHALL 采集：日志端到端延迟（worker 产生一行→前端可收到）、日志吞吐与 Stream 积压长度、SSE 活跃连接数与重连率、事件唤醒与兜底轮询的调度命中占比。

#### Scenario: 事件通道泄漏预警

- **WHEN** 兜底轮询命中的调度占比持续升高
- **THEN** 指标可见，提示事件总线在丢消息

### Requirement: 业务 SLA 基线

系统 SHALL 按 workflow + biz_date 记录数据就绪时刻（工作流实例完成时间），维护历史基线，并在就绪时间显著晚于基线时产生破线事件供告警模块与 Agent 自诊断消费。SLA 统计 MUST 排除 TEST 实例。

#### Scenario: 出数破线预警

- **WHEN** 某日报工作流基线为 06:00 完成，今日 08:00 仍未完成
- **THEN** 产生 SLA 破线事件，可被告警规则与 Agent 查询到

### Requirement: 指标暴露与查询

调度指标 SHALL 经 Micrometer 注册并通过 actuator 端点暴露；同时提供 `/api/ops/metrics` 查询接口供前端指标看板汇总展示。

#### Scenario: 前端看板取数

- **WHEN** 前端指标看板请求 /api/ops/metrics
- **THEN** 返回四层关键指标的当前汇总值
