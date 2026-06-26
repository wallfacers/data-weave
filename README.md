# Weft

> **任务即代码 (Tasks-as-Code)** —— 开发者本地用 AI 编程 agent 开发任务/任务流，pull/push 往返服务器治理与运行。

继承 DataWorks / DolphinScheduler 的调度、开发、治理能力，交互范式升级为开发者本地 AI agent 驱动。

## 仓库结构

```
data-weave/
├── frontend/        # Next.js (App Router) + shadcn/ui，独立项目
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
| 前端 | Next.js (App Router) · shadcn/ui · next-themes |
| 后端 | Java 25 · Spring Boot 4.0（Jackson 3）· WebFlux · Maven 多模块 · DDD 四层 |
| 存储 | PostgreSQL（生产）/ H2（开发零依赖）· Redis · MinIO（distributed 日志归档） |
| 调度 | 多 master 对等 + SKIP LOCKED 认领 + 事件驱动快路径 + 软抢占 + cron 护栏表防重 |
| 指标 | Micrometer + Actuator（调度四层指标：性能/资源/管道/SLA），`/api/ops/metrics` + Prometheus |

## 快速开始

### 后端
```bash
cd backend
./mvnw install -DskipTests                       # 先把四模块装进本地仓库（首次/改动后）
./mvnw -pl dataweave-api spring-boot:run         # 默认 H2，零外部依赖
# 健康检查：GET /api/health
```
> 单模块 `spring-boot:run` 需要 sibling 模块的 jar，故先 `install`。

#### 本地构建提速（可选，但强烈推荐）

四模块全量编译较慢。本仓库已内置两项 **零配置共享** 的加速（`git pull` 即生效，无需各自配置）：

- **构建缓存** `backend/.mvn/extensions.xml`（Maven build-cache extension）：未改动的模块按内容 hash 命中缓存直接复用，不重编。任何人用 `mvnw`/`mvnd` 都自动启用（首次会从 Maven 仓库拉取该 extension）。
- **快速安装脚本** `backend/dev-install.sh`：自动优先用 mvnd（若已安装），跳过测试编译与 fat jar 打包。

```bash
cd backend
./dev-install.sh                            # 全量装四模块
./dev-install.sh -pl dataweave-master -am   # 只装改动的模块及其上游(更快)
mvnd -pl dataweave-api spring-boot:run      # 跑(未装 mvnd 用 ./mvnw)
```

**额外提速（各自本机装一次）**：装 [mvnd](https://github.com/apache/maven-mvnd)（Maven 守护进程，常驻 JVM 省冷启动 + 多核并行）——下载对应平台二进制解压、软链进 `PATH` 即可；mvnd 用 `JAVA_HOME` 选 JDK，可在 `~/.m2/mvnd.properties` 写 `java.home=<JDK25 路径>` 锁定 25。`dev-install.sh` 会自动探测到 mvnd 并使用，没装则回退 mvnw。

实测（12 核 / JDK25）：全量 `clean install` **76s → 12s**，增量改核心模块约 **18s**。

> ⚠️ `dev-install.sh` 的 skip 参数仅用于本地开发。**CI / 打部署包用 `./mvnw install`**（需跑测试 + 生成可执行 fat jar）。

### 前端
```bash
cd frontend
pnpm install
pnpm dev                                         # http://localhost:4000
```
前端通过 `NEXT_PUBLIC_API_URL` 连后端（默认 `http://localhost:8000`）。

### 调度器模式

默认 `all-in-one`（单 JVM 内 master+worker，H2 + 内存总线 + 本地文件归档），克隆即跑。生产态切 `distributed`：

```bash
# distributed 模式（PG + Redis + MinIO + 独立 master/worker 进程）
docker compose --profile distributed up -d
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
