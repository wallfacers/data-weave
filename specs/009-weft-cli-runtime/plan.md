# Implementation Plan: Weft 子特性 D —— CLI 同步命令 + 本地轻量 runtime

**Branch**: `009-weft-cli-runtime` | **Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/009-weft-cli-runtime/spec.md`

## Summary

D 给 C 的服务端同步能力补上人类可操作入口,并落地宪法原则 III「本地两条腿调试」:① `dw pull/push/diff` 直打 C 的 `/api/projects/{id}/pull|push|diff`,把项目在服务器与本地文件树间往返;② `dw run` 本机真跑 —— 复用 `dataweave-worker` 真实执行器(本期经 Java 本地 runner 子进程,代码级一致,宪法 v1.1.0 已追认),Go CLI 子进程调起、管道直出、透传退出码;③ `dw run --test` 复用既有 `POST /api/tasks/{id}/run`(gated TEST_RUN)+ 日志 SSE。新增:Go CLI 子命令、Java `LocalRunMain` 入口、worker `PythonTaskExecutor`、本地 `.weft/` 配置(git-ignored)。**零新服务端同步端点、B/C 零修改。**

## Technical Context

**Language/Version**: Go(`cli/`,沿用现有 `cli/go.mod`)+ Java 25(`dataweave-worker` 本地 runner + PythonExecutor)

**Primary Dependencies**: Go 标准库 + 现有 CLI 基座(`cli/main.go` switch 分发、`DW_API`/`DW_TOKEN`);Java 侧复用 `dataweave-worker` 执行器(`TaskExecutor`/`ExecutionContext`/`AbstractTaskExecutor`)

**Storage**: 无新增 DB。本地文件:工作副本文件树 + `.weft/state.json`(baseline+projectId)+ `.weft/datasources.local.yaml`(凭据,git-ignored)

**Testing**: Go `go test`(CLI 解析/定位/退出码);Java JUnit5 + AssertJ(PythonExecutor、LocalRunMain 与服务器执行器的退出码/输出一致性 = 黄金对照)

**Target Platform**: 开发者本机(Linux/macOS;本期 `dw run` 前置本机有 JVM + python3)

**Project Type**: CLI(Go 二进制)+ 复用后端执行器(Java 本地 runner)

**Performance Goals**: `dw run` 本机执行延迟 ≈ 直接跑脚本(子进程开销可忽略);`dw pull/push` 受网络与 bundle 大小约束,非热路径

**Constraints**: 凭据绝不上行;`dw run` 退出码/stdout-stderr/超时 100% 等同服务器执行器(代码级复用保证);同步 round-trip 复用 B 字节稳定

**Scale/Scope**: 单开发者本地;一个项目数十~数百任务定义量级

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

宪法 **v1.1.0**(2026-06-27 已修订原则 III),逐条核对:

- **I. Files-First** ✅ 同步搬运 B 文件契约;`dw run` 读文件树定义。
- **II. Server is Source of Truth** ✅ pull/push/diff 经 C;push 幂等覆盖+快照+baseline 乐观并发;无双向合并;凭据不入文件/git。
- **III. Two-Legged Debugging** ✅(**phased**)本期复用真实 Java 执行器子进程取代码级一致(原则 III v1.1.0 phased 条款已**显式追认**此路径,需本机 JVM);TEST 腿复用既有端点。Go 原生 runtime = future target。**无违规**——偏离已在宪法层追认,不入 Complexity Tracking。
- **IV. AI Lives in Local Agent** ✅ D 不碰服务端 AI;不损观测(复用 ops 日志流)。
- **V. Reuse the Kernel** ✅ 复用执行器/快照/C 同步/TEST 调度;`PythonTaskExecutor` 是 worker 增量新增,非重写。

**结论:PASS**(原则 III 偏离已由 v1.1.0 追认)。Phase 1 设计后复核:结构未引入新违规,维持 PASS。

## Project Structure

### Documentation (this feature)

```text
specs/009-weft-cli-runtime/
├── plan.md              # 本文件
├── research.md          # D1–D7 决策
├── data-model.md        # 实体 + runner 子进程契约 + 本地配置 schema
├── quickstart.md        # build → pull → run → diff → push → run --test
├── contracts/           # dw 命令面 + runner 子进程契约 + 复用的服务端端点引用
└── tasks.md             # speckit-tasks 产出(本命令不建)
```

### Source Code (repository root)

```text
cli/                                  # Go 单二进制(现有基座)
├── main.go                           # 现有 switch 分发,新增 pull/push/diff/run case
├── sync/                             # 新增:pull/push/diff(HTTP→C 端点 + 文件树落地/读取)
│   ├── pull.go  push.go  diff.go
│   └── workcopy.go                   # .weft/state.json 读写、文件树 I/O、baseline
├── run/                              # 新增:本地真跑 + TEST 提交
│   ├── local.go                      # 轻读任务元数据 → 调起 Java LocalRunMain 子进程 → 管道/退出码
│   ├── testrun.go                    # POST /api/tasks/{id}/run + 日志流 SSE 消费
│   └── datasource.go                 # .weft/datasources.local.yaml 解析 → 连接串
└── *_test.go                         # Go 单测

backend/dataweave-worker/src/main/java/com/dataweave/worker/
├── infrastructure/PythonTaskExecutor.java   # 新增(type()=="PYTHON")
├── localrun/LocalRunMain.java                # 新增:独立入口,脱离 master,复用执行器
└── localrun/LocalRunArgs.java                # 新增:子进程入参(type/content/ds/timeout)解析

backend/dataweave-worker/src/test/java/com/dataweave/worker/
├── infrastructure/PythonTaskExecutorTest.java
└── localrun/LocalRunMainParityTest.java      # 黄金对照:同脚本 runner vs 服务器执行器退出码/输出一致
```

**Structure Decision**: 双语言:Go CLI(`cli/`,沿用现有单二进制 + switch 分发,新增 `sync/`、`run/` 子包)消费 C 的 HTTP 同步端点并管理本地工作副本;Java 本地 runner(置于 `dataweave-worker` 内 `localrun/` 子包,仅依赖 worker 执行器,**不耦合 master/filecontract**)经子进程被 CLI 调起以取得执行代码级一致。`PythonTaskExecutor` 增量加入 worker,服务器与本地共享。

## Complexity Tracking

> 无需填写 —— Constitution Check 全 PASS。原则 III 的 Java 执行器偏离已由宪法 v1.1.0 phased 条款**正式追认**,不构成待证违规。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | — | — |
