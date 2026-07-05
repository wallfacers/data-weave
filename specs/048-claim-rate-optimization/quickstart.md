# Quickstart: claim 速率深优化 验证

## 1. 单元/集成测试(H2)

```bash
# WSL2 长命令 detach(见 CLAUDE.md 硬规则)
cd backend/dataweave-master
# 增量单测(快)
mvn test -Dtest='SchedulerKernelTest,InstanceStateMachineTest' \
         -Dmaven.build.cache.enabled=false 2>&1 | tail -30
```

**核对**:
- 批量 crossCycleReady 就绪判定(deps Map + COUNT Map;含首周期豁免/prevBizDate 偏移/多 dep/非 CRON 直通)
- 批量 casDispatch:`updateCount == placements.size()`
- assign 三阶段:place 占槽 + 批量 cas + content 失败 casFailed
- idempotency:无重复 dispatch

**注意**:maven-build-cache 会 skip surefire 假绿(memory),必须 `clean` + `-Dmaven.build.cache.enabled=false`,只认 `Tests run: N>0`(参见 [[maven-build-cache-masks-tests]])。

## 2. 真机验证(distributed 双 master 1000wf `*/2s`)

复用 045/046 cron-stress 极限档:

```bash
# 启动 distributed 双 master(复用 046 docker-compose 配置)
docker compose -p data-weave --profile distributed up -d \
  --force-recreate dataweave-master dataweave-master-2

# 物化 1000wf */2s(复用 cron-stress setup)
bash tmp/cron-stress/cron-stress.sh setup -w 1000 -i 2s

# 抓指标(双 master)
bash tmp/cron-stress/cron-watch.sh   # scheduler_dispatch_latency / WAITING / queue.size
```

**SC 核对**:
- **SC-001**: `scheduler_dispatch_latency` p99 < 5s(R9=135s,R10 目标)
- **SC-002**: task_instance WAITING 不堆积(R9=67859,R10 稳态不涨)
- **不退化**: `dw.dispatch.queue.size` ≤18、`queue.full.count` = 0(046 成果)

## 3. 可靠性(US3,崩溃注入)

```bash
# 认领高负载运行中,杀 master-2
docker kill dataweave-master-2 && sleep 2 && docker start dataweave-master-2

# 核对重启后:
# - 无 task_instance 被重复 dispatch(DISPATCHED 唯一)
# - 无丢失(认领事务提交未下发的实例重派)
# - 无死锁(actuator/日志无 deadlock)
```

## 4. 饱和判定(US2,找认领上限)

加压到 `scheduler_*round_duration` / WAITING 不再改善,记录:
- 饱和认领吞吐(inst/s)
- 最先饱和资源(认领线程 / DB 连接 / worker slot / 聚合)

## 5. slot_util 复测(046/045 遗留)

claim 解除后 task 真到 worker;若 worker image 已 rebuild(ShellTaskExecutor 真跑 sleep),复测 slot_util > 0.5(045 SC-002 遗留)。
