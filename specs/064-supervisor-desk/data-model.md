# Data Model: 监督席

**Feature**: 064-supervisor-desk
**Date**: 2026-07-11

## Entity Changes

### incident (MODIFY — schema 0.10.0 → 0.11.0)

| Column | Type | Null | Change | Notes |
|---|---|---|---|---|
| `heal_by_type` | `VARCHAR(32)` | YES | **NEW** | 愈合条件——恢复信号的 eventType。如 TASK_FAILED 工单 → heal_by_type='TASK_SUCCESS'。NULL = 无自动愈合（手动关闭） |
| `heal_by_ref_id` | `VARCHAR(128)` | YES | **NEW** | 愈合条件——恢复信号的 refId。如 taskId=100 → heal_by_ref_id='100'。NULL = 无自动愈合 |

**New Index**: `idx_incident_heal ON incident(tenant_id, heal_by_type, heal_by_ref_id)` — 恢复信号到达时快速匹配待愈合工单。

**Existing columns (unchanged, documented for context)**:

| Column | Type | Notes |
|---|---|---|
| `signature` | `VARCHAR(128)` | 去重指纹。**本次修改语义**：从 `T:<taskId>:<failureClass>` 改为 `T:<taskId>:<failureReason>`（如 `T:100:EXIT_CODE_-1`）。长度 128 足够 |
| `active_key` | `VARCHAR(128)` | UNIQUE 约束保证同签名只有一个活跃工单（CLOSED 时置 NULL） |
| `resolution_kind` | `VARCHAR(32)` | 现有值 `AUTO_HEAL`/`MANUAL_RERUN` 不变 |

### health_event (NO CHANGE)

现有表结构已满足需求。`fingerprint` 列已用于去重，`ref_id`/`ref_kind` 可映射到工单的 `source_ref_id`/`source_kind`。

### incident_event (NO CHANGE)

时间线 append-only 流水保持不变。

## State Transitions (NO CHANGE)

现有 `IncidentStates` 合法迁移不变：
- OPEN → MITIGATING, RESOLVED, SUPPRESSED
- MITIGATING → OPEN, RESOLVED, SUPPRESSED
- RESOLVED → OPEN, CLOSED
- SUPPRESSED → OPEN
- CLOSED → (终态)

## Frontend Type Changes

### IncidentCard (EXTEND)

```typescript
interface IncidentCard {
  // ... existing fields unchanged ...
  healByType: string | null   // NEW — 愈合条件事件类型
  healByRefId: string | null  // NEW — 愈合条件引用 ID
}
```

### IncidentDetail (NO CHANGE)

`IncidentDetail = { incident: IncidentCard, timeline: TimelineEntry[], actions: IncidentAction[] }` — timeline 类型不变。

### HealthEvent (NO CHANGE)

现有类型直接用于信号流面板展示。
