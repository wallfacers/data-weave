# Data Model: 虚拟管家监督席

**Feature**: 071-virtual-butler-companion | Schema: 0.20.0 → **0.21.0**(+4 表)

所有表带 `tenant_id`/`project_id` 隔离列 + 索引(036 项目隔离收口约定);DDL 兼容 PG/H2。

## patrol_routine — 巡检例程

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | |
| tenant_id / project_id | VARCHAR | 隔离 |
| domain | VARCHAR(32) | `TASK_FAILURE` / `MACHINE` / `DATA_QUALITY` / `CODE_QUALITY`,项目内唯一 |
| enabled | BOOLEAN default true | 单独启停(FR-006) |
| cron_expression | VARCHAR(64) | 计划频率(seed 默认见 research R7) |
| scope_json | TEXT | 领域范围参数(如目录/标签过滤),JSON |
| timeout_seconds | INT default 120 | 单轮超时 |
| updated_by / updated_at | | 治理审计 |

约束:UNIQUE(project_id, domain)。

## patrol_run — 巡检执行历史(US4 可追溯)

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | |
| tenant_id / project_id | VARCHAR | |
| routine_id | BIGINT FK→patrol_routine | |
| trigger_type | VARCHAR(16) | `SCHEDULED` / `MANUAL` |
| scheduled_fire_time | TIMESTAMP | 计划触发时刻(guard 幂等键的一部分) |
| state | VARCHAR(16) | `CLAIMED` → `RUNNING` → `SUCCEEDED` / `FAILED` / `TIMEOUT` |
| started_at / finished_at | TIMESTAMP | |
| summary | TEXT | 本轮结论摘要 |
| error | TEXT | 失败原因(产出"未完成"汇报时同源) |

约束:UNIQUE(routine_id, scheduled_fire_time) 防重(参照 045 cron 幂等模式);状态推进一律 CAS(`WHERE state=?`,调度不变量②)。

## patrol_report — 巡检汇报(项目级共享)

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | |
| tenant_id / project_id | VARCHAR | |
| run_id | BIGINT FK→patrol_run | 产出来源(执行历史↔汇报关联,US4-AS2) |
| domain | VARCHAR(32) | 冗余自 routine,查询友好 |
| severity | VARCHAR(16) | `DANGER` / `WARN` / `OK` / `INFO`(`INFO` 含"未完成"汇报) |
| title / summary | VARCHAR / TEXT | 标题、摘要(摘要为管家播报文案的来源) |
| detail_json | TEXT | 结构化明细:关联对象列表(type+id+name)、聚合计数、建议动作 |
| aggregate_count | INT default 1 | 同领域聚合窗口内的异常条数(FR-011) |
| status | VARCHAR(16) | `UNREAD` → `READ` → `CLOSED`(**项目级共享**,clarify 决议) |
| closed_by / closed_at | VARCHAR / TIMESTAMP | 关闭人与时间(Key Entities 要求) |
| created_at | TIMESTAMP | |

索引:`(project_id, status, created_at DESC)`(卡片栈查询);关联对象已消失时由 detail_json 中的快照名兜底展示(边界用例)。

## companion_message — 管家会话消息

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | |
| tenant_id / project_id | VARCHAR | |
| report_id | BIGINT NULL FK→patrol_report | NULL=全局会话;非 NULL=锚定该汇报的上下文会话(FR-013) |
| role | VARCHAR(16) | `USER` / `AGENT` / `SYSTEM` |
| actor / actor_name | VARCHAR | 发言者服务端认定(070 身份标准延续) |
| content | TEXT | Markdown 正文 |
| brain_session_id | VARCHAR NULL | 对应 workhorse session(排障/续聊) |
| created_at | TIMESTAMP | |

索引:`(project_id, report_id, created_at)`。

## 派生状态(不落表)

**CompanionState**(服务端归一,SSE `state` 事件推送):

```
alert    ← 项目内存在 status≠CLOSED 且 severity∈{DANGER,WARN} 的 patrol_report
patrol   ← 存在 state=RUNNING 的 patrol_run
think    ← 当前用户会话存在进行中的 brain turn(收到指令未开始回流)
speak    ← brain turn 正在流式输出
idle     ← 其余
优先级:speak > think > alert > patrol > idle(前端只渲染,不推断)
```

**巡检概况**(`snapshot` 事件携带):今日 run 数、未关闭异常数(DANGER+WARN)、启用例程中最近的下次触发时间。

## 状态机

```
patrol_run:    CLAIMED → RUNNING → {SUCCEEDED | FAILED | TIMEOUT}   (CAS 推进,禁回退)
patrol_report: UNREAD → READ → CLOSED                                (单向;CLOSED 终态,不自动重现)
```

## 复用(不新建)

- **审计**:管家发起的写动作走既有 `ActionRequest → GatedActionService → PolicyEngine` + `agent_action` 表,不建平行审计。
- **权限**:复用监督席权限 key,无新权限域。
- **070 `incident_*` 表**:并存期不动、不迁移。
