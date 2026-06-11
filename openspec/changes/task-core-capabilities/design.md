## Context

DataWeave 当前任务模块处于"骨架完成、引擎缺失"状态：

- **数据模型齐全**：`task_def`/`task_instance`/`workflow_def`/`workflow_node`/`workflow_edge` 等表结构完备，支持版本管理、DAG 编排、多租户。
- **CRUD 缺失**：只有 `TaskService.createAndOnline()` 一个方法（创建即上线），无搜索/编辑/删除。前端只能只读展示。
- **调度不执行**：`workflow_def.cron` 存了表达式但无调度引擎触发执行。
- **实例管理不全**：只有 `NOT_RUN/RUNNING/SUCCESS/FAILED/STOPPED` 五种状态，缺 `PAUSED`；无暂停/恢复/终止 API。
- **日志截断**：`task_instance.log` 是 `VARCHAR(4000)`，长日志被截断，无在线查看能力。
- **日期格式不统一**：后端 Jackson 默认 ISO-8601（`2026-06-11T14:30:00`），前端 `toLocaleString` 输出 `06/11 14:30:00`。
- **存在 3 处 Entity-Schema 类型不匹配**：`WorkflowDependency.dateOffset`（String 存成 Integer）、`AlertRule/NotificationChannel.createdBy`（Long 存成 String），运行时必崩。

约束：
- 前后端分离，后端 Spring Boot 4 + Java 25 + WebFlux，前端 Next.js 16 + CopilotKit v2。
- 写操作必须经 `GatedActionService` + `PolicyEngine` 闸门。
- AG-UI 协议事件序列不变。
- 数据库兼容 H2（开发）和 PostgreSQL（生产）。

## Goals / Non-Goals

**Goals:**

1. 任务 CRUD 全通：创建草稿 → 搜索 → 查看详情 → 编辑 → 发布上线 → 下线 → 软删除。
2. Cron 调度引擎运转：`schedule_type='CRON'` 且 `status='ONLINE'` 的工作流能被定时触发执行。
3. 实例生命周期完整：支持暂停（graceful）、恢复、强制终止。
4. 日志可用：完整存储（TEXT）+ 分块拉取 + 前端日志查看器。
5. 日期格式全局统一 `yyyy-MM-dd HH:mm:ss`。
6. Schema 补齐：修复 3 处类型 bug，补充 Phase B 所需的新字段。
7. MCP 工具补齐：Agent 也能执行 CRUD 和实例操作。

**Non-Goals:**

1. **DAG 编排引擎**：拓扑排序 + 依赖调度属于 P1，本次不做。调度引擎触发工作流时，暂按节点顺序串行执行（不做依赖解析）。
2. **参数化/变量替换**：`${bizdate}` 等属于 P1，本次 `params_json` 仍为透传字段。
3. **告警通知引擎**：alert_rules 评估和通知派发属于 P1，本次不做。
4. **补数（Backfill）**：属于 P1，本次不做。
5. **SLA/基线管理**：属于 P2，本次不做。
6. **更多任务类型**：PYTHON/SPARK/HTTP 等属于 P2，本次仍只有 SQL（JDBC）可用。
7. **前端 DAG 可视化编辑器**：属于 P1/P2，本次不做。
8. **多租户隔离执行**：`tenant_id` 仍为硬编码 1，不做租户间隔离。

## Decisions

### D1: 任务级调度 vs 工作流级调度

**决策**：调度引擎触发单位是 **WorkflowInstance**（工作流实例），不是 TaskInstance。任务必须挂在工作流下才能被调度。

**理由**：
- 现有 Schema 中 `workflow_def` 有 `cron`/`schedule_type`/`schedule_start`/`schedule_end` 字段，`task_def` 没有。
- DolphinScheduler 和 DataWorks 都以工作流为调度单位。
- 避免引入 `task_def.cron_expression` 等额外字段，保持模型简洁。

**替代方案**：任务级 cron（每个任务独立配调度）→ 需要新增字段 + 独立调度逻辑，复杂度高且与现有 Schema 不一致。

### D2: Cron 实现方案

**决策**：使用 Spring `@Scheduled(fixedRate = 60000)` 每分钟轮询 + Spring 内置 cron 表达式解析（`CronExpression`）。

**理由**：
- 零外部依赖，Spring Framework 自带 `org.springframework.scheduling.support.CronExpression`。
- 每分钟轮询精度对数据调度场景足够（DolphinScheduler 默认也是分钟级）。
- `last_fire_time` 字段防止重复触发。

**替代方案**：
- Quartz：功能更强（持久化任务、集群调度），但多一个重量级依赖，Phase B 不需要。
- 精确到秒的定时器：浪费资源，调度场景不需要。

**风险**：单点调度——多实例部署时可能重复触发。→ 缓解：Phase B 单实例部署；后续引入 `scheduler_lock` 表或分布式锁。

### D3: 暂停实现策略

**决策**：暂停 = 等当前正在执行的节点完成后，不再调度下游节点。工作流状态变为 `PAUSED`。

**理由**：
- DolphinScheduler 的 pause 也是 graceful 的（等当前任务跑完再停）。
- 强制 kill 需要向 Worker 发送中断信号，当前 Worker 通信架构暂不支持。
- "终止"操作（kill）则是强制停止，标记为 `STOPPED`。

