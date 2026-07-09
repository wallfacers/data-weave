# Research: 血缘目录接地（Catalog Grounding）

Phase 0 决策记录。所有 spec 澄清项已在 `/speckit-clarify` 解决（作用通道=仅推断类、启用=绑定数据源即开+kill-switch、留痕=专用审计表、执行=强制异步）；本文档补齐落地所需的机制性决策。

## D1. 三态存在性如何从既有基建取得

**Decision**：在 `DatasourceSchemaResolver` 新增 `probeTable(datasourceId, qualifiedName) → TableExistence{PRESENT|ABSENT|UNKNOWN}`，用 `DatabaseMetaData.getTables(catalog, schema, table, null)` 而非 `getColumns`；在 `DatasourceBoundCatalog` 新增 `probeExistence(...)` 组合链：cache/neo4j 命中→直接 `PRESENT`，miss 且绑定数据源→调 resolver live probe，未绑定→`UNKNOWN`。

**Rationale**：现有 `lookupTable`/`fetchColumns` 返回 `Optional<TableSchema>`，`empty` **同时**表示"表不存在"与"连不上/超时/非 JDBC"——正是 grounding 必须区分的 `ABSENT` vs `UNKNOWN`，Optional 无法承载。`getTables` 成功返回结果集：有行=PRESENT，无行=ABSENT（连接成功、目录权威确认缺席）；任何异常/超时/解密失败/非 JDBC 类型=UNKNOWN。`getTables` 比 `getColumns` 轻（不迭代列），且 grounding 只需存在性。cache/neo4j 命中即 PRESENT 是零成本短路（复用 053 已回填的列目录）。

**Alternatives rejected**：
- 复用 `lookupTable` 的 `empty` 当 ABSENT——**错**，会把连接故障误判成缺席→误杀真表，违反 SC-003。
- 用 `getColumns` 空结果判 ABSENT——`getColumns` 空既可能是无列也可能是无表，且比 getTables 重。
- 单独跑 `SELECT 1 FROM tbl LIMIT 0`——违反"仅只读元数据、绝不执行数据查询"安全边界（053 FR-013）。

## D2. `ABSENT` 剔除的作用通道边界（落到 Source 枚举）

**Decision**：**可剔除（推断类）= `{SCRIPT_INFERRED, SCRIPT_MODEL, SCRIPT_AGENT}`**（规则推断 / 小模型 / 云 AI）；**永不剔除（确定性/声明）= `{SQL_PARSED, SCRIPT_SQL, AGENT, FORM, null}`**。确定性来源命中 `PRESENT` 仅升 confidence=CONFIRMED 加"已核验"，命中 `ABSENT` 也**只留痕不剔除**。

**Rationale**：FR-011 的保护判据是"Calcite 已成功解析"。`SQL_PARSED`（独立 SQL Calcite 解析）与 `SCRIPT_SQL`（脚本内嵌 SQL 且**解析成功**，enricher `priority()` 中与 SQL_PARSED 同列 0）都是确定性解析产物；`AGENT`（coding agent 在 `.task.yaml` 显式声明）与 `FORM` 是人/agent 显式断言，非模型推断——一并保护。真正会幻觉/误产 CTE/临时名的是无真解析器的 `SCRIPT_INFERRED`（正则规则）、`SCRIPT_MODEL`（小模型）、`SCRIPT_AGENT`（云 AI）。这样既清掉残余 FP 主体，又规避**跨数据源真表**被 Calcite 正确解析却因不在本数据源目录而遭误杀。

**Alternatives rejected**：全通道统一剔除——精度略高但会误杀 Calcite 正确解析的跨数据源真表边，违背"宁缺毋滥不等于误杀真表"。

## D3. 异步接缝——grounding 挂在哪、如何绕开 AI 门控

**Decision**：grounding 作为一个阶段插入 `LineageAgentEnricher.enrich()` 的 `dedupeIo/dedupeCol`（merge）之后、`recordTaskIo`（keyed replace）之前。重构早退逻辑：当前 `if (cfgOpt.isEmpty() || !enabled) return;` 会在 AI 未配置时整段跳过、连确定性重算+replace 都不做；改为——**若 grounding 开启且任务绑定数据源，即使 AI 未启用也要重算确定性全集 + 执行 grounding + replace**；AI 边生成仍受原 `shouldEnrich` 门控。全局 kill-switch（`lineage.grounding.enabled`，默认 true）关闭时回到今天行为（AI-off 则完全早退）。

