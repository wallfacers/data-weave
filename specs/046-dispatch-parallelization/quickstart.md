# Quickstart: dispatch 链路并行化优化验证

> 046 真跑验证步骤(复用 045 cron-stress,backup 在 `main tmp/cron-stress/`)。

## 前置

- distributed 后端(双 master + 双 worker)+ PG(`max_connections=200`)+ Redis
- 045 cron-stress 脚本(拷到 046 worktree `tmp/cron-stress/cron-stress.sh`,git-ignored)
- admin/admin 登录
- 045 触发层并行化已落地(物化 600 inst/s 喂给 dispatch)

## 真跑步骤

### 1. rebuild master + worker(046 改动)

```bash
cd backend && ./dev-install.sh                      # master application 改动(install 到 ~/.m2)
docker compose --profile distributed build master worker  # rebuild image(含 WebClient 超时 + worker ShellTaskExecutor)
docker compose --profile distributed up -d          # 重启
```

### 2. 健康核对

```bash
curl localhost:8000/api/health   # master-1
curl localhost:8200/api/health   # master-2
curl localhost:8000/api/fleet    # worker-1/2 ONLINE
```

### 3. 压测(复用 045 cron-stress,1000wf */2s 极限档)

```bash
cd tmp/cron-stress
./cron-stress.sh setup -n 1000 -c '*/2 * * * * *'    # 1000wf */2s
./cron-stress.sh cron-watch -m 3 -i 5                # 观测 dispatch 指标
```

### 4. 新断言核对(/actuator/prometheus,双 master)

- `dw.dispatch.queue.size`:稳态不持续涨(SC-005)
- `dw.dispatch.queue.full.count`:0 或可观测(非无限积压)
- `dw.dispatch.execute.latency`:分布(dispatch HTTP 耗时)
- `scheduler_dispatch_latency` p99 < 5s(**SC-001**,R9=227s max)
- dispatch 吞吐 ≥600 inst/s(**SC-002**,R9 SUCCESS 仅 ~17/s)

### 5. 幂等核对(SC-003)

```sql
-- task_instance DISPATCHED→SUCCESS 转换无重复(dispatch 无独立表,核对 task_instance 状态轨迹)
SELECT id, state, worker_node_code, attempt FROM task_instance
WHERE started_at > '<压测开始>' ORDER BY id DESC LIMIT 10;
-- 同一 task_instance 不应出现两次 DISPATCHED(无重复 dispatch)
```

### 6. 崩溃注入(SC-003 不丢)

```bash
docker kill dataweave-master-2 && docker start dataweave-master-2
# 等 30s → 核对 dispatchExecutor 残余 DispatchCommand casRequeue 回填
# task_instance 状态 DISPATCHED→WAITING(回退)→ 下一轮重派,无丢失
```

### 7. SC-002 slot_util 复测(045 遗留,rebuild worker 后)

```bash
./cron-stress.sh setup -n 50 -t SHELL --sleep 3 -c '*/30 * * * * *'   # 慢 task 压 slot
curl localhost:8000/actuator/prometheus | grep scheduler_slot_utilization
# 预期 slot_util > 0.5(worker slot 真占用,045 SC-002 因 worker image 旧 + dispatch 瓶颈掩盖未达成)
```

## 饱和探极限(SC-006)

加密 cron(`* * * * * *` 每秒)或加 wf(1500-2000),记录 dispatch 吞吐饱和点 + 最先饱和指标(dispatchExecutor 池 / DB 连接 / worker slot / 聚合 computeAndUpdate)。

## 验证基线对比

| 指标 | 045 R9 基线 | 046 目标 |
|---|---|---|
| dispatch_latency max | 227.77s | p99 < 5s |
| dispatch 吞吐(SUCCESS) | ~17/s | ≥600 inst/s |
| RUNNING 堆积(3min) | 159347 | 不堆积 |
| queue.size | N/A(无队列) | 稳态不涨 |
| slot_util | 0.0(掩盖) | >0.5(rebuild worker 后) |

## 拆卸

```bash
./cron-stress.sh teardown                            # 清理 1000wf
docker exec -e PGPASSWORD=dataweave dataweave-postgres psql -U dataweave -d dataweave \
  -c "TRUNCATE task_instance, workflow_instance, cron_fire RESTART IDENTITY;"
```
