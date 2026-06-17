## Why

项目所有应用服务（后端 master/worker/workhorse、前端 Next.js）硬编码使用 8080/8081/3000 等通用默认端口，与本机其他开发服务（Tomcat 实例、Node 应用、各种 dev server）频繁冲突；且多 master、多 worker 多阶段部署场景下端口分配缺乏统一规则，host 映射和容器内端口混用，扩展时易错位。需要一次性的端口重规划，建立清晰、可扩展、按职能分段的端口契约。

## What Changes

- **重新划分应用端口段**：应用服务按职能用百位分段（master/api: 80xx、worker: 81xx、master-2: 82xx、workhorse: 83xx），前端 Next.js 移到 4000，避开常见默认端口。
- **统一多实例部署策略**：多 master、多 worker 采用"容器内同端口 + host 映射错位"策略（如 master-1 host 8000、master-2 host 8200，但容器内都是 8000）。
- **同步所有硬编码位置**：后端 `application.yml`、`@Value` 默认值、`CorsConfig` 白名单、前端 `NEXT_PUBLIC_AGENT_URL`、CLI 默认 `DW_API`、`deploy/workhorse/mcp.json`、`docker-compose.yml` 全部对齐新端口。
- **更新文档**：`CLAUDE.md`、`README.md`、`docs/architecture.md`、活跃 openspec 文档中的端口示例同步。

**BREAKING**：
- 本机已运行旧端口的开发者必须重启服务；`DW_API`、`NEXT_PUBLIC_AGENT_URL` 等环境变量的默认值变化会影响未显式设置的环境。
- 前端开发入口 URL 由 `http://localhost:3000` 变为 `http://localhost:4000`，浏览器书签、外部链接需更新。
- `CorsConfig` 白名单只放行新端口 `http://localhost:4000`，旧 3000 来源的请求会被浏览器 CORS 拦截。

## Capabilities

### New Capabilities

- `deployment-ports`: 项目应用端口分配策略与多实例部署的端口错位规则。覆盖：各服务默认端口、容器内/外端口映射原则、多 master/多 worker 扩展时的 host 端口错位方式、第三方基础设施端口保持行业标准的约定。

### Modified Capabilities

（无。现有 spec 均未在需求层面硬编码具体端口号，本次属配置层调整，不改变任何 spec 的行为要求。）

## Impact

- **后端配置**：`dataweave-api`/`dataweave-worker` 的 `application.yml`（`server.port`、`dataweave.master.url`）；`WorkerExecController`/`HeartbeatReporter` 的 `@Value` 默认值；`DistributedTaskExecutionGateway` 的 `DEFAULT_WORKER_PORT` 常量；`CorsConfig` 白名单。
- **后端测试**：`HealthAndCorsTest`、`HeartbeatReporterTest`、`DistributedTaskExecutionGatewayTest` 中的端口字面量。
- **前端**：`package.json` 的 `dev` 脚本（加 `-p 4000`）；`agent-chat.tsx` 的 `NEXT_PUBLIC_AGENT_URL` 默认值。
- **部署**：`docker-compose.yml` 所有 host 映射、`DATAWEAVE_MASTER_URL`、healthcheck URL；`deploy/workhorse/mcp.json` 中 MCP 端点 URL；`deploy/workhorse/config.yaml`（如有端口引用）。
- **CLI**：`cli/main.go` 的 `DW_API` 默认值与帮助文本。
- **文档**：`CLAUDE.md`、`README.md`、`docs/architecture.md`、`openspec/changes/task-core-capabilities/` 中的端口示例。
- **开发者本机**：已占用旧端口的进程需重启；环境变量依赖默认值的场景需知悉。
- **不影响**：第三方基础设施（PostgreSQL 5432 / Redis 6379 / MinIO 9000+9001）保持不动；AG-UI 协议事件结构、领域逻辑、调度算法、PolicyEngine 闸门均不变。