**状态机**：
```
NOT_RUN → RUNNING → SUCCESS
            │  │
            │  └──→ PAUSED ──→ (恢复) → RUNNING
            │              └──→ (终止) → STOPPED
            └──→ FAILED ──→ (rerun) → RUNNING
            └──→ (终止) → STOPPED
```

### D4: 日志存储方案

**决策**：`task_instance.log` 字段从 `VARCHAR(4000)` 改为 `TEXT`，日志直接存在该字段。新增分块拉取 API（`offset`/`limit` 参数）。

**理由**：
- 改动最小，一张表一个字段。
- 4KB 截断是当前最大痛点，TEXT 无长度限制（PostgreSQL TEXT 无上限，H2 TEXT 上限 2^31）。
- 分块拉取避免大日志一次加载到内存。

**替代方案**：
- 独立日志表（`task_instance_log`）：更干净但要改查询逻辑，Phase B 不急。
- 外部日志存储（ES/S3）：属于 P2 方案。

### D5: 日期格式统一方案

**决策**：后端 Jackson 全局配置 `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")`，通过 `Jackson2ObjectMapperBuilderCustomizer` Bean 注册 `LocalDateTimeSerializer`/`LocalDateTimeDeserializer`。前端 `formatDateTime()` 同步改造。

**理由**：
- 一处配置全局生效，所有 `LocalDateTime` 字段自动使用统一格式。
- Spring Boot 4（Jackson 3）的 `ObjectMapper` 在 `tools.jackson.databind.*`，但 Customizer 接口不变。
- 前端同步改保证前后端格式一致。

### D6: 编辑权限规则

**决策**：只有 `DRAFT` 状态的任务可以编辑。`ONLINE` 状态的任务必须先下线（`POST /{id}/offline`）才能编辑。

**理由**：
- DolphinScheduler 和 DataWorks 都有类似约束：上线的任务不能直接改。
- 防止运行中的任务被意外修改导致执行异常。
- `has_draft_change` 字段已存在，可用于标记"已修改但未发布"。

### D7: MCP 工具与 REST API 统一

**决策**：新增的 CRUD 和实例操作同时暴露为 REST API 和 MCP 工具。MCP 写工具经 `GatedActionService` 闸门（L1 级别）。

**理由**：
- 保持现有架构一致性：REST 直连领域服务，MCP 经闸门。
- Agent 也需要能执行这些操作（"帮我改一下这个任务"）。

### D8: Schema 演进策略

**决策**：全部使用 `ADD COLUMN`（向后兼容），不做 `DROP` 或 `RENAME`。类型修改（`VARCHAR→TEXT`）通过 `ALTER COLUMN`。H2 和 PostgreSQL 语法差异在 `schema.sql` 中用注释标注。

**理由**：
- 现有 H2 开发 + PostgreSQL 生产的双模式需要兼容。
- `ALTER COLUMN ... TYPE TEXT` 在 H2 和 PostgreSQL 语法一致。
- 不删字段避免破坏现有 seed data 和代码引用。

## Risks / Trade-offs

| 风险 | 影响 | 缓解 |
|------|------|------|
| 单点调度器重复触发 | 同一工作流被多次执行 | Phase B 单实例部署；`last_fire_time` 乐观锁防重；后续引入分布式锁 |
| 暂停后恢复的下游状态不一致 | 部分节点已跑、部分未跑 | 恢复时检查每个节点的 `TaskInstance.state`，只调度 `NOT_RUN` 的节点 |
| log TEXT 字段大表性能 | 日志累积后查询变慢 | Phase B 可接受；后续引入日志归档（超 30 天的实例日志清理） |
| Schema 变更 H2/PG 兼容性 | DDL 语法差异导致一处能跑一处报错 | `ALTER COLUMN ... TYPE TEXT` 两端都支持；新增字段都有 DEFAULT 值 |
| Jackson 全局日期格式影响其他模块 | Agent 审计表（`agent_session` 等）的时间格式也会变 | 这是预期行为——全局统一就是要所有模块一致 |
| `WorkflowDependency.dateOffset` 修复可能影响 seed data | 现有 seed data 写入的是 String 值 | 修复后反而能正确持久化，是 bug fix 不是 break |

## Migration Plan

### 部署步骤

1. **DDL 先行**：`schema.sql` 更新 → H2 重启自动建表；PostgreSQL 需手动执行 migration SQL。
2. **后端部署**：`./mvnw install -DskipTests` → 重启 `dataweave-api`。
3. **前端部署**：`pnpm dev` 自动热更新。
4. **验证**：浏览器打开 `http://localhost:3000`，确认任务列表渲染、搜索可用、创建/编辑/删除流程通。

### 回滚策略

- Schema 变更全部是 `ADD COLUMN`，回滚只需回退代码，新增字段自动忽略。
- `VARCHAR→TEXT` 类型变更不可逆（不能 `TEXT→VARCHAR(4000)` 回退），但 TEXT 是 VARCHAR 的超集，功能不受影响。

### Open Questions

1. ~~`task_def` 要不要加 `cron_expression`（任务级 cron）？~~ → 已决策：不加，统一走工作流级调度（D1）。
2. **并发控制要不要 Phase B 就加？** → 暂不加。`worker_nodes.max_concurrent_tasks` 字段先建好，但调度引擎 Phase B 不做并发检查。后续 Phase 再实现排队逻辑。
3. **前端编辑抽屉用 shadcn Sheet 还是 Dialog？** → 建议 Sheet（侧抽屉），workspace 已有右侧面板模式。实施时确定。
