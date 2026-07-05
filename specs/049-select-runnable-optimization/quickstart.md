# Quickstart: selectRunnable 优化 验证

## 1. EXPLAIN 回归（落地后,确认 Index Scan）

```bash
# NORMAL 候选:应 Index Scan + 无 Sort(去 NOT EXISTS 后)
docker exec dataweave-postgres psql -U dataweave -d dataweave -c \
  "EXPLAIN (ANALYZE, COSTS OFF) SELECT ti.id FROM task_instance ti WHERE ti.state='WAITING' AND ti.run_mode='NORMAL' AND ti.deleted=0 ORDER BY ti.updated_at ASC LIMIT 200 FOR UPDATE SKIP LOCKED"
# 核:Index Scan using idx_task_instance_claim,Execution < 5ms,无 Sort/Seq Scan
```

## 2. 单元/集成测试(H2)

```bash
cd backend/dataweave-master
# WSL2 detach
mvn test -Dtest='SchedulerKernelBatchUpstreamTest,SchedulerKernelBatchCrossCycleTest,InstanceStateMachineTest' \
         -Dmaven.build.cache.enabled=false 2>&1 | tail -30
```

**核对**:
- batchUpstreamReady:强 SUCCESS 就绪/未就绪、弱 SUCCESS|FAILED 就绪、无 edge 直通、多上游部分未就绪、多节点混合
- selectRunnable NORMAL/BACKFILL 分开(Index Scan 各自)
- 不退化 048(batchCrossCycleReady/casDispatchBatch 仍绿)

**注意**:maven-build-cache 假绿陷阱([[maven-build-cache-masks-tests]]):必 `clean` + `-Dmaven.build.cache.enabled=false`,只认 `Tests run: N>0`。

## 3. 真机验证(distributed 双 master 1000wf `*/2s`)

```bash
# rebuild master image(049 改了 SchedulerKernel)
mvn -f backend/pom.xml clean package -DskipTests -Dmaven.build.cache.enabled=false
docker compose -f docker-compose.yml -p data-weave --profile distributed build dataweave-master
docker compose -f docker-compose.yml -p data-weave --profile distributed up -d --force-recreate dataweave-master dataweave-master-2

# 物化 1000wf */2s(复用 cron-stress)
bash tmp/cron-stress/cron-stress.sh setup -n 1000 -c '*/2 * * * * *'
```

**SC 核对**(cron-watch + psql + prometheus):
- **SC-001**: `scheduler_round_duration_seconds` 稳定 < 30ms(WAITING 堆积后不退化;048 R10=0.3-1.6s)
- **SC-002**: task_instance WAITING 不堆积(048 R10=53 万),claim/dispatch ≥ 600 inst/s
- **不退化**: `dw.dispatch.queue.size` ≤18、`queue.full.count` = 0(046 成果)
- **EXPLAIN 回归**: selectRunnable Index Scan(无 Seq Scan/Sort)

## 4. 可靠性(US3,崩溃注入)

```bash
docker kill dataweave-master-2 && sleep 2 && docker start dataweave-master-2
# 核对:无重复 dispatch、无丢失、actuator/日志无 deadlock
```

## 5. 饱和判定(US2)

加压到 `scheduler_round_duration` / WAITING 不再改善,记录饱和认领吞吐(inst/s)+ 最先饱和资源。
