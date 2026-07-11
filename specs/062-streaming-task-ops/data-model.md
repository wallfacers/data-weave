# Data Model: 实时任务运维（062）

> Phase 1 输出。实体、字段、关系、状态迁移。schema 0.15.0 → **0.16.0**（单表新增 + task_instance 一列，DROP+CREATE 幂等，存量零影响）。

## 新增实体

### `task_checkpoint`（检查点）

一个实时任务实例的多个可恢复检查点，滚动保留最近 N 个（默认 3，可配置）。续跑时从中选择一个作为恢复点。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | UUID (v7) | PK | 检查点唯一 ID |
| `task_instance_id` | UUID | FK → task_instance.id, NOT NULL | 所属实例（级联删除：实例删则检查点删） |
| `ordinal` | INT | NOT NULL | 序号（同一实例内递增，用于滚动淘汰：保留 ordinal 最大的 N 个） |
| `checkpoint_path` | VARCHAR(1024) | NOT NULL | 引擎侧 savepoint/checkpoint 路径（Flink savepointPath） |
| `external_ref` | VARCHAR(255) | NULL | 引擎侧触发句柄/请求 ID（可选，用于追踪 savepoint 触发结果） |
| `status` | VARCHAR(32) | NOT NULL | `IN_PROGRESS` / `SUCCESS` / `FAILED` / `EXPIRED` |
| `size_bytes` | BIGINT | NULL | 检查点大小（引擎返回则填） |
| `completed_at` | TIMESTAMP | NULL | status=SUCCESS 的时间（过期判定依据） |
| `created_at` | TIMESTAMP | NOT NULL DEFAULT now() | 创建时间 |

**索引**：`idx_checkpoint_instance (task_instance_id, ordinal DESC)`（面板列表 + 滚动淘汰查询）、`idx_checkpoint_status (task_instance_id, status)`（找有效检查点）。

**约束/规则**：
- **滚动淘汰**：每次成功写入新 SUCCESS 检查点后，按 ordinal 保留每个 task_instance 最大的 N 条，更早的标记 EXPIRED（软淘汰，供审计追溯）或物理删除（plan 定夺）。
- **续跑有效判定**：`status='SUCCESS'` 且未过期（completed_at 在保留窗内，默认 24h，可配置）。
- **唯一性**：一个实例同一时刻至多一个 `IN_PROGRESS` 检查点（并发触发 savepoint 防护）。

**关系**：`task_instance (1) — (N) task_checkpoint`。

## 既有实体变更

### `task_instance` —— 新增 `long_running` 快照列

| 新字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `long_running` | BOOLEAN | NOT NULL DEFAULT FALSE | 实例创建时从 task_def 快照；面板按此过滤实时任务，免 JOIN task_def |

**注意（必改点）**：`OpsService.rerunInstance` 的原生 SQL UPDATE（OpsService.java:645-649）显式列出所有重置字段，必须把 `long_running` 加入重置列表（值取自 task_def，实例 rerun 仍同任务定义），否则 rerun 后该列残留旧值。

其余 task_instance 字段不变。`external_job_handle`（VARCHAR(512)，:597）语义不变（当前运行句柄 `{jobId, restEndpoint}`），**不**塞检查点历史（检查点走 task_checkpoint 表）。

### 状态枚举（**不变**，复用 InstanceStates 11 态）

不新增状态。`RUNNING / STOPPED / WAITING / SUSPENDED` 等既有语义复用。"保留进度的停止"= STOPPED + task_checkpoint 有效行；"恢复中"= reattach 后的 RUNNING。

## 状态迁移（新增路径）

```
                         stopWithSavepoint
              RUNNING ───────────────────────► STOPPED
                 ▲                                  │
                 │ reattach (060 已有)              │ resumeFromCheckpoint
                 │                                  ▼ (有效 ckpt)
              DISPATCHED                          WAITING
                                                    ▲
                 casRequeueInfra (060 已有)          │ resumeFromCheckpoint
              SUSPENDED ────────────────────────────┘ (有效 ckpt: 优先续跑)
                 │                                  
                 │ resumeFromCheckpoint            
                 │ (无有效 ckpt → 降级)             
                 └──────► rerunInstance (全量重跑, 清 handle) ──► WAITING

强制终止: RUNNING ──killTask──► STOPPED (CANCEL, 不写 ckpt)
```

**新增 CAS（InstanceStateMachine）**：
- `casResumeFromCheckpoint(from IN {STOPPED, SUSPENDED}, → WAITING)`：不清 external_job_handle，记录所选 checkpoint_id（写入 task_instance 某引用列或 task_checkpoint.resume_of 关联——plan 定夺）。复用现有 claim→dispatch→reattach 路径（reattach 命中 handle 则不重复提交，FlinkTaskExecutor.java:268-296）。
- 该 CAS 是 SUSPENDED→续跑的唯一入口（060 现状缺此路径）。

**与 060 不变量兼容**：所有状态推进仍走乐观 CAS（WHERE state=?）；SUSPENDED 仍非终态、不被 claim、需人工转出。新增的 `casResumeFromCheckpoint` 不影响 attempt 纯栅栏 / business_attempt 双拆语义（060 七红线）。

## 指标（SchedulerMetrics，不可变约定）

新增 gauge（注册到 sampleGauges 周期刷新）：
- `scheduler.streaming.checkpoint.total`（按 status 分标签的检查点数）
- `scheduler.streaming.recovering`（处于 resumeFromCheckpoint 后 WAITING/DISPATCHED 中的实例数）
- 复用既有 `scheduler.instance.suspended`（SUSPENDED 实例数，060 已有 :268-270）

## schema 版本

`schema_version`: 0.15.0 → **0.16.0**（单行表头 + schema_version 行同步；task_checkpoint DROP+CREATE 幂等；task_instance 加列）。DB 行 / 文件头 / 项目版本三者保持一致（既有约定）。
