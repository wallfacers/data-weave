# Contract: TriggerEngine（内部触发引擎 SPI）

本特性无新增对外 REST 端点;契约是 `dataweave-master` 内部组件边界,锁定 `CronScheduler` ↔ `TriggerEngine` ↔ `TimingStrategy` ↔ 下游 `WorkflowTriggerService` 的协作,确保去重/下游/死锁不变量不被破坏。

## TriggerEngine

```java
public interface TriggerEngine {
    /** 15s 周期由 CronScheduler 调用:扫描本 master 本分片、next_trigger_time ≤ now+lookahead 的工作流，
     *  将其按精确延迟装入进程内定时器(到点回调 fire)。幂等:重复装载同一(workflowId, fireTime)不产生重复触发。 */
    void scanAndArm(LocalDateTime now);

    /** 工作流上线/改 cron/改生效期时刷新计时策略缓存与 next_trigger_time(可由事件或扫描增量触发)。 */
    void refresh(long workflowId);
}
```

**到点 fire 的内部序列(不可变约束)**:
1. 校验 `status=ONLINE` 且 `due ∈ [schedule_start, schedule_end]`(失效/下线/超期 → 丢弃,**不触发**,满足 FR-013)。
2. `cron_fire.save(new CronFire(workflowId, due))`;`DataIntegrityViolationException` → 放弃(别的 master/分片已占该点)。**此步是唯一去重真相,不可绕过**(FR-003)。
3. `WorkflowTriggerService.trigger(wf, "CRON", bizDate, priority, locale)` —— **签名与语义保持不变**(FR-012)。重叠触发**允许并发**,不阻塞、不查上一实例(FR-015)。
4. 回填 `cron_fire.firedAt/workflowInstanceId`;`TimingStrategy.next(...)` 重算并持久化 `workflow_def.next_trigger_time`(FR-004)。
5. 记录 `dw.cron.trigger.latency`。

**逾期点**: 若 `due ≤ now`(扫描时已过期或定时器繁忙),delay 取 0 立即执行上述序列(FR-005)。

## TimingStrategy

```java
public interface TimingStrategy {
    /** 返回严格大于 base 的下一个触发时刻;无后续(超出生命周期)返回 null → 调用方置 next_trigger_time=NULL 停排。 */
    LocalDateTime next(WorkflowDef wf, LocalDateTime base, LocalDateTime now);
    boolean supports(String scheduleType);   // CRON / FIXED_RATE / FIXED_DELAY
}
```

- `CRON`: `CronExpression.parse(wf.cron).next(base)`(6 字段含秒)。
- `FIXED_RATE`: `base + wf.scheduleIntervalMs`。
- `FIXED_DELAY`: `lastCompletion(wf) + wf.scheduleIntervalMs`(首轮用创建时刻)。
- **misfire 归一**: 调用方拿到 `next` 后,若 `next ≤ now` 则立即触发并继续 `next(now,…)` 推进(默认 `fire_once`);`scheduler.cron-misfire=skip` 时只推进基准不触发(FR-006/FR-007)。

## 不变量(测试须覆盖)

- 同一 `(workflow_id, scheduled_fire_time)` 在 N master/分片下**恰触发一次**(`SchedulerConcurrencyTest` 扩展)。
- `WorkflowTriggerService.trigger` 入参顺序/类型/默认 locale 不变(编译期 + 调用点回归)。
- `SchedulerKernel` 零改动:cron 触发只产实例 + `publish("dw:wake")`,派发链路不受影响。
