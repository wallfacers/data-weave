# 062 实时任务运维 — savepoint 端到端真跑取证（TR）

062 US3 优雅停止（stop-with-savepoint）+ US4 从检查点续跑的**真实 Flink 集群**端到端取证。
与 061 `verification/061-task-types/` 隔离（自有 network/容器名 `dw062-*`、自有 savepoint 共享卷），
多 Agent 零覆盖。

## 为什么单独建 harness

061 的 `verify-flink-longrunning.sh` 只覆盖 061 SC-005（detached 提交→JobID→handle→REST 轮询→
cancel），**不覆盖 062 新增的 stop-with-savepoint / 检查点续跑路径**。且 savepoint 需 JM/TM 同路径
可达的共享目录（061 compose 无此卷），故 062 自建。

## 组成

- `compose.flink.yml` —— Flink 1.20 JM+TM，**JM/TM 挂共享 named volume `/savepoints`**（stop-with-savepoint
  的 targetDirectory 须两端同路径可达），JM REST 宿主 `:8083`，JM/TM 各 1.5g/2g（防 checkpoint OOM）。
- `jobs/streaming.sql` —— 无界 datagen(**random**，非 sequence——sequence 源 checkpoint 物化整段序列会 OOM)→blackhole。
- `scripts/verify-savepoint-e2e.sh` —— 服务端产品全链路驱动（见下）。
- `evidence/` —— 真跑证据（EVIDENCE.txt + verify-run.log + 面板截图）。

## 前置（host flink 客户端）

worker（backend all-in-one）经 `sql-client.sh` detached 提交，需 host 侧 Flink 客户端。
从已拉取的镜像取（版本精确一致，免 archive.apache.org 慢下载）：

```bash
docker cp dw062-flink-jm:/opt/java/openjdk /home/wallfacers/flink-jdk11   # Flink 1.20 需 JDK11（host JDK25 已移除 SecurityManager 不兼容）
docker cp dw062-flink-jm:/opt/flink        /home/wallfacers/flink
# bin/config.sh 顶部 export JAVA_HOME=/home/wallfacers/flink-jdk11
# conf/config.yaml: rest.address=localhost / rest.port=8083 / execution.target=remote
```

## 跑

```bash
cd verification/062-streaming-savepoint
docker compose -f compose.flink.yml up -d
docker exec -u root dw062-flink-tm chmod -R 777 /savepoints   # flink 用户写权限
docker exec -u root dw062-flink-jm chmod -R 777 /savepoints
# backend 已起（h2 all-in-one，:8000）
FLINK_HOME=/home/wallfacers/flink ./scripts/verify-savepoint-e2e.sh
docker compose -f compose.flink.yml down -v   # 跑完释放
```

> 注：每次跑前重启 backend 清 h2（否则上次 TEST 实例残留，harness 关键字匹配会锁到旧终态实例）。

## 全链路（服务端产品路径，非夹具）

① `dw push`(longRunning:true)→`task_def.long_running=TRUE` ② `dw run --test`→`triggerTestRun` 物化
`instance.long_running=TRUE`→下发 ③ worker `FlinkTaskExecutor` detached 提交→真 JobID→`external_job_handle`
回写实例 ④ `POST /streaming-tasks/{id}/stop`→`HttpFlinkSavepointClient` 真连 Flink REST stop-with-savepoint
→轮询 COMPLETED 取 location→写 `task_checkpoint`→CAS STOPPED ⑤ `GET /checkpoints` 检查点落库
⑥ Flink 侧作业 FINISHED（savepoint 停止非 cancel）⑦ `POST /resume`→CAS 转出 STOPPED+`resume_checkpoint_id`+保留句柄。

⑧ `POST /resume` 续跑经 savepoint 提交**全新** Flink 作业（新 JobID ≠ 旧）⑨ 新作业 Flink checkpoint
config `restored-from` = 旧作业 stop 的 savepoint —— **真从 savepoint 恢复计算状态**（D2）。

结果：**PASS=24 FAIL=0**（`evidence/EVIDENCE.txt`）。

## 真跑暴露并修复

- **D1（已修）** in-process/all-in-one 下发路径 `InProcessTaskExecutionGateway` 执行前未
  `CurrentExecution.bind(taskInstanceId)`→long_running 句柄不回写（distributed 路径早已绑定，两路径此前不对齐）。
  回归测试 `InProcessTaskExecutionGatewayBindTest`。
- **D2（已修）** `resumeFromCheckpoint` 记录 `resume_checkpoint_id` 但 `FlinkTaskExecutor` 此前无 savepoint
  恢复逻辑 → 续跑仅 reattach 旧作业（旧作业 FINISHED → 判 SUCCESS 而不恢复状态）。修复：下发链传播 savepoint
  路径（`SchedulerKernel` claim 关联 `task_checkpoint` → `DispatchCommand.resumeSavepointPath` →
  `EngineSubmitRef.savepointRestorePath` → 两 gateway → `WorkerExecController`）；`FlinkTaskExecutor`
  savepoint 恢复优先于 reattach（sql 前置 `SET 'execution.savepoint.path'`；jar 加 `flink run -s <path> -n`）；
  句柄回写清 `resume_checkpoint_id`（消费后 infra-redispatch reattach 新作业）。端到端 restored-from 决定性证据见 ⑨。
