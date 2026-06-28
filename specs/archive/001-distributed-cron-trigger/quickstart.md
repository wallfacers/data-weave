# Quickstart: 分布式 Cron 精确触发（本地验证）

## 前置

- JDK 25(本机 symlink 透明生效)、`backend/` 可构建。
- 后端改动落在 `dataweave-master`,改后须 `cd backend && ./dev-install.sh`(否则 `spring-boot:run` 仍用旧 jar)。

## 跑起来(零外部依赖,H2)

```bash
cd backend && ./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
# 默认:15s 扫描 + fire_once + 不分片
```

## 验证 1 — 准点触发(US1 / SC-001)

1. 建一个近未来分钟级 cron 工作流(或用 data.sql 种子),置 `status=ONLINE`。
2. 到点后看日志 `[CronScheduler]/[TriggerEngine]` 触发记录,确认实例创建发生在 cron 时刻后 **秒级**内。
3. 拉指标确认延迟分布:
   ```bash
   curl -s localhost:8000/actuator/prometheus | grep dw_cron_trigger_latency
   ```
   p99 应 ≤ 2s。

## 验证 2 — 秒级 cron(US4 / SC-005)

1. 建 cron = `*/30 * * * * *`(每 30 秒)的工作流,ONLINE。
2. 连续观察 ≥ 60 个周期,相邻触发间隔误差 ≤ 2s 且无累计漂移(Spring `CronExpression` 6 字段直接生效,无需 cron-utils)。

## 验证 3 — 多 master 去重(US2 / SC-002)

- 单元/集成层:运行扩展后的并发去重回归
  ```bash
  cd backend && ./mvnw -q -pl dataweave-api test -Dtest=SchedulerConcurrencyTest
  ```
  断言 `cronFireGuardrail_dedupsConcurrentInserts`:同一 `(workflow_id, fireTime)` N 线程竞争,恰一次 INSERT 成功、其余冲突放弃。
- 多进程层:启 ≥2 个 master 指向同一 PG,确认同一触发点只产生一条 `workflow_instance`。

## 验证 4 — misfire 恢复(US3 / SC-003)

1. 把某 cron 工作流 `next_trigger_time`(或 `last_fire_time`)回拨到过去若干周期,模拟停机错过。
2. 默认 `fire_once`:恢复后只补触发一次(delay=0)并把基准推进到未来最近点;`dw.cron.misfire.count` +1,不逐个回放。
3. 改 `--scheduler.cron-misfire=skip` 重跑:补偿数为 0,仅推进基准。

## 验证 5 — >10k 分片(Batch B / SC-007,可选)

```bash
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.arguments=--scheduler.cron-sharding-enabled=true
```
- 灌入 ≥10k ONLINE cron 工作流,启 ≥3 master,确认:全量触发点零重复/零漏;`dw.cron.shard.workflows` 约为 总数/活master数;模拟某 master 下线,重平衡期靠 cron_fire 兜底不丢不重。

## 回归基线

- `SchedulerConcurrencyTest` 全绿(去重 + 派发不变量)。
- 现有分钟级 cron 工作流无定义改动即正确触发(SC-004)。
