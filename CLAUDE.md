# DataWeave

AI Agent 原生的数据中台 —— **用 Agent 编织数据**。用户用自然语言提出数据需求，Agent 自主操控任务开发、调度、指标、血缘等全流程操作。聊天式交互通过 **AG-UI 协议** 直连后端，由 Agent 编排引擎驱动中台各模块能力。

## Architecture

前后端两个独立项目：**Next.js 前端（CopilotKit v2）** → HTTP/SSE（AG-UI 协议）→ **Spring Boot 后端（WebFlux + 四模块 DDD）**。完整设计见 [docs/architecture.md](docs/architecture.md)。

## Repository Layout

```
frontend/                          # Next.js 16 (App Router) + React 19 + shadcn/ui + CopilotKit v2
  app/page.tsx                     # `/` 即 Workspace（左对话主驾 + 右多 tab 工作区）
  app/{tasks,ops,fleet,...}/       # 旧模块路由 → redirect("/?open=<view>") 深链兜底
  components/agent-rail.tsx        # 左栏 Agent 主驾（CopilotChat 直连后端 AG-UI，可拖拽调宽）
  components/workspace/            # tab 条（Pinned/Ephemeral + "+" 启动菜单）+ 视图容器 + views/
  lib/workspace/                   # zustand store（真相源）/ 视图注册表 / 会话持久化
  DESIGN.md                        # 设计系统真相源（@google/design.md 格式）
  app/globals.css                  # 实际生效的 oklch 主题变量（preset 生成）

backend/                           # Spring Boot 4.0 + Java 25，Maven 多模块（包根 com.dataweave）
  dataweave-api/                   # 启动入口；WebFlux；AG-UI /agui + MCP /mcp 端点；桥接层；CORS/WebClient 配置
  dataweave-master/                # 调度中心 + 工作流 + 指标/任务/血缘领域 + PolicyEngine/审计四表（Spring Data JDBC）
  dataweave-worker/                # 任务执行器 + 受控命令执行（ControlledCommandExecutor）
  dataweave-alert/                 # 告警规则 + 通知通道（骨架）
  # 每模块内 DDD 四层：interfaces / application / domain / infrastructure

cli/                               # dw Go 单二进制（薄壳调 master REST，独立构建，二进制不入 git）
deploy/workhorse/                  # workhorse-agent 部署配置（config.yaml + mcp.json，provisional）
docs/architecture.md               # 架构真相源
openspec/                          # OpenSpec SDD：changes / specs / archive
  changes/<name>/                  # 一个变更一个目录（proposal + design + specs + tasks）
docker-compose.yml                 # PostgreSQL + Redis（+ workhorse profile）；后端默认即连此 PG
```

## Tech Stack

| Layer    | Stack                                                                       |
|----------|------------------------------------------------------------------------------|
| 前端     | Next.js 16 (App Router, Turbopack), React 19, shadcn/ui（base 风格 / hugeicons）, next-themes |
| 对话     | CopilotKit **v2**（`@copilotkit/react-core/v2`）+ `@ag-ui/client` `HttpAgent`，直连 AG-UI，**不跑 Node Runtime** |
| 后端     | Java 25, Spring Boot 4.0 / Spring Framework 7（**Jackson 3**）, WebFlux（AG-UI SSE）, Maven 多模块 |
| 数据访问 | Spring Data JDBC + JdbcTemplate                                              |
| 存储     | **PostgreSQL（默认）** · H2（`-Dspring-boot.run.profiles=h2` 内存库，无 Docker 时用，DDL 兼容）· Redis（EventBus/LogBus）· MinIO（日志归档） |
| 调度     | 多 master 对等 + SKIP LOCKED 认领 + 事件驱动 + 软抢占 + cron 护栏表防重，`scheduler.mode=all-in-one\|distributed` |
| 指标     | Micrometer + Actuator，四层指标（性能/资源/管道/SLA），`/api/ops/metrics` + `/actuator/prometheus` |
| Agent    | 双模式 `agent.mode=mock\|workhorse`（默认 mock=`IntentRouter`；workhorse=真 LLM 大脑经桥接层）|
| 工具/权限 | DataWeave MCP Server（`/mcp`，Bearer）暴露平台工具；写操作全经 `PolicyEngine` L0–L4 闸门 + 审计四表 |
| CLI      | `dw`（Go 单二进制，`cli/`）：`task list/show/instances/rerun`、`logs cat`，调 master REST |
| 设计系统 | `@google/design.md`（token 真相源 + lint/export）                            |
| 规范     | OpenSpec（spec-driven，`/opsx:*`）                                            |