**Rationale**：`LineageEnrichmentTrigger.request(...)` 在 `TaskService:535` 与 `ProjectSyncService:940` **每次 push 无条件发布**事件（AI 门控在 enricher 内部），异步管线已覆盖所有 push——无需新建触发点。用同一个消费者做 grounding，避免第二条消费者与 enricher 各自 `recordTaskIo` 对同一任务边的 replace **竞争**（后写覆盖先写）。事件已携 `tenantId/projectId/taskDefId/taskType/calciteParsed/agentReads/agentWrites`，足够 grounding 用。

**Alternatives rejected**：
- 新增独立 grounding 异步消费者订阅同 channel——与 enricher 的 keyed replace 竞争，二者顺序不定→边集互相覆盖。
- 在同步 `recordLineage` 里做 grounding——违反强制异步（FR-008），且真连库探测会拖慢 push。

## D4. "catalog-verified" 标如何表示（不动 IoEdge 结构）

**Decision**：`PRESENT` 采纳的边把 `Confidence` 从 `UNVERIFIED` 升到 `CONFIRMED`；权威的核验事实落 `lineage_grounding_disposition` 审计表（verdict=PRESENT, disposition=ADOPTED）。冲突消解（FR-012）在 enricher 既有 `dedupeIo/dedupeCol` 的 source-priority 之上加 confidence 次级偏好：同键同 source 时 CONFIRMED 胜 UNVERIFIED。

**Rationale**：`Confidence.CONFIRMED` 语义正是"元数据齐全印证"，与 catalog-verified 天然契合；复用它避免给 `IoEdge` record 加字段（波及所有构造点）。审计表承载可查询的处置真相，满足 SC-006。前端零改动（v1）沿用 053 来源/置信展示。

**Alternatives rejected**：给 `IoEdge` 加 `boolean catalogVerified` 字段——侵入 record 与全部调用点，收益不抵成本；`Confidence` 已够。

## D5. 系统 / 元数据 schema 集合

**Decision**：`SystemNamespaceClassifier` 内置一套集合：**通用** = `{information_schema}`；**引擎特定**（按 `Datasource.typeCode`）——PostgreSQL=`{pg_catalog, pg_toast}`、MySQL/MariaDB/StarRocks/Doris=`{mysql, sys, performance_schema, information_schema}`、SQLServer=`{sys, INFORMATION_SCHEMA}`、Oracle=`{SYS, SYSTEM}`、H2=`{INFORMATION_SCHEMA}`。判定按候选限定名的 schema 段大小写不敏感匹配。允许配置 `lineage.grounding.system-schemas` 追加覆盖。

**Rationale**：系统表在目录中**真实存在**，US1 存在性判据会误采纳（PRESENT），必须显式排除（FR-010）。集合按引擎划分因各库系统命名空间不同。内置覆盖主流引擎，配置口兜底长尾。`data.sql` 种子命名豁免不受影响。

**Alternatives rejected**：靠"系统表列多/命名前缀"启发式——不可靠、过拟合；直接查 `getSchemas` 判系统标志位——各驱动不统一，不如显式集合稳。

## D6. 处置留痕载体（审计表 vs 复用 053）

**Decision**：新增专用表 `lineage_grounding_disposition`（详见 data-model.md），schema 0.11.0→**0.12.0**；不复用 053 `lineage_agent_call`。

**Rationale**：`ABSENT` 剔除的候选**无边可挂**元数据，必须独立落库才可持久审计（SC-006）。`lineage_agent_call` 是 AI 外呼专用（config_id/protocol/latency 语义），而 grounding 覆盖全推断通道（含小模型/规则，无 AI 外呼），语义拉伸会污染 053 审计表。独立表语义干净。按项目约定新表须 bump schema 版本。

**Alternatives rejected**：仅结构化日志——不可持久查询审计，弱化 SC-006；扩 `lineage_agent_call`——语义混淆。

## D7. 新鲜度与幂等

**Decision**：复用 053 `DatasourceBoundCatalog` 的重 push 失效（`evict` 候选表）+ TTL 兜底（默认 6h）。grounding 探针结果不额外缓存三态本身，而是靠底层 catalog 层缓存 PRESENT；ABSENT/UNKNOWN 不缓存（下次 push 重探，保证删表/建表能翻转结论）。enricher 的 `recordTaskIo` 是 keyed replace，天然幂等，grounding 重复运行结论稳定。

**Rationale**：满足 FR-013；ABSENT 不缓存避免"曾经不存在的新建表"长期被判缺席。

## 未决/交给实现阶段

- `probeTable` 的 `getTables` 对少数驱动（Hive/Impala 等）行为差异——实现时对非标准驱动异常一律降级 UNKNOWN，不特化。
- 列级边随表剔除：`ABSENT` 剔除一张表时，其相关 `ColumnEdge`（srcTable/dstTable 命中该表）一并剔除——实现阶段在 grounding 过滤里连带处理，data-model 记为规则。
