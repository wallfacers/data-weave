## Context

项目现有应用端口集中在 8080/8081/3000 这类"通用默认值"段：后端 `dataweave-api`（也是 master-1）用 8080、worker 默认 8081、前端 Next.js 用 3000、workhorse-agent 用 8787。多 master 部署时 master-2 通过 host 端口 8082 映射到容器内 8080；多 worker 部署时 worker-1 host 8081 / worker-2 host 8083，容器内都是 8081。

问题：
1. 8080/3000 与 Tomcat、各类 Node dev server、IDE 插件等默认端口频繁冲突。
2. host 映射端口（8082/8083）与容器内端口（8080/8081）不同，但规则不统一（master 和 worker 的错位方式不对称），扩展时易配置错位。
3. 代码中 `@Value` 默认值、`CorsConfig` 白名单、`NEXT_PUBLIC_AGENT_URL`、CLI `DW_API`、`deploy/workhorse/mcp.json` 等多处硬编码同一端口，改端口要全网搜。

基础设施（PostgreSQL 5432 / Redis 6379 / MinIO 9000+9001）保持行业标准端口不动。

## Goals / Non-Goals

**Goals:**

- 应用服务端口全部按职能百位分段，互不重叠、避开常见默认值。
- 多 master / 多 worker 采用**统一的"容器内同端口 + host 映射错位"**策略，可扩展到 N 个实例。
- 所有硬编码位置（配置、代码默认值、白名单、CLI 默认值、文档）全部同步。
- 端口分配规则写成 spec，后续新增服务时按规则取号，不再临时拍脑袋。

**Non-Goals:**

- 不动第三方基础设施端口（PostgreSQL / Redis / MinIO）。
- 不引入端口服务发现 / 配置中心（如 Consul/Nacos）——当前规模直接写配置文件即可。
- 不改 AG-UI 协议事件结构、调度算法、PolicyEngine 闸门等业务逻辑。
- 不引入运行时端口动态分配（`server.port=0`）。
- 不改生产环境 K8s/其他编排层的 Service port（当前仅 docker-compose 部署）。

## Decisions

### Decision 1 — 应用端口按职能百位分段

**选择：**
| 服务 | 容器内 | Host（单实例/首个） |
|---|---|---|
| master-1（dataweave-api） | 8000 | 8000 |
| master-2 | 8000 | 8200 |
| master-N | 8000 | 8200 + (N-2) |
| worker 默认 / worker-1 | 8100 | 8100 |
| worker-N (N≥2) | 8100 | 8100 + (N-1) |
| workhorse-agent | 8300 | host 模式 127.0.0.1:8300 |
| 前端 Next.js | 4000 | 4000 |

**理由：**
- 百位分段（80xx / 81xx / 82xx / 83xx）让端口自带"职能"语义，看端口号就知道是什么服务。
- 与第三方标准端口段（5432/6379/9000）不重叠。
- 8080/8081/3000 这类"默认值"全部避开。
- 前端放 4000 段（非 8xxx），与后端显式区分，且 4000 冲突概率远低于 3000。

**被否决的替代：**
- 9xxx 段（9080/9081/...）：与 MinIO 9000 同段易混淆。
- 1xxxx 段（18080/...）：数字太长，手输 URL 易错。
- 全随机：难记，调试时不利。

### Decision 2 — 多实例策略：容器内同端口 + host 映射错位

**选择：** 所有 master 容器内都是 8000、所有 worker 容器内都是 8100；不同实例通过 host 端口映射错开（master-1 host:8000、master-2 host:8200、worker-1 host:8100、worker-2 host:8101）。

**理由：**
- 容器内端口一致 → 镜像通用、配置简化、healthcheck URL 统一。
- host 端口错位 → 同机多实例可并存，外部访问按 host 端口区分。
- master 通过 DB 中 `node.host_port` 寻址 worker（`DistributedTaskExecutionGateway` 已实现），天然支持任意 host 端口。
- worker 通过 `dataweave.master.url` 连 master，指向 DNS 名 + 容器内端口（`http://dataweave-master:8000`），不依赖 host 映射。

**被否决的替代：**
- 每实例容器内端口也不同：配置冗余，镜像不通用，healthcheck 要按实例定制。
- 引入服务发现（Consul/Eureka）：当前规模（<10 节点）用静态配置足够。

### Decision 3 — 配置来源优先级

**选择：** 端口在 `application.yml` 中写默认值，允许通过环境变量（`SERVER_PORT`）或启动参数（`--server.port=...`）覆盖。`@Value("${xxx:default}")` 的 default 必须与 `application.yml` 保持一致。

**理由：**
- 单源真相（`application.yml`），`@Value` 默认值仅作 fallback（防御性）。
- docker-compose 通过环境变量注入覆盖（保持现有模式），不改架构。

### Decision 4 — CORS 白名单端口与前端 dev 端口同步

**选择：** `CorsConfig.allowedOrigins` 硬编码 `http://localhost:4000`。前端 dev 脚本加 `-p 4000` 固定端口。

**理由：**
- Next.js 默认 3000 → 4000，与后端 CORS 白名单严格对应。
- 不允许"前端用任意端口" → CORS 必须显式声明。

## Risks / Trade-offs

- **[风险] 已运行旧端口的开发者需迁移** → 在 CLAUDE.md 顶部加迁移说明，重启服务 + 清浏览器缓存 + 更新书签。
- **[风险] 端口被其他程序占用（如 8000 在 macOS 被 AirPlay Receiver 占）** → 文档中说明可用 `SERVER_PORT=xxx` 覆盖；docker-compose 中 host 端口可单独改（容器内端口固定）。
- **[取舍] 不引入服务发现** → 当前规模无需引入；超过 ~20 节点时再评估。本次不预做。
- **[取舍] 端口号不加密/不验证** → 配置文件写错不会自动校验，靠 review + 健康检查兜底。

## Migration Plan

1. 后端先改 `application.yml` + Java 默认值 + 测试（不影响前端）。
2. 前端改 `package.json` + `agent-chat.tsx` 默认值。
3. `docker-compose.yml`、`deploy/workhorse/*`、`cli/main.go` 同步。
4. 文档（CLAUDE.md / README / architecture.md / openspec 文档）同步。
5. 开发者本机：
   - 停掉旧服务（8080/8081/3000）。
   - 重启后端（`./mvnw spring-boot:run` 起 8000）、前端（`pnpm dev` 起 4000）。
   - 浏览器访问 `http://localhost:4000`。
6. 回滚：改回原端口配置即可（git revert）。

## Open Questions

（无。方案已完全确定。）
