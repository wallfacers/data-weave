# Phase 0 Research: 血缘 AI Agent 通道 + 数据源 Schema 解析

所有 Technical Context 无 NEEDS CLARIFICATION（4 项已在 `/speckit-clarify` 固化）。以下为 8 项技术决策。

---

## D1. AI 外呼客户端选型：JDK HttpClient vs WebClient

- **Decision**: JDK `HttpClient`（同步 `send`），与既有 `ModelExtractor` 一致。
- **Rationale**: master 模块零新增依赖；AI 通道运行在异步富化线程池内，阻塞语义天然匹配；无需 reactive 背压。SB4 无 `WebClient.Builder` 自动配置，用 WebClient 反需自建 `@Bean` 且引 reactor 到血缘子系统。
- **Alternatives**: WebClient（reactive，但与 push 后台批处理不匹配）；OkHttp（新增依赖，无收益）。

## D2. 双协议适配：Anthropic Messages vs OpenAI Chat Completions

- **Decision**: 一个 `LlmAgentClient` + 两个 `ProtocolAdapter`（`AnthropicProtocolAdapter` / `OpenAiProtocolAdapter`），按配置 `protocol` 字段分发。两者把响应归一为同一 `AgentExtraction{reads[], writes[], columnEdges[], confidence}` 结构。
- **Rationale**: 两协议差异仅在：① 端点路径（`/v1/messages` vs `/v1/chat/completions`）；② 鉴权头（`x-api-key` + `anthropic-version` vs `Authorization: Bearer`）；③ 请求/响应 JSON 结构（`system`+`messages`+`content[]` vs `messages`+`choices[]`）；④ 结构化输出方式（Anthropic tool_use / OpenAI response_format json_schema）。归一层之上统一做防幻觉校验，协议差异对上层不可见（FR-002）。覆盖任意 OpenAI-兼容 / Anthropic-兼容云网关（DashScope/DeepSeek/Kimi/OpenAI/Claude 等）。
- **Alternatives**: 引 LangChain4j / 官方 SDK（重依赖、绑定供应商、与"任意兼容端点"目标冲突）；只支持 OpenAI 协议（不满足 FR-002）。

## D3. 执行时机：异步后台富化（澄清 Q1）

- **Decision**: push 同步路径记录确定性血缘不变；随后发布 `LineageAgentEnrichmentRequested` 事件到 `EventBus`；`LineageAgentEnricher` 监听并在有界线程池 + 预算内执行 AI 抽取，再做一次全量 keyed replace 并入图谱。
- **Rationale**: 云端调用数秒~数十秒，不得阻塞交互式 push（SC-004 AI 对 push 零增耗）。复用既有 `InMemoryEventBus`（单机）/ `RedisEventBusConfig`（peer master 分布式）——零新基建。事件只携带 `(tenantId, projectId, taskDefId)`，enricher 自行按需重载任务内容 + 连库，符合"持久态在事务内、HTTP 在事务外"的调度不变量。
- **不擦除同步边**：neo4j 边按 taskDefId replace（先删后建）。异步只写 AI 边会擦除同步确定性边 → **异步流程重算「确定性 + AI」全集后一次 replace**（superset 幂等覆盖）。确定性解析可复现，代价是解析跑两次，可接受。
- **Alternatives**: push 内同步大预算（阻塞 push 数十秒，UX 差，已被 Q1 否决）；仅显式触发（需新触发面，Q1 否决）；Spring `@Async`（不跨 peer master，且血缘富化宜走已有 EventBus 统一）。

## D4. 数据源实时 Schema 抓取：复用 JdbcConnectionTester 连接机器

- **Decision**: 新增 `DatasourceSchemaResolver`，复用 `JdbcConnectionTester` 的连接建立与**驱动隔离加载**（`driver_jar_id` → `IsolatedDriverLoader`；否则内置驱动），拿到 `Connection` 后用 `DatabaseMetaData.getColumns(catalog, schema, table, null)` 取列（名/序号/类型），视图经同一 API 或 `getColumns`（视图对 metadata 等同表）解析输出列。
- **Rationale**: 连接、解密（`DatasourceResolver.decryptPassword`）、驱动隔离已在 `JdbcConnectionTester`/`DatasourceResolver` 沉淀，直接复用零漂移；`DatabaseMetaData` 跨方言统一（MySQL/PG/Oracle/Hive…），已被 `QualityCheckRunner` SCHEMA 断言 TODO 预告为标准做法。
- **上限保护（FR-014）**: 单表列数上限（如 2000）+ 单次抓取超时（如 3s）；超限截断/降级并留 hint。
- **限定名规范化（FR-015）**: 裸表名按连接的默认 catalog/schema（`Connection.getSchema()`/`getCatalog()`）补全，与既有 `NameNormalizer`/血缘坐标同源。
- **Alternatives**: 各库 `information_schema.columns` 手写方言 SQL（碎片化、维护差）；只用 neo4j 预登记（无法解决未登记表，即用户诉求本身）。

## D5. Catalog 分层：DatasourceBoundCatalog 组合链

