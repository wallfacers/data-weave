# DataWeave 架构设计

> AI Agent 原生的数据中台 —— 「用 Agent 编织数据」。
> 用户用自然语言提出数据需求，Agent 自主操控任务开发、调度、质量、指标、血缘等全流程操作。

本文档是 DataWeave 的**架构真相源**。需求/MVP 范围以 OpenSpec change（`openspec/`）为准；前端设计系统以 `frontend/DESIGN.md` 为准。

---

## 1. 顶层布局

```
data-weave/
├── frontend/          # Next.js (App Router) 独立项目
│   └── DESIGN.md      # 设计系统单一真相源（@google/design.md 格式）
├── backend/           # Spring Boot 4 + Maven 多模块独立项目
├── docs/              # 架构设计（本文档）
└── openspec/          # OpenSpec：spec-driven 工作流（MVP 需求归宿）
```

前后端是两个**独立项目**（各自构建、各自依赖），共享一份 `docs/` 与 `openspec/`。

---

## 2. 技术栈

| 层 | 选型 | 备注 |
|----|------|------|
| 前端框架 | Next.js (App Router) + pnpm | shadcn 组件一律命令安装 |
| UI / 主题 | shadcn/ui + 给定 oklch 亮/暗主题 | 主题 tokens 收进 `DESIGN.md` |
| 对话交互 | CopilotKit **v2** (`@copilotkit/react-core/v2`) + `@ag-ui/client` `HttpAgent` | 直连后端 AG-UI 端点，**不跑 Node CopilotKit Runtime** |
| 后端语言 | Java 25 (LTS) | |
| 后端框架 | Spring Boot 4.0 / Spring Framework 7 | 自带 **Jackson 3**（`tools.jackson.*`） |
| Web 层 | Spring WebFlux | AG-UI 走 SSE 流式 |
| 持久化 | PostgreSQL（生产）/ H2（开发零依赖） | DDL 兼容 PG 语法 |
| 缓存/队列 | Redis | 对话临时状态 + Master/Worker 队列占位 |
| Agent 引擎 | MVP 为规则 mock，预留 `LlmClient` 接口 | 后期接 LangChain4j / 真模型 |
| 设计系统工具 | `@google/design.md`（alpha） | token 真相源 + lint + export |
| 规范工作流 | `@fission-ai/openspec` | spec-driven，change → propose/apply/archive |

### 关键环境注记（来自项目 memory）
- **JDK 25**：本机经 symlink swap 使非交互 shell 透明使用 Temurin 25。
- **Jackson 3**：`ObjectMapper` 在 `tools.jackson.databind.*`；注解仍在 `com.fasterxml.jackson.annotation.*`。
- **Spring Boot 4 无 `WebClient.Builder` 自动配置**：须自建 `@Bean WebClient.Builder`（见 `WebClientConfig`）。
- **CopilotKit 直连 AG-UI 必须用 v2**：v1 运行时硬性要 `runtimeUrl`，`selfManagedAgents` 不被运行时识别。

---

## 3. 前端架构

- **`/agent` 路由**：客户端组件。`CopilotKitProvider selfManagedAgents={{ dataweave: httpAgent }}` + `<CopilotChat agentId="dataweave" />`，`HttpAgent({ url: NEXT_PUBLIC_AGENT_URL })` 直连后端。
- **主题**：`next-themes` 做亮/暗切换；CSS 变量由 `DESIGN.md` export 进 `app/globals.css`。
- **结果可视化**：后端在 AG-UI 事件里携带结构化结果（SQL、表格行、血缘边），前端用 shadcn `Table` / chart / 血缘图渲染（generative UI）。

```
浏览器 (/agent)
  └─ CopilotKitProvider (v2, selfManagedAgents)
       └─ HttpAgent ── HTTP/SSE ──▶ 后端 dataweave-api /agui
```

---

## 4. 后端架构（Maven 多模块）

父 pom 统一管理 Spring Boot 4、Java 25、依赖版本。四个模块，每个内部 DDD 四层：`interfaces / application / domain / infrastructure`。

