# Data Model: Companion Workhorse 生产收口

**无新数据实体**。本 feature 是部署 / 基础设施收口,不涉及 schema 变更,**不 bump `schema_version`**(保持 0.21.0)。

## 沿用 071 已落地 schema(spec 0.21.0,已入 main)

- **patrol_routine**:巡检例程(领域 `TASK_FAILURE`/`MACHINE`/`DATA_QUALITY`/`CODE_QUALITY` + cron + scope + timeout);项目内按领域唯一。
- **patrol_run**:执行历史(状态机 `CLAIMED→RUNNING→SUCCEEDED/FAILED/TIMEOUT`,`UNIQUE(routine_id, scheduled_fire_time)` 幂等防重)。
- **patrol_report**:项目级共享汇报(severity `DANGER/WARN/OK/INFO`,status `UNREAD→READ→CLOSED`)。
- **companion_message**:管家会话(role `USER/AGENT/SYSTEM`,`report_id` 锚定汇报上下文)。

## 运行态实体(非持久化,容器/进程)

- **workhorse sidecar 容器**(distributed 服务):承载推理的 go 进程,无状态(会话态在进程内/SQLite store,非平台 PG)。
- **mcp 连接**(workhorse → 后端 `/mcp`):工具通道,请求级无状态;tenant/project 由 token 绑定 + 工具参数传。

本 feature 不新增/修改上述任何实体的字段或关系。
