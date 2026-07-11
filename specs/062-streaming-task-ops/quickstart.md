# Quickstart: 实时任务运维（062）

> Phase 1 输出。本地验证实时任务面板与运维能力的步骤。

## 前置

- 后端：Java 25 + Spring Boot 4。零依赖用 H2（`-Dspring-boot.run.profiles=h2`），或 `docker compose up -d`（PG + Redis）。
- 前端：Node + pnpm。
- **实时任务执行**：依赖 long_running 流式引擎（Flink）。**061（Flink 真跑）未合 main 前**，savepoint/续跑的引擎侧能力不可用——本 quickstart 验证**不依赖 061 的部分**（US1 面板 / US2 日志 / US5 SUSPENDED 一等化 / US3·US4 骨架），savepoint 实际触发待 061。

## 启动

```bash
# 后端（H2，零外部依赖）
cd backend && ./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2   # :8000

# 前端
cd frontend && pnpm install && pnpm dev   # http://localhost:4000
```

## 准备一个 long_running 实时任务实例

需要一个 `task_def.long_running=TRUE` 的任务并产生实例。开发期可：

1. 用 061 的 Flink 任务定义（若 061 已在本地分支跑通），push 上线后触发；或
2. 临时构造：直接在 DB 插一条 `long_running=TRUE` 的 task_instance（state=RUNNING，external_job_handle 填一个占位 `{jobId, restEndpoint}`），用于验证面板/日志/SUSPENDED 呈现（不验 savepoint）。

```sql
-- H2/PG 临时构造（仅开发期验证 UI，非真实引擎）
UPDATE task_def SET long_running = TRUE WHERE name = '<某任务>';
-- 触发一个实例或手动 INSERT task_instance (state='RUNNING', long_running=TRUE, external_job_handle='{"jobId":"test","restEndpoint":"http://localhost:8081"}', started_at=now())
```

## 验证（按 US）

### US1 — 实时任务独立视图
1. 浏览器登录（admin/admin，localStorage 注入 `dw.auth.token`）。
2. 打开运维中心 `/`。
3. **预期**：在"任务实例"tab 之后看到第 5 个 tab"实时任务"；点开只列出 `long_running=TRUE` 的实例，含状态 badge / 已运行时长 / 重启次数。

### US2 — 最新日志
1. 在"实时任务"tab 点某 RUNNING 实例的"日志"按钮。
2. **预期**：复用 InstanceLogView，SSE 近实时刷新最新日志；断网重连后 `Last-Event-ID` 续传、不重复回放。
3. （长期）连续运行实例日志查看不卡顿——7 天级长期连接的资源/漂移在 plan 的 SSE 适配项处理。

### US3/US4 — 停止保留进度 / 续跑（骨架，061 未就绪）
1. 点"停止"（保留进度）。
2. **预期（061 未就绪）**：返回 `503 streaming.savepoint.unavailable`，面板提示"无法保留进度，可改用强制终止"；点"强制终止"走既有 `/kill` 成功。
3. **预期（061 就绪后）**：停止触发 savepoint → 写 task_checkpoint → 状态 STOPPED；续跑弹窗列出 N 个检查点，选一个 → 状态 WAITING → 调度 reattach 恢复。

### US5 — SUSPENDED 一等化 + 健康信号
1. 模拟底层故障：让某 RUNNING 实例的 infra_redispatch_count 超 `infra-redispatch-max` → 进 SUSPENDED（或临时 SQL 置 SUSPENDED）。
2. **预期**：实时任务面板以"已挂起"badge 明确标识（非误显示 RUNNING/FAILED）；可从此发起"恢复续跑"（优先续跑，无有效 ckpt 降级全量重跑）或"终止"。
3. 健康信号列：最近检查点状态、重启次数、workerOnline（断连呈现）。

## 测试

- 后端：JUnit5 + AssertJ。
  - `OpsServiceTest`：stopWithSavepoint（mock 引擎 savepoint）/ resumeFromCheckpoint（有效 ckpt 续跑 / 无效降级全量重跑 / SUSPENDED→WAITING CAS）/ rerunInstance 的 long_running 列重置。
  - `InstanceStateMachineTest`：`casResumeFromCheckpoint` 单赢语义、与 060 不变量兼容。
  - WebTestClient：`/api/ops/streaming-tasks*` 契约 + 项目隔离。
- 前端：vitest + 浏览器验证（实时任务 tab 渲染、操作按钮、日志 SSE 消费）。
- schema：0.16.0 升级在 H2（DDL 兼容）与 PG 双验；DROP+CREATE 幂等。

## 完成判据（映射 SC）

- SC-001：30 秒内从运维中心进实时任务 tab 定位目标实例。
- SC-002：最新日志 10 秒内刷新；7 天长期连接不退化。
- SC-003：停止→续跑不丢不重（**依赖 061**）。
- SC-004：无需培训区分运行中/已停止/已挂起。
- SC-005：SUSPENDED 100% 在面板标识并可转出。