## Build & Run

```bash
# 后端（默认连 PostgreSQL，须先起 Docker 库）
cd backend
docker compose up -d                               # PostgreSQL + Redis（默认连 localhost:5432）
./mvnw install -DskipTests                         # 首次/改 domain·application·infra 后必做
./mvnw -pl dataweave-api spring-boot:run           # 端口 8000；AG-UI: POST /agui；健康: GET /api/health

# 前端
cd frontend
pnpm install
pnpm dev                                           # http://localhost:4000（左对话主驾 + 右 Workspace）

# 零外部依赖（无 Docker 时用 H2 内存库）
cd backend && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
```

当前运行入口：前端 `http://localhost:4000`，后端 `http://localhost:8000`，前端经 `NEXT_PUBLIC_AGENT_URL`（默认 `http://localhost:8000/agui`）连后端。

### 调度与指标端点

- **调度模式**：`scheduler.mode=all-in-one`（默认）单 JVM 全包；`distributed` 独立 master/worker + PG + Redis + MinIO。
- **系统指标**：`GET /api/ops/metrics`（JSON 快照），`GET /actuator/metrics`（Micrometer 明细），`GET /actuator/prometheus`（Prometheus 格式）。
- **实时 SSE**：日志流 `GET /api/ops/instances/{id}/logs/stream`，状态流 `GET /api/ops/workflow-instances/{id}/events/stream`。

### Agent 大脑模式（agent-fabric-m1）

- **`agent.mode=mock`（默认，零依赖）**：`IntentRouter` 规则路由，CI/克隆即跑。
- **`agent.mode=workhorse`**：接 workhorse-agent 真 LLM 大脑。`AguiController` 经桥接层转发 workhorse 会话 SSE → AG-UI 事件。
  ```bash
  # 1) 起 workhorse（部署配置在 deploy/workhorse/，需 ANTHROPIC_API_KEY/OPENAI_API_KEY）
  docker compose --profile workhorse up -d workhorse
  # 2) 后端切 workhorse 模式
  ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.arguments=--agent.mode=workhorse
  ```
- **MCP 端点**：`POST /mcp`（JSON-RPC：initialize/tools/list/tools/call，Bearer `mcp.auth.token`）。workhorse 经 `deploy/workhorse/mcp.json` 接入；token 两侧须一致。
- **dw CLI**：`cd cli && ./build.sh`；`DW_API`（默认 `:8000`）、`DW_TOKEN`（写类操作 `X-DW-Token`，对应 `cli.auth.token`）。
- **审计回放**：每次运行落 `agent_session/agent_run/agent_step/agent_action`，两模式同样留痕。

## Key Conventions

