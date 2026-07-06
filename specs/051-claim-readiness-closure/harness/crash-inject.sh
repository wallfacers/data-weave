#!/usr/bin/env bash
# crash-inject.sh — 051 US2 / T026 崩溃注入真跑编排（收口 046 T017 / 048 T013 / 049 T013）
# 用法：./crash-inject.sh [crontab-n-default-1000]
# 依赖：US1 已进 master 镜像 + distributed profile 已起 + tmp/cron-stress/cron-stress.sh
# 详见 crash-injection-runbook.md。核对口径依赖 audit.md §3-F1 裁决结果。

set -u   # 不用 -e：核对类脚本，grep 无匹配返回非零不应中断
N="${1:-1000}"
PG_CONTAINER="dataweave-postgres"
PG_USER="dataweave"
PG_DB="dataweave"
MASTER2="dataweave-master-2"
TS="$(date -Is)"

psql_exec() {
  docker exec -e PGPASSWORD="$PG_USER" "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1"
}

log() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }

# ── 0. 前置健康核对 ──────────────────────────────────────────
log "0. 前置健康核对"
curl -sf localhost:8000/api/health >/dev/null && echo "master-1 OK" || { echo "master-1 DOWN — 终止"; exit 1; }
curl -sf localhost:8200/api/health >/dev/null && echo "master-2 OK" || { echo "master-2 DOWN — 终止"; exit 1; }
echo "readiness 指标注册核对："
curl -s localhost:8000/actuator/prometheus | grep -cE 'readiness_signal_pending|readiness_drift_corrected' \
  | xargs -I{} echo "  readiness_* 指标命中数={}（>0 才说明 US1 已进镜像）"

# ── 1. 起负载 ────────────────────────────────────────────────
log "1. 起负载 setup -n $N"
CRON_DIR="${CRON_STRESS_DIR:-tmp/cron-stress}"
[ -x "$CRON_DIR/cron-stress.sh" ] || { echo "缺 $CRON_DIR/cron-stress.sh（见 046 quickstart）— 终止"; exit 1; }
( cd "$CRON_DIR" && ./cron-stress.sh setup -n "$N" -c '*/2 * * * * *' )
echo "等 60s 跑稳..." ; sleep 60

log "1b. 崩溃前基线计数"
psql_exec "SELECT 'pending_signals', COUNT(*) FROM readiness_signal WHERE processed=0
           UNION ALL SELECT 'waiting_total', COUNT(*) FROM task_instance WHERE state='WAITING' AND deleted=0
           UNION ALL SELECT 'waiting_unmet_gt0', COUNT(*) FROM task_instance WHERE state='WAITING' AND unmet_deps>0 AND deleted=0;"

# ── 2. 崩溃注入（×2，命中"认领已提交/下发未完成"窗口）────────
log "2. 崩溃注入窗口 1"
echo "kill@$(date -Is)"; docker kill "$MASTER2"; sleep 2
echo "start@$(date -Is)"; docker start "$MASTER2"; sleep 10

log "2b. 崩溃注入窗口 2"
echo "kill@$(date -Is)"; docker kill "$MASTER2"; sleep 2
echo "start@$(date -Is)"; docker start "$MASTER2"
echo "等 30s 让 Maintainer/Reconciler/casRequeue 收敛..."; sleep 30

# ── 3. 四项核对 ──────────────────────────────────────────────
log "3.① 无重复 dispatch — worker 幂等键日志"
docker logs worker-1 2>&1 | grep -iE 'duplicate|already running' | head -5 || true
docker logs worker-2 2>&1 | grep -iE 'duplicate|already running' | head -5 || true
echo "（无输出=无重复执行证据）"

log "3.② 不丢 — 滞留 DISPATCHED + 信号收敛"
echo "stranded_dispatched（应趋 0）："
psql_exec "SELECT COUNT(*) FROM task_instance WHERE state='DISPATCHED' AND lease_expire_at < NOW() AND deleted=0;"
echo "readiness_signal 处理分布（unprocessed 应趋 0）："
psql_exec "SELECT 'unprocessed='||SUM(CASE WHEN processed=0 THEN 1 ELSE 0 END)
                  ||' processed='||SUM(CASE WHEN processed=1 THEN 1 ELSE 0 END)
           FROM readiness_signal WHERE created_at > '$TS';"

log "3.③ 无 deadlock — 日志 grep（应空）"
docker logs dataweave-master   2>&1 | grep -iE 'deadlock|lock wait timeout|livelock' | head -5 || true
docker logs "$MASTER2"         2>&1 | grep -iE 'deadlock|lock wait timeout|livelock' | head -5 || true
echo "（无输出=无死锁证据）"

log "3.④ unmet 收敛 — 永久卡 WAITING 计数（应 0 或仅真未就绪）"
psql_exec "SELECT COUNT(*) FROM task_instance
           WHERE state='WAITING' AND unmet_deps>0 AND deleted=0
             AND updated_at < NOW() - INTERVAL '5 minutes';"

echo "readiness 自愈/时效指标："
curl -s localhost:8000/actuator/prometheus \
  | grep -E 'readiness_drift_corrected|readiness_signal_pending|readiness_signal_lag.*quantile.*0\.99' | head -5

# ── 4. 收尾提示 ──────────────────────────────────────────────
log "4. 完成 — 把以上输出 + §5 长跑 5-10min metrics 回贴交主 Claude"
echo "长跑核对：继续 ./cron-stress.sh cron-watch -m 5 -i 5，确认 round_duration/queue.size 稳态、日志无 deadlock。"
echo "拆卸：cd $CRON_DIR && ./cron-stress.sh teardown"
