# API Contracts: cron 触发并发吞吐优化

> Phase 1 产出。本 feature **无新增 HTTP 端点**;改动是后端调度内核 + schema,对外契约不变。观测通过现有 actuator + 新 metrics 暴露。

## 无新端点(复用现有)

| 端点 | 用途 | 本 feature 关系 |
|---|---|---|
| `GET /api/health` | 健康检查 | 不变 |
| `GET /api/ops/workflow-instances?size=N` | 实例列表 | 观测触发实例创建(筛 `triggerType=CRON`) |
| `GET /api/fleet` | 节点拓扑 | 不变(双 master + 双 worker) |
| `GET /actuator/prometheus` | metrics 抓取 | **新 metrics 经此暴露** |
| `GET /api/ops/metrics` | JSON metrics 聚合 | 新 metrics 一并聚合 |

## 新增 metrics(经 /actuator/prometheus 暴露)

| metric | 类型 | 说明 | 找极限用途 |
|---|---|---|---|
| `dw.cron.fire.queue.size` | gauge | 触发队列当前深度 | 背压信号(持续涨 = worker 跟不上) |
| `dw.cron.fire.queue.full.count` | counter | 满队列降级次数 | >0 = 触发背压传导 |
| `dw.cron.fire.execute.latency` | timer | fireExecute 物化耗时分布 | 物化瓶颈定位 |
| `dw.cron.fire.arm.latency` | timer | fireArm 耗时分布 | timer 线程健康 |
| `dw.cron.reconcile.replayed` | counter | reconciler 补偿重试次数 | 无崩溃应=0 |
| `dw.cron.reconcile.skipped` | counter | reconciler 幂等跳过(已有 instance) | 幂等生效证据 |
| `dw.cron.reconcile.dead` | counter | reconciler 标 DEAD(超时失败) | 应=0(除非持续故障) |

## 复用 044 观测指标

- `dw.cron.trigger.latency`(due → instance 创建延迟)
- `slot_utilization`(执行端利用率,目标 >0.5)
- `dispatch_count_total`(双 master 触发分布均衡度)

## 压测脚本契约(git-ignored,复用 044)

`tmp/cron-stress/cron-stress.sh`(从 044 拷贝 + 扩展):
- `cron-watch` 子命令扩展输出列:`queue.size` / `full.count` / `reconcile.replayed|skipped|dead`
- 经 `GET /actuator/prometheus` 抓取上述 metrics(双 master 分别抓 :8000 / :8200)
- DB 直查 `cron_fire`(status 分布)+ `workflow_instance`(幂等核对 `(workflow_id, scheduled_fire_time) count>1`)