- **依赖方向**：domain ← application ← infrastructure ← interfaces（外层依赖内层，绝不反向）。
- **新增 Agent 能力**：mock 模式在 `IntentRouter` 加意图分支；真大脑模式在 `McpToolRegistry` 注册平台工具（查询直通 master 领域服务，写操作经 `GatedActionService` 闸门）。两模式经同一 `AguiEvents` 出口产同构 AG-UI 事件。
- **副作用操作必经闸门**：任何写工具（含 `node_exec`、CLI `rerun`、`applyFix`）构造 `ActionRequest` → `GatedActionService.submit` → `PolicyEngine` 裁决（L0/L1 直执行，L2/L3 建审批单返回 `PENDING_APPROVAL`，L4 拒绝）+ `agent_action` 留痕，**无绕过路径**。分级规则数据驱动（`policy_rules` 表）。
- **MCP 工具新增**：在 `McpToolRegistry.registerTools()` 注册（name + JSON Schema + handler）；查询工具复用领域服务，写工具经闸门。`node_exec` 命令串安全解析在 `PolicyEngine`（重定向/分隔/子命令 → 抬升 L2）。
- **AG-UI 事件序列**（`/agui`，`text/event-stream`，`type` 为 SCREAMING_SNAKE_CASE）：`RUN_STARTED → TEXT_MESSAGE_START → N×TEXT_MESSAGE_CONTENT(markdown) → TEXT_MESSAGE_END → [CUSTOM(name="dataweave.result")] → [CUSTOM(name="dataweave.ui.open")] → RUN_FINISHED`。文本走 Markdown（CopilotChat 原生渲染）；结构化结果走 `CUSTOM` 事件；视图召唤走 `CUSTOM(dataweave.ui.open)`（载荷 `{view, params?}`，前端 Workspace 去重激活，未知 view 忽略）。
- **调度死锁防御不变量**（硬性）：① 认领只用 SKIP LOCKED；② 状态推进全用乐观 CAS（`WHERE state=?` 守卫）；③ 锁序固定 task→workflow；④ 事务内只做状态落库，HTTP 下发在事务外。等待不占资源。
- **SSE 端点**（Phase 4 realtime-streams）：① `GET /api/ops/instances/{id}/logs/stream`（日志实时流，Last-Event-ID 断线续传）；② `GET /api/ops/workflow-instances/{id}/events/stream`（DAG 状态流，节点实时变色）。
- **系统指标端点**：`GET /api/ops/metrics`（四层指标快照，前端看板用）；`/actuator/metrics` + `/actuator/prometheus`（Prometheus 刮取）。`SchedulerMetrics` 服务负责所有埋点。
- **指标口径不可篡改**：改口径 → `metrics` 新增递增 `version`，不 UPDATE 旧版本。
- **i18n 文案归属三规则**（新增/改任何用户可见文案必守，详见 [docs/architecture.md](docs/architecture.md) i18n 段 + [docs/i18n-error-codes.md](docs/i18n-error-codes.md)）：① **UI 静态文案**（按钮/tab/表单 label/空状态/toast）→ 前端 next-intl key 表，ICU `{name}` 占位，**按 UI locale**；② **后端动态生成**（AG-UI markdown、MCP 工具描述、诊断建议、审批理由）→ 后端 `Messages.get`，MessageFormat `{0}` 占位，**按 agent locale**；③ **错误/异常**（`throw`、`ApiResponse.err`）→ 后端 `BizException(code, args)` + `GlobalExceptionHandler` 本地化，**按 UI locale**。前端 toast 信任后端本地化 message，不再 `|| "中文兜底"`。
- **i18n key 命名规范**：前端 messages（`frontend/messages/{zh-CN,en-US}.json`）顶层按区域命名空间（`common`/`workspace`/`ops` + 每视图独立如 `cockpit`/`metrics`/`instanceTable`…），`views.*` 只存 tab 标题；**两 bundle 键集必须完全一致**（CI/脚本校验 zh-only/en-only 为空，所有 `t("key")` 静态可解析）；非默认 locale 经 `i18n/request.ts` 深合并 zh-CN 兜底缺失 key。后端 code 命名 `<domain>.<semantic>`（kebab/snake 一致，如 `workflow.not_online`），**稳定永不复用**；数据中台术语（cron/DAG/SLA/lineage/OOM）保留英文原词；`data.sql` 业务种子数据 i18n 豁免（保留中文）。组件取 locale 用 `useLocale()`（日期经 `formatDateTime(iso, locale)`）。
- **Spring Boot 4 注意**：① Jackson 3 —— `ObjectMapper` 在 `tools.jackson.databind.*`，注解仍在 `com.fasterxml.jackson.annotation.*`；② 无 `WebClient.Builder` 自动配置，须自建 `@Bean`（见 `WebClientConfig`）；③ 部分 test/auto-config 注解迁了包，import 报错按实际包名调整。
- **Java 25**：本机经 symlink swap 使非交互 shell 透明使用 JDK 25。

