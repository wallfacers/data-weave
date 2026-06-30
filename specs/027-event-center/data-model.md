# Data Model: 统一数据健康事件中心

**schema_version 0.3.0 → 0.4.0**（新增两表）。

## 新增表

### health_event
规则无关的全量数据健康事件（旁路持久化）。

| 列 | 类型 | 说明 |
|----|------|------|
| id | BIGINT IDENTITY PK | |
| tenant_id | BIGINT NOT NULL | 租户隔离 |
| type | VARCHAR(32) NOT NULL | 来源类型：SLA_BREACH/QUALITY_FAILED/TASK_FAILED/TASK_TIMEOUT/NODE_OFFLINE/METRIC_BREACH/...（预留 LINEAGE_CONFLICT） |
| severity | VARCHAR(16) | 信号 severityHint |
| fingerprint | VARCHAR(128) NOT NULL | 去重指纹（来自 fingerprintHint） |
| ref_kind | VARCHAR(16) | 关联对象类型：TASK/METRIC/TABLE/WORKFLOW |
| ref_id | VARCHAR(128) | 关联对象标识（深链用） |
| summary | VARCHAR(512) | 摘要 |
| context_json | VARCHAR(2000) | 信号 context 载荷 |
| count | INTEGER DEFAULT 1 | 去重合并计数 |
| first_occurred_at | TIMESTAMP | 首次 |
| last_occurred_at | TIMESTAMP | 最近 |
| created_at | TIMESTAMP | |
| deleted | SMALLINT DEFAULT 0 | |

去重键：`(tenant_id, type, fingerprint)` + 窗口内合并（count++/last_occurred_at 刷新），镜像 `alert_event` 范式。

### event_subscription
用户/租户对事件的订阅。

| 列 | 类型 | 说明 |
|----|------|------|
| id | BIGINT IDENTITY PK | |
| tenant_id | BIGINT NOT NULL | |
| subscriber_id | BIGINT | 订阅者（用户） |
| type_filter | VARCHAR(32) | 订阅的事件类型（空=全部） |
| min_severity | VARCHAR(16) | severity 阈值（≥ 才触达） |
| ref_kind | VARCHAR(16) | 资产维度过滤（空=全部） |
| ref_id | VARCHAR(128) | 资产标识过滤（空=全部） |
| channel_id | BIGINT NOT NULL | 目标通道（alert_channel.id，复用 026） |
| enabled | SMALLINT DEFAULT 1 | |
| created_at | TIMESTAMP | |
| deleted | SMALLINT DEFAULT 0 | |

## 复用/统一实体

### domain.signal.AlertSignal（统一后唯一信号契约）
`type/tenantId/fingerprintHint/severityHint/context/occurredAt`。`quality.domain.AlertSignal` **删除**，`QualitySignalEmitter` 改用本类。

### AlertChannel / AlertDispatchService（026，复用）
订阅命中后经 `AlertDispatchService.dispatch`（或直发指定通道）触达 + `AlertNotification` 审计。

## 关联对象深链映射

| ref_kind | 深链目标 |
|----------|---------|
| TASK | 任务实例视图 |
| METRIC | 指标视图 |
| TABLE | 血缘视图（定位该表节点） |
| WORKFLOW | 工作流实例视图 |

对象不存在 → 优雅降级「对象已不存在」，不报错。
