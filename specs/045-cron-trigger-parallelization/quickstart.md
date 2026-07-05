# Quickstart: cron 触发并发吞吐优化 压测手册

> Phase 1 产出。基于 044 cron-stress 扩展新指标观测。前置:044 已验证 cron 链路基本正确(6 字段 cron)。

## 前置(distributed 模式)

```bash
# 1. 启动基础服务 + distributed(045 worktree)
cd /home/wallfacers/project/dw-045-cron-parallel
docker compose up -d postgres redis minio neo4j
docker compose --profile distributed up -d

# 2. 确认双 master + 双 worker
curl localhost:8000/api/health && curl localhost:8200/api/health   # 均 200
curl localhost:8000/api/fleet                                       # worker-1/2 ONLINE

# 3. 准备压测脚本(从 044 worktree 拷贝)
mkdir -p tmp/cron-stress
cp /home/wallfacers/project/dw-044-cron-perf/tmp/cron-stress/cron-stress.sh tmp/cron-stress/
```

## 默认档压测(基线,PG=100 安全)

配置(application.yml 默认):timer=8 / worker=32 / HikariCP=40 / queue=4000。

```bash
# 建 50 个同 cron 工作流(*/10 * * * * * = 每 10s 一批)
./tmp/cron-stress/cron-stress.sh setup -n 50 -c '*/10 * * * * *'

# 观测(扩展列:queue/full/reconcile)
./tmp/cron-stress/cron-stress.sh cron-watch -m 2

# 核对(SC-001/002/003):
#   吞吐 ≥30 inst/s(对比 044 基线 1-3 inst/s,≥10x)
#   slot_utilization > 0.5
#   无重复 (workflow_id, scheduled_fire_time)
```

## 极限档压测(找天花板,需 PG=200)

```bash
# 1. docker-compose.yml postgres 加:
#      command: postgres -c max_connections=200
# 2. master×2 environment 追加:
#      SCHEDULER_CRON_FIRE_WORKER_THREADS: "64"
#      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "64"
#      SCHEDULER_CRON_FIRE_QUEUE_CAPACITY: "8000"
docker compose --profile distributed up -d dataweave-master dataweave-master-2

# 3. 加压到饱和
./tmp/cron-stress/cron-stress.sh setup -n 200 -c '*/10 * * * * *'
./tmp/cron-stress/cron-stress.sh cron-watch -m 3

# 记录(SC-006):最大吞吐 + 最先饱和指标(CPU / DB 连接 / 锁 / worker 池)
```

## 崩溃注入(SC-004 不丢触发)

```bash
# 1. 建 50wf + 触发中(队列有未消费 FireTask)
./tmp/cron-stress/cron-stress.sh setup -n 50 -c '*/10 * * * * *'
sleep 5

# 2. kill master-2(进程内队列 FireTask 丢)
docker kill dataweave-master-2 && docker start dataweave-master-2

# 3. 等 30s,核对 reconciler 补偿:
#    cron_fire instance_id IS NULL 的行最终回填
#    dw.cron.reconcile.replayed > 0
#    无重复 instance(幂等三层挡)
docker exec dataweave-postgres psql -U dataweave -c \
  "select count(*) from cron_fire where instance_id is null and created_at < now() - interval '60 second';"
```

## 不变量 + 幂等核对(SC-003/005)

```bash
# 幂等:同一触发点无重复 instance(应返回 0 行)
docker exec dataweave-postgres psql -U dataweave -c \
  "select workflow_id, scheduled_fire_time, count(*) from workflow_instance
   where scheduled_fire_time is not null
   group by 1,2 having count(*) > 1;"

# DEAD:应 0(除非持续故障注入)
docker exec dataweave-postgres psql -U dataweave -c \
  "select count(*) from cron_fire where status = 'DEAD';"

# 双 master dispatch 均衡(044 已验证模式)
DW_BASE=http://localhost:8000 ./tmp/cron-stress/cron-stress.sh metrics
DW_BASE=http://localhost:8200 ./tmp/cron-stress/cron-stress.sh metrics
```

## 清理

```bash
./tmp/cron-stress/cron-stress.sh teardown   # offline + delete wf/task
# cron_fire / workflow_instance 历史保留(观测依据),用 RUN_TAG 区分本次
```