- **Decision**: 新增 `DatasourceBoundCatalog implements ColumnLineageCatalog`，在 `recordLineage` 按任务 `datasource_id` 构造，`lookupTable` 顺序：① 进程内 TTL 缓存 → ② neo4j 列目录（既有 `Neo4jColumnLineageCatalog`）→ ③ `DatasourceSchemaResolver` 实时抓取，命中即回填 ②③ 到 ①② 与 neo4j。不改 `ColumnLineageCatalog.lookupTable` 签名（仍 `tenantId, projectId, qualifiedName`）——datasourceId 由构造时闭包持有。
- **Rationale**: 接口签名无 datasourceId，改签名会波及所有实现与调用点；用"每次解析构造一个绑定数据源的 catalog 实例"闭包传参，改动面最小、隔离清晰。既有 `SqlColumnLineageExtractor.extract(sql, catalog, ...)` 直接吃这个组合 catalog，Calcite `SELECT *` 展开无感获益。
- **Alternatives**: 改接口加 datasourceId 形参（波及面大）；全局单例 catalog 内部查任务绑定（catalog 不该知道任务，职责错位）。

## D6. Schema 缓存新鲜度：重 push 失效 + TTL 兜底（澄清 Q4）

- **Decision**: 进程内 `Map<tableKey, (TableSchema, expireAt)>`；(a) 任务 push 时先 evict 其候选表（reads+writes）的缓存条目再解析；(b) 每条目带 TTL（默认 6h，`lineage.schema-cache.ttl` 可配）兜底。neo4j 列目录作为跨进程持久缓存，其新鲜度随回填更新（列变更时覆盖 dataType/ordinal）。
- **Rationale**: 贴合 push 触发时机——开发者改任务重 push 正是 schema 可能变的时刻；TTL 防长期不 push 的表结构漂移。命中率目标 SC-006 ≥ 90%。
- **Alternatives**: 纯 TTL（表结构变更窗口期陈旧，Q4 否决）；仅手动刷新（需新入口，Q4 否决）；监听 DDL（跨库不可行）。

## D7. 任务类型范围：脚本 + Calcite 解析失败的 SQL（澄清 Q3）

- **Decision**: `AgentLineageExtractor.supports` 覆盖 PYTHON/SHELL/SPARK；对 SQL 任务，仅当 Calcite 表级/列级解析失败（`ColumnLineageResult.parsed()==false` 且表级也空/异常）时，由异步富化流程判定"Calcite 解析失败"后触发 AI 兜底。Calcite 正常解析的 SQL 不外呼（FR-001，避免冗余成本/幻觉）。
- **Rationale**: 精准补盲区不叠加冗余。Calcite 成功即确定性最优，AI 无增益反增幻觉风险与成本。
- **实现**: 富化事件携带一个 `calciteParsed` 标志（同步路径已知），enricher 据此决定 SQL 任务是否走 AI。
- **Alternatives**: 全任务叠加 AI（成本/幻觉高，Q3 否决）；仅脚本（漏 Calcite 解析失败的 SQL 长尾，Q3 否决）。

## D8. 配置存储、加密、审计与安全（US4）

- **Decision**:
  - 新表 `lineage_agent_config`（租户/项目隔离，默认 `enabled=false`）：`protocol`(ANTHROPIC/OPENAI) / `base_url` / `model` / `api_key_enc` / `timeout_ms` / `rate_limit_per_min` / `max_columns`。`api_key_enc` 经既有 `DatasourceEncryptor` 加密。
  - Controller/DTO 脱敏：只回 `sk-…{末4位}`，绝不回明文；client 结构化日志脱敏 key。
  - 新表 `lineage_agent_call`：每次外呼留痕（config_id / protocol / task_def_id / latency_ms / status[SUCCESS|DEGRADED|REJECTED] / created_at），供审计（FR-021）。
  - 未开启项目：`supports` 返回 false，`LineageAgentEnricher` 直接旁路——零外呼（FR-019/SC-005）。
  - 频次/并发上限（FR-023）：enricher 线程池容量 + 每配置 `rate_limit_per_min` 令牌桶。
- **Rationale**: 复用 `DatasourceEncryptor` 与数据源密码同源保护姿态（FR-020）；专用 call 表比复用 `agent_action`（PolicyEngine 写闸门语义，面向 agent 写操作）更贴合"系统内部外呼审计"，避免语义混淆。
- **Alternatives**: 复用 `agent_action`（语义错配——这不是 agent 发起的受闸写操作）；配置放 `datasources.props_json`（与数据源实体耦合，且 AI 配置本非数据源）；环境变量配置（不满足"按项目可配 + 界面管理"FR-003/FR-019）。

---

## 依赖既有能力索引

| 复用点 | 位置 |
|--------|------|
| 抽取器可插拔契约 | `application/lineage/script/ScriptLineageExtractor` + `ScriptLineageService` |
| 列元数据接口 | `application/lineage/ColumnLineageCatalog` + `infrastructure/lineage/Neo4jColumnLineageCatalog` |
| 真实连接 + 驱动隔离 | `infrastructure/JdbcConnectionTester` + `IsolatedDriverLoader` + `application/DatasourceResolver` |
| 血缘写入 + 列 MERGE | `infrastructure/lineage/Neo4jLineageStore.recordTaskIo` / `ensureColumn`（dataType/ordinal 预留位）|
| 异步基建 | `infrastructure/InMemoryEventBus` / `RedisEventBusConfig` |
| 凭据加密 | `application/DatasourceEncryptor` |
| 血缘触发点 | `application/TaskService.recordLineage` + `application/ProjectSyncService`（push）|
| 冲突消解 + 裁决重放 | `ScriptLineageService`（CHANNEL_PRIORITY + correctionGate）|
