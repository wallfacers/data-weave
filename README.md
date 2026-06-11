# DataWeave

> **用 Agent 编织数据** —— AI Agent 原生的数据中台。
> 用户用自然语言提出数据需求，Agent 自主操控任务开发、调度、指标、血缘等全流程操作。

继承 DataWorks / DolphinScheduler 的调度、开发、治理能力，但交互范式升级为 AI Agent 驱动。

## 仓库结构

```
data-weave/
├── frontend/        # Next.js (App Router) + shadcn/ui + CopilotKit v2，独立项目
│   └── DESIGN.md    # 设计系统真相源（@google/design.md 格式）
├── backend/         # Spring Boot 4 + Java 25，Maven 四模块（api/master/worker/alert）
├── docs/            # 架构设计文档
├── openspec/        # OpenSpec：spec-driven 工作流（需求/MVP 归宿）
└── docker-compose.yml  # 生产态 PostgreSQL + Redis（开发态用内置 H2，可不启）
```

- **架构设计**：见 [`docs/architecture.md`](docs/architecture.md)
- **MVP 需求**：见 OpenSpec change [`openspec/changes/dataweave-mvp/`](openspec/changes/dataweave-mvp/)

## 技术栈

| | 选型 |
|---|---|
| 前端 | Next.js (App Router) · shadcn/ui · CopilotKit **v2**（直连 AG-UI，不跑 Node Runtime）· next-themes |
| 后端 | Java 25 · Spring Boot 4.0（Jackson 3）· WebFlux（AG-UI SSE）· Maven 多模块 · DDD 四层 |
| 存储 | PostgreSQL（生产）/ H2（开发零依赖）· Redis · MinIO（distributed 日志归档） |
| 调度 | 多 master 对等 + SKIP LOCKED 认领 + 事件驱动快路径 + 软抢占 + cron 护栏表防重 |
| 指标 | Micrometer + Actuator（调度四层指标：性能/资源/管道/SLA），`/api/ops/metrics` + Prometheus |
| Agent | 双模式 mock（IntentRouter）/ workhorse（真 LLM 经桥接层） |

## 快速开始

### 后端
```bash
cd backend
./mvnw install -DskipTests                       # 先把四模块装进本地仓库（首次/改动后）
./mvnw -pl dataweave-api spring-boot:run         # 默认 H2，零外部依赖
# AG-UI 端点：POST http://localhost:8080/agui  ·  健康检查：GET /api/health
```
> 单模块 `spring-boot:run` 需要 sibling 模块的 jar，故先 `install`。

### 前端
```bash
cd frontend
pnpm install
pnpm dev                                         # http://localhost:3000 → /agent
```
前端通过 `NEXT_PUBLIC_AGENT_URL`（默认 `http://localhost:8080/agui`）连后端。

### 调度器模式

默认 `all-in-one`（单 JVM 内 master+worker，H2 + 内存总线 + 本地文件归档），克隆即跑。生产态切 `distributed`：

```bash
# distributed 模式（PG + Redis + MinIO + 独立 master/worker 进程）
docker compose --profile workhorse up -d
cd backend && ./mvnw -pl dataweave-api spring-boot:run \
    -Dspring-boot.run.profiles=pg \
    -Dspring-boot.run.arguments=--scheduler.mode=distributed
```

配置项：`scheduler.mode`、`scheduler.poll-interval-ms`、`scheduler.claim-batch-size`、`scheduler.cron-misfire`、`cluster.auth.token`（见 `application.yml`）。

### 系统指标

调度内核四层可观测指标（性能/资源/管道/SLA）经 Micrometer 暴露：

- **后端 REST**：`GET /api/ops/metrics`（JSON 快照，前端指标看板用）
- **Actuator**：`GET /actuator/metrics` / `GET /actuator/prometheus`（Prometheus 刮取）
- **前端**：Workspace → "系统指标" 视图（Pinned 底座，开箱可见）

### 接真实库（可选）
```bash
docker compose up -d                             # 启 PostgreSQL + Redis
cd backend && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=pg
```

## MVP 能力（试一试）

打开 `/agent`，输入（已预置种子：GMV 指标、orders 表、每日 GMV 任务）：

- 「GMV 是多少」—— 指标查询 + 口径溯源
- 「orders 表有多少条」—— Text-to-SQL
- 「创建一个任务，每天 8 点执行 select count(*) from orders，结果存到 report 表」—— 任务调度
- 「GMV 受哪些表影响」—— 数据血缘

## 设计系统

主题（亮/暗 oklch）以 `frontend/DESIGN.md` 为真相源：

```bash
cd frontend
pnpm design:lint       # 校验
pnpm design:export     # 导出 CSS 变量 / Tailwind
```
实际生效的变量在 `frontend/app/globals.css`（由 shadcn preset 生成，取值与 DESIGN.md 一致）。

## spec-driven 工作流（OpenSpec）

```bash
openspec status --change dataweave-mvp     # 查看当前 change
/opsx:propose "你的新需求"                  # 发起新变更（在 Claude Code 内）
```
