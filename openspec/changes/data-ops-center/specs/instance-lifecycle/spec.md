## ADDED Requirements

### Requirement: 实例置成功状态转移

实例状态机 SHALL 支持 `set-success` 转移:从 FAILED/STOPPED/RUNNING/PREEMPTED 经乐观 CAS(`WHERE state=?`)推进为 SUCCESS,并触发下游 WAITING 实例的就绪重算与唤醒;NOT_RUN/WAITING 不可置成功。事务内只落状态,唤醒/下发在事务外。

#### Scenario: 失败态置成功
- **WHEN** 对 FAILED 实例执行 set-success
- **THEN** CAS 推进为 SUCCESS,下游 WAITING 被唤醒重评

#### Scenario: 运行态置成功不破坏不变量
- **WHEN** 对 RUNNING 实例 set-success
- **THEN** 状态在事务内 CAS 落 SUCCESS,`dw:wake` 唤醒在事务外发出,不持锁等待

#### Scenario: 非运行态拒绝
- **WHEN** 对 NOT_RUN/WAITING 实例 set-success
- **THEN** 拒绝,状态不变

### Requirement: 实例批量操作语义

系统 SHALL 支持对一组实例批量执行 rerun/kill/set-success,每个实例独立经状态机转移与闸门裁决,逐项汇总结果,部分待批不影响其余项执行。

#### Scenario: 批量逐项独立裁决
- **WHEN** 批量 set-success 一组实例,部分命中 L2 策略
- **THEN** 命中项 PENDING_APPROVAL,其余 EXECUTED,各自状态转移互不阻塞
