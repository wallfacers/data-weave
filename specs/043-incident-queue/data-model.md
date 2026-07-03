# Data Model: Incident 域模型 + 监督席队列 (043)

schema_version: `0.6.3 → 0.7.0`（+2 表，+1 列；schema.sql 文件头 / schema_version INSERT / docs/architecture.md 三处同步）。

## 表 1：incident（工单本体，可变实体 → 全套审计列）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT IDENTITY | PK | |
| tenant_id | BIGINT | NOT NULL | 租户隔离 |
| project_id | BIGINT | NOT NULL | 项目隔离（036 惯例） |
| signature | VARCHAR(128) | NOT NULL | 故障签名（research D3）：`T:{taskId}:{failureClass}` / `WSLA:{workflowId}` / `N:{nodeCode}:OFFLINE`；未来经验库 join key |
| active_key | VARCHAR(128) | NULL | 未关闭 = signature，CLOSED 置 NULL；`UNIQUE(tenant_id, project_id, active_key)` 保证未关闭同签名全局唯一（research D4） |
| title | VARCHAR(256) | NOT NULL | 人话标题（如「ods_订单同步 失败(EXIT_NONZERO)」），服务端生成 |
| severity | VARCHAR(16) | NOT NULL | 已附着信号 severityHint 最大值（research D9） |
| state | VARCHAR(16) | NOT NULL | OPEN / MITIGATING / RESOLVED / SUPPRESSED / CLOSED |
| source_kind | VARCHAR(16) | NOT NULL | TASK / WORKFLOW / NODE（对齐 health_event ref_kind 语义，深链复用 refKindToView） |
| source_ref_id | VARCHAR(64) | NOT NULL | taskId / workflowId / nodeCode |
| source_ref_name | VARCHAR(256) | NULL | 可读名（taskName/workflowName），镜像 health_event.ref_name |
| workflow_instance_id | UUID | NULL | 同工作流实例归并键（research D4）；NODE 类为 NULL |
| occurrence_count | INTEGER | NOT NULL DEFAULT 1 | 附着/复发累加 |
| first_seen_at / last_seen_at | TIMESTAMP | NOT NULL | |
| blast_radius | INTEGER | NULL | 下游任务数；NULL = 血缘不可用（区别于 0 = 无下游，research D7） |
| time_budget_at | TIMESTAMP | NULL | 最近下游 SLA 基线投影时刻；NULL = 无基线；早于 now = 已超期 |
| suppress_reason | VARCHAR(512) | NULL | 静默原因（FR-011） |
| resolution_kind | VARCHAR(32) | NULL | AUTO_HEAL / MANUAL_RERUN / …（收口方式） |
| resolved_at / closed_at | TIMESTAMP | NULL | RESOLVED+7d 无复发 → sweeper 置 CLOSED（clarify Q1） |
| diagnosis_json | VARCHAR(4000) | NULL | **编队预留槽位**，本期恒 NULL（FR-013） |
| proposal_json | VARCHAR(4000) | NULL | **编队预留槽位**，本期恒 NULL（FR-013） |
| created_by/updated_by/created_at/updated_at/deleted/version | 审计标配 | | 可变实体全套 |

索引：`idx_incident_queue (tenant_id, project_id, state, last_seen_at DESC)`；`idx_incident_wfi (workflow_instance_id)`；`idx_incident_signature (tenant_id, signature)`（历史同签名关联提示 + 经验库预热）。

## 表 2：incident_event（时间线，append-only 流水 → 仅 created_at）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT IDENTITY | PK | |
| tenant_id | BIGINT | NOT NULL | |
| incident_id | BIGINT | NOT NULL | 所属工单 |
| seq | INTEGER | NOT NULL | 单内递增序号（`UNIQUE(incident_id, seq)`） |
| kind | VARCHAR(16) | NOT NULL | SIGNAL / STATE_CHANGE / ACTION / APPROVAL / NOTE /（预留 AGENT_FINDING） |
| payload_json | VARCHAR(4000) | NOT NULL | 事件内容：SIGNAL=信号 context 摘要；ACTION=agent_action id + 闸门 outcome；STATE_CHANGE=from/to/原因（含"复发"重开） |
| actor | VARCHAR(64) | NOT NULL | 用户名 / `system`（自动愈合、sweeper）/ 未来 agent 名 |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

索引：`idx_ievent_incident (incident_id, seq)`。

## 变更 3：agent_action + incident_id

`agent_action` 加可空列 `incident_id BIGINT`——incident 卡片发起的闸门动作反向关联工单（research D10）；既有行为零影响（列可空、无默认逻辑分支）。索引 `idx_agent_action_incident (incident_id)`。

## 状态机

```
            信号(同签名/同实例)附着: count++, last_seen
                    ┌────────────┐
                    ▼            │
  信号 ──开单──▶ OPEN ──处置动作提交──▶ MITIGATING
                    │  ▲                  │
                    │  │ 复发(CAS,记时间线)│ 处置后再失败(附着+CAS回OPEN)
      恢复信号/心跳恢复│  │                  │ 恢复信号
                    ▼  │                  ▼
                  RESOLVED ◀──────────────┘
                    │ sweeper: resolved_at+7d 无复发
                    ▼
                  CLOSED (active_key=NULL, 终态)

  OPEN/MITIGATING ⇄ SUPPRESSED（人工静默/恢复；静默期间信号仅累加计数）
```

- 一切状态推进 = `UPDATE ... WHERE id=? AND state=?`（乐观 CAS，多 master 安全，调度内核不变量②）。
- 每次成功推进同步写一条 STATE_CHANGE 时间线（同事务）。
- CLOSED 为唯一终态；同签名新故障在 CLOSED 后 → 开新单（active_key 唯一约束此时不冲突），新单查 `idx_incident_signature` 给"历史同签名"提示。

## 验证规则（源自 FR）

- 开单必填：signature/title/severity/state=OPEN/source_*/first_seen/last_seen（FR-002）。
- 附着更新仅允许：occurrence_count、last_seen_at、severity（只升不降）、state（RESOLVED→OPEN 复发）（FR-002/D9）。
- suppress 必须携带非空 reason（FR-011）。
- diagnosis_json/proposal_json 本期只读为 NULL——任何写入路径都不存在（FR-013 的实现方式）。
- timeline 无 UPDATE/DELETE 路径（FR-006 append-only）。

## 与既有实体的关系

```
AlertSignal(瞬时) ──┬─▶ AlertSignalListener → alert_event(既有，不动)
                   ├─▶ HealthEventRecorder → health_event(既有，不动)
                   └─▶ IncidentSignalListener → incident + incident_event   ← 本期新增
TaskSucceededEvent(既有) ──▶ IncidentHealListener → RESOLVED
WorkflowSucceededEvent(新增，镜像 TaskSucceededEvent) ──▶ 同上
incident 1─n incident_event；incident 1─n agent_action(经 incident_id)；审批仍在 agent_action 上（不新建审批模型）
```
