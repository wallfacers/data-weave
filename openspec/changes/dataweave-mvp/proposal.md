## Why

传统数据中台（DataWorks / DolphinScheduler）的任务开发、运维、指标、血缘都靠人工在控制台拖拽与排查，门槛高、口径分散。DataWeave 把交互范式升级为 **AI Agent 原生**：用户用自然语言提需求，Agent 操控中台完成全流程。本 change 交付 DataWeave 的 MVP —— 跑通「自然语言 → Agent 操控」的最小闭环，验证 AG-UI 对话 + 指标/任务/血缘领域模型的端到端可行性。

## What Changes

- 新建前端 `frontend/`（Next.js App Router + shadcn/ui + 给定 oklch 主题），`/agent` 页用 CopilotKit v2 直连后端 AG-UI 端点。
- 新建后端 `backend/`（Spring Boot 4 + Java 25 + WebFlux，Maven 四模块 `api/master/worker/alert`，各 DDD 四层）。
- 后端 `dataweave-api` 暴露 **AG-UI SSE 端点**，接收用户消息、做规则式意图编排、流式返回文本 + 结构化结果。
- Agent 引擎 MVP 为**规则 mock**，预留 `LlmClient` 接口与 `WebClientConfig`（后期接真模型）。
- 数据模型与种子：`users / tasks / task_instances / metrics / metric_lineage`，预置 GMV 指标、每日 GMV 任务、mock `orders` 表。
- 开发态用 H2（零外部依赖即可 `mvnw spring-boot:run`），`docker-compose.yml` 提供 PostgreSQL + Redis。
- `frontend/DESIGN.md` 作为设计系统真相源（`@google/design.md` 格式），主题 tokens 由其 export 进 `globals.css`。

## Capabilities

### New Capabilities
- `agent-conversation`: `/agent` 对话界面与后端 AG-UI 流式协议 —— 输入中文、收文本增量与结构化结果、渲染表格/图表。
- `text-to-sql`: mock 的自然语言转 SQL —— 识别预置问法，返回 SQL 文本 + 表格结果。
- `metric-registry`: 指标注册与查询 —— 按名识别指标，返回口径数据并溯源（口径不可篡改，改口径生成新版本）。
- `task-scheduling`: 自然语言任务编排 —— 从意图建任务、配 cron、上线，状态机 mock 推进。
- `data-lineage`: 基础血缘问答 —— 回答「指标 → SQL → 物理表」的影响链路。

### Modified Capabilities
<!-- 首个 change，无既有 capability 被修改 -->

## Impact

- **新增项目**：`frontend/`、`backend/`（两个独立项目，共享 `docs/` 与 `openspec/`）。
- **依赖**：前端 Next.js / shadcn/ui / CopilotKit v2 / `@ag-ui/client` / `@google/design.md`；后端 Spring Boot 4 / WebFlux / H2 / Flyway（或 schema.sql）/ Redis 客户端。
- **环境约束**：Java 25（memory: symlink swap）、Jackson 3（`tools.jackson.*`）、Spring Boot 4 须自建 `WebClient.Builder` bean、CopilotKit 直连 AG-UI 必须用 v2。
- **外部服务**：MVP 不依赖真实 LLM；Agent 引擎为 mock，接口预留。
