# Data Model: 069 任务失败智能运维

**Schema**: `0.18.0 → 0.19.0`（单一权威 DDL `backend/dataweave-api/src/main/resources/schema.sql`；DB 行 / 文件头 / 项目版本三处同步）。H2/PG 双方言兼容（`IF NOT EXISTS`、无 partial index、拼接用 CONCAT）。

## 新表

### incident（事故——一等领域对象）

| 列 | 类型 | 说明 |
|---|---|---|
| id | UUID PK | |
| tenant_id | BIGINT NOT NULL | 租户隔离 |
| project_id | BIGINT NOT NULL | 项目隔离（FR-013） |
| task_def_id | BIGINT NOT NULL | 关联任务 |
| task_def_name | VARCHAR(255) | 快照冗余，防任务删后线程失名 |
| first_instance_id | UUID NOT NULL | 开单实例 |
| latest_instance_id | UUID NOT NULL | 最近关联实例（归并/重跑后更新） |
| instance_count | INT DEFAULT 1 | 归并的失败实例数 |
| trigger_source | VARCHAR(16) | CRON / MANUAL / STREAMING（口径：long_running→STREAMING；否则 cron_expression 非空→CRON、为空→MANUAL） |
| classification | VARCHAR(24) | TRANSIENT / RESOURCE / CODE / UPSTREAM_DATA / CONFIG_CREDENTIAL / UNKNOWN；NULL=未诊断 |
| confidence | VARCHAR(8) | HIGH / MEDIUM / LOW（LLM 自评） |
| state | VARCHAR(24) NOT NULL | 见状态机 |
| open_key | BIGINT | **开单唯一键**：开着时 = task_def_id，收口置 NULL；`UNIQUE(tenant_id, open_key)`（NULL 可重复，规避 H2 无 partial index） |
| auto_action_count | INT DEFAULT 0 | 防循环计数（FR-006/上限 `ops.incident.max-auto-actions`） |
| summary | VARCHAR(512) | 一句话事故摘要（feed 列表用） |
| suggestion | TEXT | 升级人工时的操作建议（FR-008） |
| close_kind | VARCHAR(16) | AUTO / HUMAN_ASSISTED / MANUAL |
| opened_at / closed_at | TIMESTAMP | |
| version | INT DEFAULT 0 | 乐观锁 |
| created_at / updated_at | TIMESTAMP | |

索引：`UNIQUE(tenant_id, open_key)`；`idx_incident_project(project_id, state, opened_at DESC)`；`idx_incident_task(task_def_id, opened_at DESC)`。

**状态机**（全部推进乐观 CAS `WHERE state=?`）：

```
OPEN ──采证/诊断──▶ ANALYZING ──分型──▶ ACTING（自动处置执行中）
  │                     │                 ├─ 验证成功 ─▶ RESOLVED（close_kind=AUTO）
  │  模型不可用          │  CODE 分型       ├─ 验证失败且未超限 ─▶ ACTING（下一梯度）
  ▼                     ▼                 └─ 超限/不可自愈 ─▶ NEEDS_HUMAN
DIAG_UNAVAILABLE   AWAITING_APPROVAL ──批准──▶ ACTING（发布+重跑验证）
  （配置恢复可回 ANALYZING）│──驳回──▶ NEEDS_HUMAN
NEEDS_HUMAN ──人标记已处理+复验成功──▶ RESOLVED（close_kind=HUMAN_ASSISTED）
任意非终态 ──人工直接收口──▶ RESOLVED（close_kind=MANUAL）
```

不变量：① 同一任务至多一个未收口事故（open_key 唯一）；② `state=RESOLVED ⇔ closed_at NOT NULL ⇔ open_key IS NULL`；③ `auto_action_count` 只增不减；④ 事故只读观察调度，绝不反向锁 task_instance 行（守调度锁序红线）。

### incident_message（线程消息，持久化粒度）

| 列 | 类型 | 说明 |
|---|---|---|
| id | UUID PK | |
| incident_id | UUID NOT NULL | FK 逻辑关联 |
| seq | BIGINT NOT NULL | 事故内递增序（SSE Last-Event-ID 续传锚点） |
| kind | VARCHAR(16) NOT NULL | AGENT_STEP（采证/诊断/验证步骤）/ AGENT_SAY（对话回复）/ HUMAN_SAY / ACTION（闸门动作及结果）/ PROPOSAL（提案卡）/ SYSTEM（开立/归并/让位/升级） |
| content | TEXT | 面向人的正文（LLM 叙述按 agent locale 原文存储） |
| payload_json | TEXT | 结构化载荷：工具链 chips 状态、证据引用（instanceId/日志行）、agent_action_id、proposal_id、分型/置信度 |
| actor | VARCHAR(64) | `ops-agent` / 用户名 / `system` |
| created_at | TIMESTAMP | |

