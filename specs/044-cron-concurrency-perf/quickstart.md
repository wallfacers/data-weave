# Quickstart: cron 并发触发压测操作手册

## 前置

```bash
# distributed 后端已起(双 master + worker + PG/Redis)
docker compose --profile distributed up -d
curl localhost:8000/api/health        # → 200
curl localhost:8000/api/fleet         # 应看到 worker-1/2 ONLINE(任务才会真跑完)
```

## 1.(可选)加速扫描 — 秒级迭代

在 `docker-compose.yml` 的 `dataweave-master` 与 `dataweave-master-2` 的 `environment` 各追加:

```yaml
      SCHEDULER_CRON_SCAN_INTERVAL_MS: "2000"   # 15s → 2s
      SCHEDULER_CRON_LOOKAHEAD_MS: "3000"       # 30s → 3s
```

重建 master 容器(纯 env 覆盖,不 mvn package):

```bash
docker compose --profile distributed up -d dataweave-master dataweave-master-2
```

## 2. 跑压测

```bash
cd tmp/cron-stress
./cron-stress.sh health && ./cron-stress.sh login
./cron-stress.sh setup -n 50 -c '*/10 * * * * *'   # 50 个 wf,每 10s 到期
./cron-stress.sh cron-watch -m 1                    # 观测 1 分钟 cron 自动触发
```

双 master 触发分布对比:

```bash
./cron-stress.sh metrics                                   # master-1(8000)
DW_BASE=http://localhost:8200 ./cron-stress.sh metrics     # master-2(8200)
```

## 3. 核对成功标准

| 标准 | 核对方式 |
|---|---|
| SC-001 定时触发成功率 ≥99% | `trigger_type=CRON` 实例数 ≈ 50 × 触发点数 |
| SC-002 去重不丢点 | 每个触发点恰好 1 个实例;cron_fire 行数 = 触发点数 |
| SC-003 吞吐 + p99 延迟基线 | cron-watch 输出每触发点吞吐;`scheduled_fire_time→started_at` p99 |
| SC-004 瓶颈证据 | metrics 的 `scheduler_*` / `dispatch_*` / `hikaricp_*` 最先饱和项 |

## 4. 清理

```bash
./cron-stress.sh teardown      # offline + delete 本次 wf/task
```

历史 instance 保留(观测依据);如 cron_fire 膨胀影响后续,手动清理或调短 `cron-fire-retention-days`。

## 备注

- 不带 jq 时脚本自动退化 python3;`DW_BASE` / `DW_PROJECT_ID` 可环境变量覆盖。
- 入口 `/run` 吞吐优化、执行端优化、调度内核改动属**阶段二**,本阶段不做。