## OpenSpec Workflow (SDD)

DataWeave 以 **OpenSpec（spec-driven schema）** 为提案、设计、实现、归档的主工作流。需求/MVP 文档归宿是 `openspec/`，**不再往 `docs/` 加新需求文档**（`docs/` 仅放架构真相源等参考）。

| Command | Purpose |
|---------|---------|
| `/opsx:explore [topic]` | 开放式探索 —— 思考、对比、画图。不产出构件、不写代码。 |
| `/opsx:propose <change-name>` | 创建变更：proposal.md + design.md + specs/ + tasks.md |
| `/opsx:apply [change]` | 按 tasks.md 逐个 checkbox 实现 |
| `/opsx:archive [change]` | 把 delta specs 合入 base specs，变更移入 `changes/archive/YYYY-MM-DD-<name>/` |

```
/opsx:explore "idea"   → 想清楚（无构件）
   ↓
/opsx:propose <name>   → 生成 proposal + design + specs + tasks
   ↓ 评审
/opsx:apply            → 逐项实现
   ↓ 全部完成
/opsx:archive          → 合并 delta，归档
```

首个变更 `dataweave-mvp` 已就位（`openspec status --change dataweave-mvp`）。

## Knowledge Base Navigation

本文件是地图，细节在各处：

| 想找…                  | 去                                                          |
|------------------------|-------------------------------------------------------------|
| 活跃变更提案           | `openspec/changes/`（`openspec list`）                      |
| 系统行为规范           | `openspec/specs/`                                           |
| 已归档变更             | `openspec/changes/archive/`                                 |
| 架构与分层             | [docs/architecture.md](docs/architecture.md)                |
| MVP 需求与验收         | `openspec/changes/dataweave-mvp/`                           |
| 前端设计系统真相源     | [frontend/DESIGN.md](frontend/DESIGN.md)                    |
| 实际主题 CSS 变量      | `frontend/app/globals.css`                                  |
| AG-UI 端点实现         | `backend/dataweave-api/.../interfaces/AguiController.java`  |
| Agent 意图路由         | `backend/dataweave-api/.../application/IntentRouter.java`   |
| 调度内核               | `backend/dataweave-master/.../application/SchedulerKernel.java` |
| 系统指标               | `backend/dataweave-master/.../application/SchedulerMetrics.java` |
| 前端指标看板           | `frontend/components/workspace/views/metrics-view.tsx`      |
| 类目树（文件夹+标签）   | `backend/dataweave-master/.../application/CatalogTreeService.java`（path 维护/移动/防环）+ `CatalogController`/`TagController` |
| 前端类目树组件         | `frontend/components/workspace/catalog-tree.tsx`（画布左侧面板；两种拖拽 MOVE_MIME/TASK_MIME） |
| 运行方式               | [README.md](README.md)                                      |

## Working Rules

### Post-Edit Verification

- **后端**：每次编辑后 `cd backend && ./mvnw -q -pl <改动模块> compile` —— 确认零编译错误再继续。
- **前端**：每次编辑后 `cd frontend && pnpm typecheck` —— 确认零类型错误再继续。
- **例外**：注释/文案/单行字面量等高置信度小改可跳过；拿不准就跑。

### Backend Run vs Compile

- `./mvnw -pl dataweave-api spring-boot:run` 从 `~/.m2` 加载 sibling 模块（master/worker/alert），**不是** `target/classes`。改了 domain/application/infrastructure 后必须先 `./mvnw install -DskipTests`（或 `-pl <module> -am`），否则运行中的进程仍用旧 class。
- 单模块 run 前若没 install 过会报「找不到 sibling jar」。

### Browser Verification Gate（硬性）

- **`pnpm build` 通过 ≠ 页面能渲染。** CopilotKit/AG-UI 这类只在浏览器运行时才暴露的缝，build 和 Node seam-check 都测不出。
- 任何改动 `/agent`、CopilotKit Provider、AG-UI 协议、主题/布局的任务，**完成后必须真在浏览器跑一次**（`mcp__playwright__*` 或 `playwright-cli`），确认 CopilotChat 渲染出输入框（而非「只剩几条分割线」）、console 无 error、消息能发出并收到流式回复。
- 浏览器验证产物（截图/trace）写到项目根 `tmp/`，验证完清理，**不留在仓库**。

