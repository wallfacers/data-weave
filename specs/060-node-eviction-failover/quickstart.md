# Quickstart & Verification: 节点容错闭环

面向"证伪式真跑"验证（对齐 CLAUDE.md「调度下发验证」硬规则：任何 claim→dispatch→execute 路径改动须在**真实并发下发**下验证，非仅单测）。

## 前置

```bash
cd backend && docker compose --profile distributed up -d      # 2 master + worker-1 + worker-2 + PG/Redis
# 或最小：./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2  （单机验证门谓词/计数）
```

## US1 · 坏节点剔除 + 转移（切片 A）

1. **假节点不承接**：造一个 incarnation 反复变化的心跳（脚本每 10s 变 incarnation 上报 `node-fake`），提交每分钟 cron 工作流。
   - 期望：`SELECT worker_node_code, count(*) FROM task_instance GROUP BY 1` 中 `node-fake` = 0；实例只落 worker-1/2。（SC-002）
2. **在坏节点上转移**：把某实例 `worker_node_code` 指到即将"重启"的节点，触发 incarnation 变化。
   - 期望：该实例 `WORKER_RESTART` 回收 → 重派到健康节点 → SUCCESS；`business_attempt` **保持不变**（SC-001）。
3. **熔断**：让 worker-2 的执行端连续拒绝下发 ≥ 阈值。
   - 期望：`worker_nodes.quarantined_until` 被置未来；隔离期 worker-2 不承接；到期稳定后自动回归。
4. **毒任务**：造一个必崩 worker 的任务，令其 infra 重派超 `infra-redispatch-max`。
   - 期望：实例转 `SUSPENDED` + 告警；不再重派；未判 FAILED（SC-008）。

## US2 · 无节点等待 + 恢复抽干（切片 B）

1. 停掉全部 worker，提交任务。
   - 期望：实例停 `WAITING`；`business_attempt`/`infra_redispatch_count` 均 0；无 FAILED（SC-003）。
2. 恢复一个 worker。
   - 期望：`WAKE_CHANNEL` 触发，WAITING 实例在**一个兜底轮询周期内**开始执行；稳态无"就绪未认领"残留（`SELECT count(*) FROM task_instance WHERE state='WAITING' AND unmet_deps=0` → 0）。

## US3 · 长跑/流式/丢失（切片 C）

1. **长跑不误杀**：跑一个 sleep 明显 > 一个租约周期的进程内任务，worker 存活。
   - 期望：心跳持续带该实例 id → `renewLease` 命中 → 不被回收；跑到自然结束。（SC-004）
2. **状态丢失兜底**：`kill -9` 该 worker。
   - 期望：租约到期 → `WORKER_LOST` infra 回收 → 重派健康节点 → 完成，无人工介入。（SC-005）
3. **分区不双跑**：用 iptables/DROP 隔离 worker↔master（worker 进程仍活）。
   - 期望：master 重派新实例的同时，旧 worker 在 `self-fence-grace` 后自我中止；同一实例并发物理执行 = 0。（SC-006）
4. **Flink 流式 reattach**（依赖 059 Flink 执行器就绪）：提交 `long_running=true` 的 Flink SQL 流作业。
   - 期望：`task_instance.external_job_handle` 落 JobID；`kill` 承载 worker → 新 worker 按 JobID **reattach**（Flink 集群 `flink list` 中该 job 实例数 = 1，无重复提交）。（SC-007）
5. **max-runtime**：给一个有界任务配 `timeout_sec=5`，跑 sleep 30。
   - 期望：5s 后 `FAILED(TIMEOUT)`；`long_running` 任务不受此约束。
6. **人工兜底**：对一个 `SUSPENDED`/卡死实例 `kill` 再 `rerun`。
   - 期望：STOPPED→WAITING，`business_attempt/infra_redispatch_count/attempt` 全 0，重新执行。（SC-009）

## 单测 / 集成测试锚点

- `SlotManagerTest`：三谓词过滤（心跳/纪元/隔离）各命中与放行。
- `NodeHealthServiceTest`：原子自增 + `GREATEST` 单调；并发两线程更新结果与串行等价（FR-006）。
- `LeaseReaperTest`：infra 回收不动 `business_attempt`、`infra_redispatch_count+1`、超限转 SUSPENDED；WORKER_RESTART 真比对纪元。
- `RetryServiceTest`：业务重试改比 `business_attempt`；PREEMPTED 仍不计（回归）。
- `WorkerExecServiceTest`：running 集合 add/remove；`abortAll` 只中止进程内、豁免 long_running。
- `FlinkReattachTest`：detached 提交解析 JobID；reattach 路径不 `flink run`；有界作业 exit-code 保真（constitution III）。
- **真跑门禁**：every-minute cron 端到端，确认 `started_at − created_at ≈ 0`、root attempt=1、零 `跳过下发/中止执行` straggler（CLAUDE.md）。

## 回归红线（不得破坏）

- `isCurrentDispatch` 栅栏语义 / `attempt` 单调（936d/44ea 两次修复不得回退）。
- 048/049/051 认领稳态（round 时长、dispatch 速率不退化）。
- backend 测试隔离不变量（H2 唯一库、`@DirtiesContext`、seed 不漂移）。
