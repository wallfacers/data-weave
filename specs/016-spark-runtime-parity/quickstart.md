# Quickstart: 深化执行 —— Spark 协议 + runtime 语义对齐 验证指南

三层验证（CI 确定性 + 本地真跑 + 服务端 TEST），对应原则 III fidelity 不变量。

---

## 第 0 层：编译 + 单测（CI，零外部依赖，无 Spark）

```bash
# 后端改动模块编译（WSL2 长跑须 setsid 脱离，见 CLAUDE.md）
cd backend && ./mvnw -q -pl dataweave-worker,dataweave-master,dataweave-api compile

# 执行层单测（H2，无 Spark → Spark 测试断言 SKIPPED + 命令构造）
setsid bash -c 'cd backend && ./mvnw -pl dataweave-worker test \
  -Dtest=SparkTaskExecutorTest,WorkerExecServiceDispatchTest,SqlTaskExecutorTest,LocalRunMainParityTest \
  >/tmp/.../016-build.log 2>&1; echo $? >/tmp/.../016-build.exit' </dev/null >/dev/null 2>&1 & disown
# 轮询：[ -f 016-build.exit ] && echo "exit=$(cat 016-build.exit)"

# CLI
cd cli && go build ./... && go test ./...
```

**预期**：
- `SparkTaskExecutorTest`：三 sparkMode 命令构造正确；无 SPARK_HOME → skipped=true；模拟失败 → exitCode≠0。
- `WorkerExecServiceDispatchTest`：SQL 任务在 distributed 路径选 SQL 执行器（非 SHELL）；构建完整 ctx。
- `SqlTaskExecutorTest`：无数据源 → **skipped=true**（不再 success 伪装）。
- `LocalRunMainParityTest`：SHELL/SQL/PYTHON/ECHO/SPARK 本地↔服务端逐项相等。
- 全绿 = SC-002/003/004/006。

## 第 1 层：本地真跑（装有 Spark 的开发机，手工/按需，不进 CI）

```bash
# 前置：SPARK_HOME 指向 Spark 发行版；datasources.local.yaml 配 spark 数据源 master: local[*]
dw pull <project>
# 写一个 pyspark 任务（参考 examples/sample-spark.task.yaml）
dw run my-spark-task          # 真提交 spark-submit --master local[*]，stdout 直出，退出码忠实
# 三形态各跑一遍（US3）：pyspark / spark-sql / jar
```

**预期**（SC-001/005）：
- 装 Spark：真跑出结果，成功 exit 0 / 作业失败 exit 6。
- 不装 Spark：`dw run` 输出"已跳过（本地无 Spark 环境）"，**不报错退出**。

## 第 2 层：服务端 TEST（yarn 漂移验证）

```bash
dw push                       # 上线（含删除→L2 审批挂起≠失败，照旧）
dw run --test my-spark-task   # 服务端 SPARK 数据源解析 master: yarn，集群真跑，日志回流
```

**预期**（SC-001）：同一份任务文件，服务端以 yarn 模式跑——本地/服务端差异仅来自数据源环境解析，任务文件零改动。

---

## 验收对照（SC → 验证层）

| SC | 验证 |
|----|------|
| SC-001 同一文件零改动漂移 | 第 1 层 local[*] + 第 2 层 yarn |
| SC-002 五类型 parity 100% | 第 0 层 LocalRunMainParityTest |
| SC-003 distributed 选对执行器 | 第 0 层 WorkerExecServiceDispatchTest |
| SC-004 环境缺失 100% SKIPPED 非伪装 | 第 0 层 SqlTaskExecutorTest + SparkTaskExecutorTest |
| SC-005 三形态可跑 + 失败/跳过可区分 | 第 1 层三形态 |
| SC-006 编译零错 + 无回归 | 第 0 层全套 |

## 关联坑位（避免重复踩）

- [[long-running-h2-workers-go-offline]]：跑 distributed/调度相关测试，心跳过期会致 worker 全离线、实例卡 WAITING——重启刷 seed 再验。
- [[backend-dev-server-setsid-detach]] + [[backend-run-jdk25-and-profile]]：长跑命令 setsid 脱离；非交互 shell 手动 export JDK25。
- [[h2-shared-mem-db-test-pollution]]：新增测试用独立库名防串台。
