# Research: Cron 并发触发链路验证

> Phase 0 产出。所有结论已由代码定位 + 基线实测支撑,无未解决项。

## R1. cron 表达式格式

- **Decision**:Spring `org.springframework.scheduling.support.CronExpression`,**6 字段:秒 / 分 / 时 / 日 / 月 / 周**。
- **Evidence**:`CronTimingStrategy.java:13` 注释「Spring CronExpression(6 字段 秒/分/时/日/月/周,原生含秒)」;`CronTimingStrategy.java:33` `CronExpression.parse(expr).next(base)`。
- **此前 bug**:压测脚本用 5 字段 `*/1 * * * *` → `CronTimingStrategy` 抛异常 → `log.warn("非法 cron")` → 该 workflow 的 `next_trigger_time` 永不回填 → **cron 自动触发链路长期零触发**。先前所有"压测"实际是手动 `/run`(trigger_type=MANUAL),不是并发 cron。
- **测试采用**:`*/10 * * * * *`(每 10 秒)—— 产生持续并发触发密度。

## R2. 调度触发机制(代码定位)

- **CronScheduler**(`CronScheduler.java:31`):`@Scheduled(fixedRateString = "${scheduler.cron-scan-interval-ms:15000}")` → `triggerEngine.scanAndArm(clock.now())`。外壳只驱动扫描。
- **scanAndArm**(`DefaultTriggerEngine.java:94`):
  1. 回填 `next_trigger_time = NULL` 的周期 wf(首扫)
  2. 预读 `next_trigger_time ≤ now + lookahead`(默认 30s)
  3. `armPoint(wfId, due)` 压入进程内精确定时器(armed `ConcurrentHashMap` 防同点跨周期重复 arm)
- **fire**(`DefaultTriggerEngine.java:186`):校验 ONLINE + 生效期 → **`cron_fire` UNIQUE(wf_id, scheduled_fire_time) 去重** → 创建 workflow_instance → 重算并持久化 next_trigger_time。
- **多 master**:armed 是进程内、不跨进程;两 master 各自 arm 同一点,到点都尝试 fire,靠 `cron_fire` 唯一键全局去重 —— 一个成功,一个撞键放弃(零协调)。

## R3. 秒级测试加速(不改后端代码)

- 在 `docker-compose.yml` 的 `dataweave-master` / `dataweave-master-2` 的 `environment` 追加:
  - `SCHEDULER_CRON_SCAN_INTERVAL_MS: "2000"`(15s → 2s)
  - `SCHEDULER_CRON_LOOKAHEAD_MS: "3000"`(30s → 3s)
- **纯 env 覆盖**:`application.yml` 不改,镜像不重建;`docker compose --profile distributed up -d dataweave-master dataweave-master-2` 重建容器即生效(relaxed binding:`SCHEDULER_CRON_SCAN_INTERVAL_MS` → `scheduler.cron-scan-interval-ms`)。
- 秒级 cron `*/10 * * * * *` + 2s 扫描 → 每 10s 一批 N 个到期,持续并发负载,迭代快。

## R4. 观测手段

| 指标 | 来源 |
|---|---|
| 定时触发实例数 / 状态分布 | `GET /api/ops/workflow-instances?size=200` → `data.items`,筛 `triggerType=CRON` |
| cron_fire 触发点 / 撞键 | `docker exec dataweave-postgres psql -U dataweave -c "select count(*) from cron_fire where workflow_id in (...)"`;双 master 撞键放弃数 = master-1 dispatch + master-2 dispatch − 唯一触发点数 |
| 双 master 触发分布 | `DW_BASE=http://localhost:8000/8200 ./cron-stress.sh metrics` 对比 `scheduler_dispatch_count_total` |
| 到点→实例延迟 | `workflow_instance.scheduled_fire_time` → `started_at` |
| 瓶颈环节 | `/actuator/prometheus`:`scheduler_*` / `dispatch_*` / `hikaricp_*` |

## R5. 瓶颈假设(待实测验证)

- **H1**:cron 链路不经入口 `/run`(内部 fire 直建实例),故入口的 HikariCP=10 瓶颈可能不适用;但 cron_fire 写入 + 实例创建仍走 DB 连接,高并发到期下连接池仍是候选瓶颈。
- **H2**:cron_fire 唯一键 —— N 个**不同** wf 同触发点 → N 次不同键插入,互不撞;同 wf 同点多 master → 撞键(每点 1 成功 1 放弃)。预期撞键放弃数 ≈ 触发点数(双 master 各 arm 一份)。
- **H3**:预读 SCAN 走 `idx_workflow_def_scan(deleted, schedule_type, status, next_trigger_time)`,50–100 wf 量级非瓶颈。
- **H4**:dispatch / 执行端,基线 `slot_utilization=0.1`,ECHO 秒消化,非瓶颈。
- **预期真瓶颈**:并发到期下 cron_fire 写入竞争 或 PG 连接池 —— 由实测定夺。

## R6. 清理与隔离

- `teardown` 已支持(offline + delete wf/task);历史 instance 保留(观测依据),用 RUN_TAG 区分本次。
- worktree 隔离(`dw-044-cron-perf`):main 被 043 占,按 SDD 硬规则隔离;worktree feature.json=044,main=043,指针未互偷。
- 秒级 cron 产生大量实例:测试结束 teardown,必要时手动 truncate cron_fire(归档保留期内不删,但测试可放宽 `cron-fire-retention-days`)。

## R7. 实测结论(2026-07-05,distributed + 50wf `*/10 * * * * *`)

**US1 正确性 ✓**:
- 607→970 个 `trigger_type=CRON` 实例**全 SUCCESS**(SC-001,成功率 100% ≥ 99%)
- 去重核对 `重复数 = 0`(每个 workflow_id+scheduled_fire_time 恰好 1 实例,SC-002)
- `cron_fire` 行数 = CRON 实例数(1:1,fire 记录与实例创建一致)

**US2 量化**:
- 50wf 规模:触发延迟 p99 = **0.248s**(到期→实例创建,scan+arm+fire 链路)
- 触发吞吐 ~1–3 实例/s —— **远低于 50wf/10s = 5/s 的预期**,暴露触发节流(见 US3)

**US3 瓶颈定位(SC-004)**:
- 最先饱和环节 = **cron 触发器(cron-trigger 线程 + cron_fire 写入)**
- 证据:`slot_utilization=0.1`(执行端只用 10%,worker 闲)、`queue_depth=0`、`dispatch_count` m1/m2 = 525/563(均衡)、`task_duration max=0.025s`(ECHO 秒完)→ 执行/dispatch/worker 全不饱和
- 但 `cron_fire` 增长远低于 50wf/10s 预期 → cron-trigger **单线程串行 fire**(每 fire ~0.25s × 50 = 12.5s > 10s 间隔)节流触发吞吐;`claim_empty=618` 侧证执行端空等任务
- 双 master dispatch 均衡,`cron_fire` UNIQUE 去重 0 丢失

**阶段二优化方向(后续 feature)**:
1. **cron-trigger 并行化**(单线程 → 池,按 wf 分组并发 fire)
2. **批量 fire**(同触发点 N 个 wf 批量 INSERT cron_fire + 批量创建 instance)
3. **instance 异步创建**(fire 只记 cron_fire,实例创建丢入队列,ParallelDispatcher 消化)
