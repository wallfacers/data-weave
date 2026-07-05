# Data Model: cron 触发并发吞吐优化

> Phase 1 产出。列本 feature 涉及/新增的实体与字段;完整字段以 `backend/dataweave-api/src/main/resources/schema.sql`(权威 DDL)为准。

## 扩展实体

### CronFire(表 `cron_fire`)
触发点记录,去重真相源。本 feature 扩展生命周期状态 + 补偿器扫描索引。

| 字段 | 类型 | 说明 |
|---|---|---|
| (既有) workflow_id | BIGINT | 工作流 id |
| (既有) scheduled_fire_time | TIMESTAMP | 计划触发时刻(与 workflow_id 组合 UNIQUE) |
| (既有) workflow_instance_id | UUID | 回填的实例 id(fireExecute 完成后) |
| (既有) created_at | TIMESTAMP | INSERT 时间(fireArm) |
| (既有) fired_at | TIMESTAMP | 物化完成时间(fireExecute) |
| **status**(新) | VARCHAR(16) | `PENDING`(fireArm INSERT)/ `FIRED`(fireExecute 回填)/ `DEAD`(reconciler 超时放弃) |

**新索引**:`(instance_id, created_at)` —— reconciler 扫 `instance_id IS NULL && created_at < :threshold`(默认扫 grace=30s 仍未回填的)。

### WorkflowInstance(表 `workflow_instance`)
工作流运行实例。本 feature 加部分唯一约束(幂等防重)。

**新约束**:`UNIQUE (workflow_id, scheduled_fire_time) WHERE scheduled_fire_time IS NOT NULL`
- 覆盖:CRON / FIXED_RATE / FIXED_DELAY 触发(scheduled_fire_time 非 null)
- 不覆盖:手动触发 / 补数据(scheduled_fire_time = null,零误伤;`triggerBackfillTaskRun` 走独立路径不传 scheduled_fire_time)
- 作用:并发 fire / 崩溃重试 / reconciler 重试 的 DB 层兜底,防同一触发点创建多个实例

## 新增(进程内 / 组件)

### FireTask(进程内 record,不持久化)
触发点物化任务,`fireQueue` 元素,timer 线程 → worker 池传递。

| 字段 | 类型 | 说明 |
|---|---|---|
| workflowId | Long | 工作流 id |
| due | LocalDateTime | 计划触发时刻 |
| cronFireId | Long | 对应 cron_fire 行 id(回填用) |

### CronFireReconciler(新 `@Component`)
周期补偿崩溃丢失的触发点。

- `@Scheduled(fixedRateString = "${scheduler.cron-reconcile-interval-ms:10000}")`
- 扫描:`SELECT FROM cron_fire WHERE instance_id IS NULL AND created_at < :threshold LIMIT :batch`
  - `threshold = now - cron-reconcile-grace-ms`(默认 30000)
- 每行:应用层幂等查 → 已有 instance 回填 `status=FIRED`;无 → `triggerService.trigger` → 回填
- DEAD:`created_at < now - cron-reconcile-timeout-ms`(默认 180000)仍失败 → `status=DEAD` + `log.error`

## 不变实体(沿用)

- **WorkflowDef**:`advanceNext` 沿用现有 `workflowDefRepository.save`(next_trigger_time 重算),行为不变。
- **TaskInstance**:物化批量 `saveAll`,字段不变。
- **WorkflowDefVersion / WorkflowDagSnapshot**:trigger 物化读快照,不变。

## schema_version

`schema_version` single-row 版本号 bump(本 feature 改 `cron_fire` + `workflow_instance` 表 → 必 bump;DB 行 / 文件 header / 项目版本须一致)。具体版本号实施时基于当前最大按 SemVer 定。
