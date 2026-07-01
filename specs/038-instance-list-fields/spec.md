# 038 任务流实例列表字段增强 + scheduled_fire_time 快照

## 背景

任务流实例列表（`workflow-instances-panel.tsx`）当前仅 8 列，且 DataWorks 心智下"任务流实例该有定时时间"——Weft 语义里该值（`scheduled_fire_time`）存在于 `cron_fire` 防重护栏表，但 `workflow_instance` 表把 `cron_expression`/`workflow_def_name` 都冗余成快照，唯独漏了它，列表 SQL 也未 JOIN，前端拿不到。

## 范围

1. **后端**：`workflow_instance` 加 `scheduled_fire_time TIMESTAMP` 快照列（与 `cron_expression` 同源同哲学），cron/fixed_rate 触发建实例时写入（手动/补数据透传 null）；schema 升 0.5.0 → 0.6.0。列表 DTO `WorkflowInstanceRow` 补 `scheduledFireTime`/`workflowVersionNo`/`cronExpression` 投影。
2. **前端**：列表补 6 列（priority/failedTasks/finishedAt/workflowVersionNo/cronExpression/scheduledFireTime），widthPct 重分配到 100，`humanizeCron` 抽共享 util，i18n 加 2 key。

## 不做（拆后续）

- 触发人（需 join sys_user 下发 createdByName）
- 运行中实例 ETA（需 per-instance 接口，接 SlaService.predictLatestEta）
- 实例 ID 列

## 方案决策

- 定时时间落地：**实例表加字段**（方案 B），非 JOIN cron_fire（因 CronFireReaper 30 天清理，JOIN 老实例取不到）。
- 回填：不回填，老实例留 NULL。
- 归属：独立 worktree，不污染 034。

详见实施计划：`/home/wallfacers/.claude/plans/eager-hatching-sunrise.md`。
