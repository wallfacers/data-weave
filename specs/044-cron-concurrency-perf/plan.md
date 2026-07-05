# Implementation Plan: Cron 并发触发链路验证与性能基准

**Branch**: `044-cron-concurrency-perf` | **Date**: 2026-07-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/044-cron-concurrency-perf/spec.md`

## Summary

修复测试脚本 cron 表达式约定(5 字段 → Spring 6 字段),让 cron 自动触发链路首次被有效验证;新增 cron 专项观测(定时实例增量、cron_fire 撞键、双 master 触发分布);以秒级 cron + 调短扫描间隔产生持续并发触发负载,量化正确性 / 吞吐 / 延迟并定位瓶颈。**不改后端代码**,仅改测试脚本 + docker-compose env。

## Technical Context

**Language/Version**: Bash(测试脚本) + Java 25 / Spring Boot 4(后端,只读观测、不改)

**Primary Dependencies**: curl + (jq | python3)(脚本);后端既有 Spring `CronExpression`、HikariCP、PostgreSQL、Redis、Micrometer

**Storage**: PostgreSQL(只读观测 `workflow_instance` / `cron_fire` / `task_instance`)

**Testing**: 实际压测 —— 脚本驱动真实 distributed 后端,观测 `/actuator/prometheus` + DB 表 + 双 master 指标对比

**Target Platform**: Linux / WSL2 + Docker(distributed profile:master×2 + worker×2)

**Project Type**: 测试 / 压测工具 + 运行环境配置(非产品功能)

**Performance Goals**(验证目标):50 个同 cron wf 跨 ≥3 触发点定时触发成功率 ≥99%;50–100 并发到期下给出每触发点吞吐 + p99 延迟基线;定位最先饱和环节

**Constraints**: 不改后端代码;仅 env 覆盖扫描参数;ECHO 任务隔离执行端;测后清理

**Scale/Scope**: 10–100 并发到期 wf;ECHO 任务(单节点 DAG);双 master 触发分布观测

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

本 feature 为**性能验证 / 压测**,不引入产品功能改动,对五条原则:

| 原则 | 检查 | 结果 |
|---|---|---|
| I Files-First | 不涉及定义文件格式 | N/A ✓ |
| II Server is Source of Truth | 不改进度 / 版本 / 隔离 | N/A ✓ |
| III Two-Legged Debugging | 不涉及 CLI 本地 runtime | N/A ✓ |
| IV AI Lives in Local Agent | 不涉及 AI 大脑 | N/A ✓ |
| V Reuse the Kernel | **复用**现有调度内核(CronScheduler / TriggerEngine / cron_fire / SKIP LOCKED / ParallelDispatcher),仅测试不重写 | ✓ 完全符合 |
| Teardown 不损观测 / 内核 | teardown 只清测试 wf/task/实例,不动 actuator/metrics/调度内核 | ✓ |

**Gate 通过,无违反。** Phase 1 设计后复检:本 feature 不产生新代码 / 新表 / 新 API,复检维持通过。

## Project Structure

### Documentation (this feature)

```text
specs/044-cron-concurrency-perf/
├── spec.md
├── plan.md              # 本文件
├── research.md          # Phase 0
├── data-model.md        # Phase 1(既有实体观测清单)
├── quickstart.md        # Phase 1(压测操作手册)
├── contracts/           # Phase 1(测试依赖的既有 API)
└── checklists/requirements.md
```

### Source Code (repository root)

```text
tmp/cron-stress/              # 压测脚本(git-ignored;worktree 内)
├── cron-stress.sh            # 改:cron 默认 6 字段;新增 cron-watch 子命令
└── README.md

docker-compose.yml            # 改:master×2 environment 追加扫描间隔 env(仅测试加速,纯 env 覆盖)
```

**Structure Decision**:本 feature **无产品源码改动**。交付物 = 测试脚本(tmp/cron-stress,git-ignored)+ docker-compose env 覆盖(仅 master 扫描间隔,加速迭代)。后端 Java 代码零改动。

## Complexity Tracking

无 Constitution 违反,本节留空。
