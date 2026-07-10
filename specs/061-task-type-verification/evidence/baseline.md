# T005 保真回归基线（059 既有单测 + SKIPPED 闭环）

**目的**：钉死 061 动工前的 worker 测试基线；后续任何缺陷修复（T017/T028/T038）与合并（T043）须对比此基线**零退化**。

## 基线（2026-07-10，禁 build-cache 真跑）

```
命令: cd backend && ./mvnw -pl dataweave-worker test -Dmaven.build.cache.enabled=false
结果: Tests run: 173, Failures: 0, Errors: 0, Skipped: 0
构建: BUILD SUCCESS
```

- 执行器测试类 18 个（含 `FlinkTaskExecutorTest` 33、`SqlTaskExecutorTest`/`HiveTaskExecutorTest`/`DataXTaskExecutorTest`/`SeaTunnelTaskExecutorTest`/`SparkTaskExecutorTest`/`PythonTaskExecutorTest` 等）。
- 覆盖 059「命令构造纯函数 + 缺引擎→SKIPPED」两路径——**真实 SUCCESS 路径正是 061 待补**。
- 首次 `-q` 版 exit=0 但摘要被吞；本基线以禁缓存复跑取真 `Tests run` 数（遵仓库 build-cache 假绿教训）。

## 复核口径（T043）

合并三工作流后重跑同命令，`Tests run` 应 ≥ 173（缺陷修复新增测试只增不减），零 Failures/Errors。
