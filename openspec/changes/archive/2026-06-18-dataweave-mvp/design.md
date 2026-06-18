## Context

DataWeave 是从零起步的 AI Agent 原生数据中台。本 change 交付 MVP，技术与环境约束已在项目 memory 中沉淀：Java 25、Spring Boot 4（Jackson 3、无 `WebClient.Builder` 自动配置）、CopilotKit 直连 AG-UI 必须用 v2。完整架构见 `docs/architecture.md`，本文档只记录关键技术决策与取舍。

## Goals / Non-Goals

**Goals:**
- 跑通「自然语言 → Agent 操控中台」的端到端最小闭环（对话、Text-to-SQL、指标、任务、血缘）。
- 前端 Next.js + shadcn + 给定主题，`/agent` 用 CopilotKit v2 直连后端 AG-UI 端点。
- 后端 Maven 四模块骨架（DDD 四层），`mvnw spring-boot:run` 零外部依赖即可跑通（H2）。
- 设计系统 tokens 收进 `DESIGN.md`，规范走 OpenSpec。

**Non-Goals:**
- 不接真实 LLM（Agent 引擎为规则 mock，仅预留接口）。
- 不做真正的分布式调度执行（Master↔Worker 为接口 + Redis 队列占位）。
- 不实现数据质量中心、资产目录、告警引擎的完整功能（alert/worker 仅骨架）。
- 不做认证授权、多租户。

## Decisions

**1. 前端用 Next.js + shadcn，对话用 CopilotKit v2 直连 AG-UI**
- 选 CopilotKit v2 而非 v1：v1 运行时硬性要求 `runtimeUrl`，`selfManagedAgents` 不被运行时识别（build 过 ≠ 能渲染）。v2 从 `@copilotkit/react-core/v2` 导入 `CopilotKitProvider` + `CopilotChat`，`selfManagedAgents` 使 `hasLocalAgents=true` 绕过 runtime 要求。
- 备选：自建 REST `/api/chat` + 手写聊天 UI。放弃，因为放弃了流式与工具调用编排，后期接 Agent tool-calling 要重写。

**2. 后端 WebFlux + AG-UI SSE**
- AG-UI 事件天然适合 SSE 流式；WebFlux 的 `Flux<ServerSentEvent>` 直接产出事件流。
- Spring Boot 4 须自建 `@Bean WebClient.Builder`（后期调真 LLM 用），MVP 先放着。

**3. Maven 四模块全搭骨架，DDD 四层**
- `api`（对话/网关）、`master`（调度/工作流/指标领域）、`worker`（执行器）、`alert`（告警）。每模块 `interfaces/application/domain/infrastructure`。
- 备选：先只搭 api+master。放弃，用户要求四模块齐备以固定架构边界。worker/alert 仅放基类/接口骨架。

**4. Agent 引擎 = 规则 mock + `LlmClient` 接口**
- 意图路由：指标查询 / Text-to-SQL / 建任务 / 血缘问答 / 兜底。命中后调对应领域服务，产出 AG-UI 事件。
- `LlmClient` 接口默认 mock 实现；后期换 LangChain4j 或 WebClient 调真模型只改实现，不动编排骨架。

**5. 开发态 H2，生产 PostgreSQL；DDL 兼容**
- `mvnw spring-boot:run` 默认 H2 内存库 + `schema.sql`/`data.sql` 种子，零外部依赖。
- `docker-compose.yml` 提供 PostgreSQL + Redis，`prod`/`pg` profile 切换。
- DDL 用兼容语法（避免 PG 专有类型），保证两边一致。

**6. 指标口径不可篡改**
- `metrics` 带 `version`，改口径插新版本而非 UPDATE，保证溯源真相唯一。

**7. DESIGN.md 作为主题真相源**
- 用 `@google/design.md` 收录 oklch 亮/暗 tokens + radius + chart 色板，`export` 校对/生成 `globals.css`。
- 降级预案：若 alpha 工具无法表达 oklch 或 export 不理想，DESIGN.md 退化为主题文档 + lint，`globals.css` 直接用给定 CSS。

## Risks / Trade-offs

- **[`@google/design.md` 为 alpha，可能不支持 oklch / export 不稳]** → 降级为文档+lint，主题 CSS 直接落 globals.css，不阻塞。
- **[CopilotKit v2 在 Next.js App Router 下的客户端约束]** → `/agent` 整页客户端组件，import v2 styles；必须真在浏览器跑一次验证（build 过不代表能渲染）。
- **[Spring Boot 4 较新，部分 test/auto-config 注解迁了包]** → import 报错按实际包名调整（如 `@WebFluxTest` 迁到 `org.springframework.boot.webflux.test.autoconfigure`）。
- **[mock 引擎覆盖问法有限]** → 未命中时优雅降级并提示支持范围，不抛错。
- **[四模块骨架多为空壳]** → 用注释标注接缝（gRPC/MQ、真 LLM、规则引擎），避免被误认为已实现。

## Migration Plan

首个 change，无存量迁移。部署：后端 `mvnw spring-boot:run`（H2）或 `docker compose up` 后切 pg profile；前端 `pnpm dev`。回滚即丢弃工作区。

## Open Questions

- 真实 LLM 选型与接入方式（LangChain4j vs WebClient 直调）留待 MVP 后的独立 change。
- AG-UI 结构化结果（表格/血缘图）的事件 payload 约定细节，在实现期对齐 `@ag-ui/client` 版本后定稿。