### Frontend Stack Gate

写或改 `frontend/` 任何代码前，遵守：
- **对话只用 CopilotKit v2**：从 `@copilotkit/react-core/v2` 导入 `CopilotKitProvider`/`CopilotChat`，`selfManagedAgents={{ dataweave: httpAgent }}`，import `@copilotkit/react-core/v2/styles.css`。**禁止用 v1**（运行时硬性要 `runtimeUrl`，`selfManagedAgents` 不被识别）。
- **`@ag-ui/client` 版本必须与 CopilotKit 内部绑定一致**（当前 `@copilotkit/react-core@1.59.5` → `@ag-ui/client@0.0.53`）。错版会让 `tsc` 报 `HttpAgent` 与 `AbstractAgent` 的私有属性 `_debug` 冲突。升级 CopilotKit 时同步对齐。
- **base 风格组件**：自定义触发器用 `render` prop（非 `asChild`）。base UI 的 `Button` 用 `render={<Link/>}`（渲染成 `<a>`）必须加 `nativeButton={false}`，否则 console 报错。
- **图标用 hugeicons**：`<HugeiconsIcon icon={XxxIcon} />`，名称从 `@hugeicons/core-free-icons` 取（非 lucide）。
- 遵守 shadcn 规则：语义 token（`bg-primary`、`text-muted-foreground`），间距 `gap-*`，等宽高 `size-*`，不手写 `dark:` 颜色覆盖。

### Design Contract Gate

- 改 `frontend/` 主题/视觉/设计系统前，**必须先读 [frontend/DESIGN.md](frontend/DESIGN.md)** 并在提案里说明采用的约束。
- 主题改动改 `DESIGN.md`（真相源）+ 同步 `app/globals.css`；`pnpm design:lint` 校验。
- 若方向与 `DESIGN.md` 冲突，停下来问：① 遵守 DESIGN.md ② 先改 DESIGN.md ③ 带书面理由刻意偏离。

### AG-UI Protocol Contract Gate

- 改 `/agui` 端点或事件结构前，前后端两侧一起改并对齐：事件 `type` 用 SCREAMING_SNAKE_CASE、序列完整（RUN_STARTED…RUN_FINISHED）、CORS 放行前端 origin（`http://localhost:4000`）。
- 改完走 Browser Verification Gate 真跑一次。

### Testing

- 新功能必须有对应测试，无测试 = 未完成。
- 后端：JUnit 5 + AssertJ；涉及 WebFlux/AG-UI 用 `@SpringBootTest` 或 WebTestClient（注意 SB4 的 `@WebFluxTest` 在 `org.springframework.boot.webflux.test.autoconfigure`）。
- 前端：vitest（如需）+ Browser Verification Gate 的端到端实跑。

### Exploration & Brainstorming

- 新点子、设计讨论、问题排查：优先 `/opsx:explore`（带 OpenSpec 上下文，不写代码）。
- 重大架构变更（新模块、跨 DDD 四层重构、改 AG-UI 协议）**必须**先 `superpowers:brainstorming` 再 `/opsx:propose`。常规功能用 `/opsx:explore` 即可。

### Clarify Before Acting

- 需求、范围或实现方式不清时，**先问**，绝不猜。

### MCP / Skill Temporary Files

- MCP/Skill 产生的临时文件（Playwright 截图/trace、brainstorming 草稿、中间脚本、下载产物等）**必须**写到项目根 `tmp/`（不存在则创建）。
- **禁止**写入：仓库根、`frontend/`、`backend/`、`docs/`、系统 `/tmp`、`~/`，或任何被跟踪的源码路径。
- `tmp/` 不提交 git。

### Response Style

- 简洁直接，不堆废话。如实报告：测试失败就说失败并贴输出，跳过的步骤就说跳过。