| 模块 | 职责 | MVP 实质内容 |
|------|------|------|
| `dataweave-api` | AG-UI 对话端点 + REST 网关 | WebFlux SSE 发 AG-UI 事件；Agent 编排（意图→动作）入口；指标查询/Text-to-SQL |
| `dataweave-master` | 调度中心、工作流引擎、指标领域 | 任务/调度/指标/血缘领域模型 + DDL + 种子；mock 调度状态机 |
| `dataweave-worker` | 任务执行器 | 执行器基类 + Shell 执行器（骨架） |
| `dataweave-alert` | 告警通知 | 规则实体 + 通知通道接口（骨架） |

- **Master ↔ Worker**：MVP 先定义领域接口 + Redis 队列占位，代码内注释标注后期切换 gRPC/MQ 的接缝。
- **Agent 引擎（mock）**：规则式意图识别 →
  - 识别指标名（如「GMV」）→ 查 `metrics` 取口径 SQL → 返回结果 + 口径溯源。
  - 识别建任务意图 → 调 master 领域模型建任务、配 cron、上线。
  - 识别血缘问题 → 查 `metric_lineage` 返回「指标 → SQL → 物理表」链路。
  - 预留 `LlmClient` 接口（默认 mock 实现）+ `WebClientConfig`（自建 `WebClient.Builder` bean）。

### AG-UI 事件流（mock）
api 模块接收用户消息 → Agent 编排器解析意图 → 产生一系列 AG-UI 事件（文本增量 + 结构化结果）→ SSE 推回前端。

---

## 5. 数据模型

PostgreSQL 为主（开发用 H2，DDL 兼容）。Redis 存对话临时状态与任务队列占位。

| 表 | 关键字段 | 说明 |
|----|----------|------|
| `users` | id, username, email, role | 基础用户 |
| `tasks` | id, name, type(SQL/Shell/...), content, cron, status, depends_on | 任务定义 |
| `task_instances` | id, task_id, state, started_at, finished_at, log | 任务运行实例 |
| `metrics` | id, name, expr_sql, source_table, dimensions, owner, version | 指标定义（改口径 → 新版本，旧版本不可篡改） |
| `metric_lineage` | id, metric_id, downstream_type, downstream_id | 指标 → SQL → 物理表血缘边 |

**种子数据**：
- GMV 指标：`expr_sql = sum(order_amount)`，`source_table = orders`。
- 「每日 GMV 统计」任务：cron `0 0 8 * * ?`。
- mock `orders` 表 + 样例数据，供 Text-to-SQL / 指标查询返回真实结果。

---

## 6. MVP 范围

1. **Agent 对话界面** —— `/agent` 可输入中文、流式收 AG-UI 事件、渲染表格。
2. **Text-to-SQL（mock）** —— 提问 → 返回 SQL + 表格结果（预置问法）。
3. **指标注册与查询** —— 识别指标名 → 返回正确口径数据 + 口径溯源。
4. **简单任务调度** —— 自然语言建任务 → 配 cron → 上线（状态机 mock 推进）。
5. **基础血缘** —— 「GMV 受哪些表影响」→ 从 `metric_lineage` 问答。

详细需求与验收以 `openspec/` 下的 change 为准。

---

## 7. 运行方式

- **后端**：`cd backend && ./mvnw install -DskipTests && ./mvnw -pl dataweave-api spring-boot:run`（默认 H2 + 嵌入态，零外部依赖即可跑通 MVP；单模块 run 前需先 install 兄弟模块）。
- **前端**：`cd frontend && pnpm dev`，打开 `/agent`。
- **真实库**：`docker compose up`（PostgreSQL + Redis），切配置即用。

---

## 8. 演进路线（MVP 之后）

- Agent 引擎：mock → LangChain4j / 真模型，工具调用编排化。
- Master↔Worker：Redis 队列占位 → gRPC / 消息队列。
- 模块填充：worker 多协议（SQL/Spark/Python）、alert 规则引擎与多通道、数据质量中心、资产目录。
- 指标体系：指标市场（搜索/订阅/复用）、字段级血缘自动解析。
