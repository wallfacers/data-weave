# Quickstart: 认领就绪态物化 — 端到端验证

证明本 feature 工作的可跑验证场景。实现细节见 tasks.md；此处只给验证/运行指引。

## 前置

```bash
cd backend
# JDK 25（非交互 shell 需 export；见 memory backend-run-jdk25-and-profile）
# 改 master 后必先 dev-install 再 spring-boot:run，否则跑旧 jar
./dev-install.sh -pl dataweave-master -am
```

## 1. 编译门（每次改后）

```bash
cd backend && ./mvnw -q -pl dataweave-master compile   # 零错误
```

## 2. 单元 + 集成测试（H2 + PG 双跑，长跑 setsid 脱离）

```bash
# H2（零外部依赖）
setsid bash -c 'cd backend && ./mvnw -pl dataweave-master test -Dspring-boot.run.profiles=h2 \
  -Dtest="Readiness*Test,SchedulerKernelReadinessTest" >/tmp/rt-h2.log 2>&1; echo $? >/tmp/rt-h2.exit' </dev/null >/dev/null 2>&1 & disown
# PG（docker compose up -d 后）
setsid bash -c 'cd backend && ./mvnw -pl dataweave-master test \
  -Dtest="Readiness*Test,SchedulerKernelReadinessTest" >/tmp/rt-pg.log 2>&1; echo $? >/tmp/rt-pg.exit' </dev/null >/dev/null 2>&1 & disown
# 轮询：[ -f /tmp/rt-h2.exit ] && echo "DONE $(cat /tmp/rt-h2.exit)" || { echo running; tail -1 /tmp/rt-h2.log; }
```

**预期**：初值正确（宽 DAG/跨周期/首周期豁免/无依赖直通）；scoped 重算幂等（双跑同值）；STRONG/WEAK 语义；Maintainer 信号→重算→wake；Reconciler 注入漂移→自愈；认领只取 unmet_deps=0。

## 3. 端到端就绪流转（起后端手验）

```bash
setsid bash -c 'cd backend && ./mvnw -pl dataweave-api spring-boot:run >/tmp/api.log 2>&1' </dev/null >/dev/null 2>&1 & disown
# 造一个 A→B 强依赖工作流，触发实例：
#   ① B 物化时 unmet_deps=1（A 未完成）→ B 不被认领
#   ② A SUCCESS → readiness_signal(TERMINAL) 落库 → Maintainer 重算 B unmet_deps=0 → wake
#   ③ B 在 <3s 内被认领下发
curl -s localhost:8000/api/ops/metrics | grep -E 'readiness_signal_lag|unmet_ready_candidates'
```

**预期**：`readiness_signal_lag` p99 < 3s；B 在 A 完成后被及时认领。

## 4. 崩溃注入 + 四不变量（闭 046/048/049 三连欠）

```bash
# 复用 045/046 cron-stress + fault-injection（真跑多 master docker）
# setup -n 1000 起负载 → 认领事务提交后下发前：
docker kill dataweave-master-2 && sleep 2 && docker start dataweave-master-2
# 等 30s 后核对：
```

**预期（SC-004）**：① 无重复 dispatch（casDispatchBatch WHERE state=WAITING 恰好一次）② 不丢（readiness_signal 崩溃前已提交 → 重启续处理；已认领未下发实例 casRequeue 重派）③ actuator/日志无 deadlock ④ `unmet_deps` 崩溃后收敛权威值（`readiness_drift_corrected` 可能 >0 但自愈）。四不变量代码审计见 contracts/components.contract.md §四不变量。

## 5. SC-001 压测：就绪判定与堆积脱钩

```bash
# 1000wf */2s 跑稳后人为堆 WAITING 50万+，观察：
curl -s localhost:8000/api/ops/metrics | grep -E 'round_duration|unmet_ready_candidates|markClaimExtraWindow'
```

**预期**：`round_duration` 在 50万+ 堆积与早期一致（不随堆积退化，对比 049 的 49ms→1.58s 反弹应消除）；`markClaimExtraWindow`=0（窗口游标已删）；claim 吞吐 ≥ 物化速率、WAITING 稳态不涨（SC-002）。

## 6. US3 慢任务 slot_util（补容量证据）

```bash
# rebuild worker image，任务真跑 sleep（占槽数十秒）；密负载压测：
curl -s localhost:8000/api/ops/metrics | grep -E 'slot_utilization'
```

**预期（SC-003）**：加压到 claim 吞吐不再随负载提升（slot 成绑定约束）+ 稳态 `slot_utilization` ≥ 80% + 慢任务认领延迟无长尾饿死 + 就绪滞后 p99 < 3s。记录 slot 饱和点与瓶颈是否转移到 worker（SC-007）。

## 通过标准（对齐 spec Success Criteria）

| 场景 | SC |
|---|---|
| §2 单测双跑绿 | FR 全项 + H2/PG 兼容（FR-011） |
| §3 就绪流转 <3s | SC-005 |
| §4 崩溃注入 | SC-004、SC-006（三连欠闭合） |
| §5 堆积脱钩 | SC-001、SC-002 |
| §6 慢任务 slot_util | SC-003、SC-007 |