索引：`UNIQUE(incident_id, seq)`；`idx_incident_msg(incident_id, seq)`。
注：思考态起止、流式文本分片、工具动作点亮为**瞬态直播事件**（只走 EventBus，不落此表）；语义完整后落一条消息。

### incident_proposal（修复提案）

| 列 | 类型 | 说明 |
|---|---|---|
| id | UUID PK | |
| incident_id | UUID NOT NULL | |
| task_def_id | BIGINT NOT NULL | |
| base_version_no | INT NOT NULL | 生成提案时的 current_version_no（陈旧性防护：发布前必须仍相等，否则提案作废） |
| proposed_content | TEXT NOT NULL | 全量新脚本内容（与 push 幂等覆盖语义一致，非 diff） |
| change_summary | VARCHAR(1024) | 变更说明（LLM 生成） |
| evidence_json | TEXT | 证据包：诊断依据、关键日志行、预期效果 |
| status | VARCHAR(16) NOT NULL | PENDING → APPROVED / REJECTED / STALE；APPROVED → PUBLISHED → VERIFIED / VERIFY_FAILED → ROLLED_BACK |
| agent_action_id | BIGINT | 关联 agent_action（闸门/审批单） |
| published_version_no | INT | 发布产生的新版本号 |
| rollback_version_no | INT | 回滚产生的版本号（回滚也是新快照，版本只进不退） |
| approved_by / approved_at | | |
| created_at / updated_at | TIMESTAMP | |

### incident_briefing（战况播报，每项目最新一行）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id / project_id | BIGINT NOT NULL | `UNIQUE(tenant_id, project_id)` |
| summary_line | VARCHAR(512) | LLM 一句话综述（叙述层） |
| report_md | TEXT | 完整接班报告（Markdown） |
| stats_json | TEXT | 生成时点的计数快照——**接口返回的实时数字永远另由 SQL 现算**（SC-010 一致性），此处仅报告佐证 |
| generated_at | TIMESTAMP | |

## 既有表变更

| 表 | 变更 | 说明 |
|---|---|---|
| `task_def` | + `resources_json VARCHAR(512)` | 声明式资源 `{"memoryMb":4096,"cpuCores":2}`，NULL=引擎默认；随 `task_def_version` 快照列同步新增、随 `*.task.yaml` 文件契约新增可选 `resources` 节（pull/push 往返完整性） |
| `task_def_version` | + `resources_json VARCHAR(512)` | 版本快照承载 |
| `lineage_agent_config` | + `ops_enabled` INT DEFAULT 0 | 智能运维独立启停（FR-012）；连接配置四元组复用不动 |

## 领域对象与服务归属（dataweave-master, DDD）

```
domain/incident/        Incident · IncidentMessage · IncidentProposal · IncidentBriefing
                        Classification / IncidentState 枚举 · IncidentEvent（直播事件 record 族）
application/incident/   IncidentSweeper（@Scheduled 巡检开单+认领）
                        IncidentAgentService（采证→诊断→决策→行动→验证 编排）
                        IncidentEvidenceCollector（LogBus 尾部/定义/历史/句柄）
                        IncidentConversationService（多轮对话+动作提议解析）
                        IncidentBriefingService（防抖播报）
                        IncidentQueryService（列表/详情/消息分页）
application/lineage/agent/  LlmChatClient（新增，复用两协议适配器）
infrastructure/incident/    IncidentRepository · IncidentMessageRepository · IncidentProposalRepository · IncidentBriefingRepository
interfaces(api 模块)        IncidentController（REST + SSE，见 contracts/）
```

关系：`incident 1—N incident_message`；`incident 1—N incident_proposal`；`incident N—1 task_def`；`incident_message —(payload)→ agent_action / incident_proposal`；写动作审计仍在 `agent_action`（不加列，经 proposal/payload 反向关联）。

## 校验规则（源自 FR）

- 开单：仅 `state IN ('FAILED','SUSPENDED')` 且 `run_mode NOT IN ('TEST','BACKFILL')` 的实例可触发；插入冲突 = 归并（instance_count++、latest_instance_id 更新、追加 SYSTEM 消息）。
- 资源护栏：`memoryMb ≤ ops.incident.memory-cap-mb`；新值/旧值 ≤ `resource-step-factor-max`；越界 → 转 NEEDS_HUMAN（FR-005）。
- 提案陈旧：发布前 `task_def.current_version_no != base_version_no` → status=STALE + SYSTEM 消息（FR-007 冲突防护）。
- 对话提议动作：只允许白名单动作类型（rerun / adjust_resources / reverify / publish_fix / escalate），其余丢弃并记录。
- 消息 seq：服务端事故级序列器发号，保证 SSE 续传单调。
