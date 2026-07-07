# Implementation Plan: 血缘解析扩展——可配置云 AI Agent 抽取通道 + 数据源实时 Schema 解析

**Branch**: `053-lineage-llm-agent-schema` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/053-lineage-llm-agent-schema/spec.md`

## Summary

在既有血缘解析地基上补两块互相强化的能力：

1. **可配置云 AI Agent 抽取通道**——以配置驱动兼容 Anthropic / OpenAI 两种协议，直连云厂商大模型，作为 `ScriptLineageExtractor` 契约的第 4 条通道，补脚本长尾与 Calcite 解析失败的 SQL。因云端调用需数秒~数十秒，**异步后台富化**：push 立即返回，AI 边随后并入图谱。
2. **数据源实时 Schema 解析**——当列元数据目录查不到某表的列时（典型 `SELECT *`），复用任务绑定数据源的连接（含驱动隔离）直连数据库经 `DatabaseMetaData` 取回真实列清单，喂给确定性 Calcite 列级血缘，并作为大模型 schema 接地上下文抑制幻觉；结果回填 neo4j 列目录缓存（重 push 失效 + TTL 兜底）。

**技术进路**：全部落在 `dataweave-master` 模块既有血缘子系统内 + 一处 `schema.sql` 变更 + 一个薄前端配置表单。复用 `ScriptLineageService` 聚合器、`ColumnLineageCatalog` 接口、`JdbcConnectionTester` 连接机器、`InMemoryEventBus`/Redis 异步基建、`Neo4jLineageStore.ensureColumn` 的 dataType/ordinal 预留写入位、`DatasourceEncryptor` 凭据加密——零内核重写。

## Technical Context

**Language/Version**: Java 25（backend），TypeScript / Next.js 16 + React 19（frontend 薄配置 UI）

**Primary Dependencies**: Spring Boot 4.0 / WebFlux、Spring Data JDBC + JdbcTemplate、Apache Calcite（既有列级引擎）、neo4j-java-driver（血缘存储）、JDK `HttpClient`（AI 外呼，master 零新增依赖，沿用 `ModelExtractor` 选型）、JDBC `DatabaseMetaData`（schema 抓取）

**Storage**: PostgreSQL / H2（配置表 `lineage_agent_config` + 审计 `lineage_agent_call`）；neo4j（血缘图 + 列目录，唯一血缘存储）；进程内列 schema 缓存（Caffeine 风格 TTL Map 或轻量自实现）

**Testing**: JUnit 5 + AssertJ；neo4j IT 直连 `etl-neo4j` 真验（沿用 018/024/025 姿态）；H2 + PG 双方言各测一遍；前端 vitest + Playwright 浏览器门

**Target Platform**: Linux server（peer master 进程内）

**Project Type**: web（backend 多模块 DDD + frontend）；本特性 backend 为主、frontend 为薄 CRUD 配置面

**Performance Goals**: 实时 schema 解析对 push 额外耗时 ≤ 2s（SC-004）；AI 通道异步、对 push 耗时零增加；同表二次解析缓存命中率 ≥ 90%（SC-006）

**Constraints**: 血缘为增强、绝不阻断 push（既有不变量）；AI 外呼不嵌入进程（远端 HTTP，宪法 IV）；凭据加密 + 脱敏 + 不落日志；确定性来源优先消解（内嵌 SQL/Calcite > 规则 > AI > 小模型）

**Scale/Scope**: 单库表列数上限保护（大宽表截断/降级，FR-014）；AI 外呼并发/频次上限（FR-023）；配置按租户/项目隔离，默认关闭

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
|------|------|------|
| **I. Files-First** | ✅ PASS | 不改任务/目录的文件表示；血缘为派生增强，非定义。AI 配置是治理侧配置（DB），非任务定义。 |
| **II. Server 为治理真相源** | ✅ PASS | AI 配置、schema 缓存、审计均服务端持有，按租户/项目隔离；schema 抓取只读外部库元数据、不双向同步。 |
| **III. 两条腿调试** | ✅ 不涉及 | 不触碰 CLI 本地运行时 / 执行器语义。 |
| **IV. AI 归位本地（NON-NEGOTIABLE）** | ✅ PASS（需记录边界） | 三条不可让渡内核逐条核对见下方；结论：**合规**。 |
| **V. 复用内核** | ✅ PASS | 复用 `ScriptLineageExtractor` 契约、`ColumnLineageCatalog`、`JdbcConnectionTester`、EventBus、`Neo4jLineageStore`、`DatasourceEncryptor`；不新建平行血缘/连接/加密内核。 |

**原则 IV 边界核对**（关键——一个 naive 读法「服务端调 LLM」易被误判为违规，故显式论证）：

1. *服务端无 AI 大脑*：本特性不新增聊天座舱 / AG-UI / IntentRouter / 决策 agent 逻辑。AI 外呼是一次**窄用途、best-effort 的元数据抽取**（脚本→表/字段读写），与 041 已交付并被宪法认可的 `ModelExtractor` 推断通道**同性质**；不含推理决策/agent 编排。**推理不嵌入进程**——远端 HTTP 调用，与小模型独立 sidecar 姿态一致。→ 不构成"AI 大脑"。
2. *AI 能力由本地 agent 提供*：面向开发者的**创作 AI** 仍只在本地 agent（Claude Code/Codex）。本通道是治理侧的血缘富化，不向开发者提供创作/对话能力，不与本地 agent 职责重叠。
3. *拆除不得损伤观测/调度*：本特性纯增量，不触碰 ops/metrics/run logs/DAG/调度内核。

**记录**：以上边界写入 spec Assumptions（"合规姿态沿用宪法原则 IV"）；如评审认为需更强隔离，退路是把 AI 抽取也做成独立 sidecar（如小模型），但当前以进程内 HTTP 客户端交付（与 `ModelExtractor` 对齐），无 Complexity Tracking 违规项。

## Project Structure

### Documentation (this feature)

```text
specs/053-lineage-llm-agent-schema/
├── plan.md              # 本文件
├── research.md          # Phase 0：8 项技术决策
├── data-model.md        # Phase 1：实体 + neo4j 富化 + schema.sql 变更
├── quickstart.md        # Phase 1：端到端验证脚本
├── contracts/           # Phase 1：抽取器契约 + AI 协议适配 + 配置 API + schema 目录
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令产出）
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/
├── domain/lineage/
│   └── Source.java                              # 改：+ SCRIPT_AGENT 枚举值
├── application/lineage/
│   ├── ColumnLineageCatalog.java                # 既有接口（不改签名）
│   ├── DatasourceBoundCatalog.java              # 新：绑定 datasourceId 的组合 catalog（neo4j 缓存 → 实时抓取兜底 → 回填）
│   └── script/
│       ├── ScriptLineageExtractor.java          # 既有契约（不改）
│       ├── AgentLineageExtractor.java           # 新：第 4 条通道（AI，供异步流程调用）
│       └── ScriptLineageService.java            # 改：CHANNEL_PRIORITY 插入 SCRIPT_AGENT；拆同步/异步入口
├── application/lineage/agent/
│   ├── LlmAgentClient.java                      # 新：双协议外呼客户端（JDK HttpClient）
│   ├── AnthropicProtocolAdapter.java            # 新：Anthropic Messages 协议
│   ├── OpenAiProtocolAdapter.java               # 新：OpenAI Chat Completions 协议
│   ├── LineageExtractionPrompt.java             # 新：抽取提示构造（含 schema 接地）+ 结构化输出解析
│   ├── AgentLineageConfigService.java           # 新：配置 CRUD + 加密/脱敏 + 启用判定
│   └── LineageAgentEnricher.java                # 新：异步富化编排（消费 EventBus 事件）
├── application/
│   └── DatasourceSchemaResolver.java            # 新：复用连接直连库取列（DatabaseMetaData/视图/上限保护）
├── infrastructure/lineage/
│   ├── Neo4jColumnLineageCatalog.java           # 既有（读缓存，不改）
│   └── Neo4jColumnBackfillWriter.java           # 新（或扩 Neo4jLineageStore）：回填列 schema + 失效
├── infrastructure/
│   └── AgentConfigRepository.java               # 新：lineage_agent_config JdbcTemplate 仓储
└── interfaces/
    └── LineageAgentConfigController.java        # 新：配置 REST（脱敏返回）

backend/dataweave-api/src/main/resources/schema.sql   # 改：+ lineage_agent_config + lineage_agent_call；schema_version 0.10.0 → 0.11.0

frontend/
├── components/workspace/views/                   # 数据源/连接配置视图内新增「血缘 AI Agent」配置表单
├── messages/{zh-CN,en-US}.json                   # 新 i18n 键（配置面文案）
└── ...（薄 CRUD：协议选择/端点/模型/Key 脱敏/启用开关/测试连通）
```

**Structure Decision**: web 多模块。绝大部分改动集中在 `dataweave-master` 血缘子系统（application/lineage 及新增 agent 子包），一处 `schema.sql` 加两表并升版本，一个薄前端配置面。三条既有血缘触发点（`TaskService.recordLineage`、`ProjectSyncService` push、以及列 catalog 装配）为主要接缝：同步路径记确定性边不变，push 后发一个富化事件驱动异步 AI 通道。

## 关键设计接缝与风险（详见 research.md）

- **异步不擦除同步边**：neo4j 边为「按 taskDefId replace（先删后建）」。异步 AI 富化 MUST NOT 单独 replace（会擦除同步确定性边）。方案：异步流程重跑「确定性 + AI」全量集合后做**一次** keyed replace（superset 幂等覆盖），确定性解析可复现，代价是解析跑两次——可接受。
- **catalog 缺 datasourceId**：`ColumnLineageCatalog.lookupTable` 无数据源坐标，无法连库。方案：在 `recordLineage` 按任务的 `datasource_id` 构造 `DatasourceBoundCatalog`（neo4j 缓存优先 → 未命中走 `DatasourceSchemaResolver` 实时抓取 → 回填 neo4j + 进程缓存），不改接口签名，仅改装配点传参。
- **双协议语义等价**：两适配器把各自响应归一为同一 `{reads[], writes[], columnEdges[]}` 结构；防幻觉校验（表名字面命中脚本 + 列落在真实列集合内）在协议之上统一执行。
- **确定性优先消解**：`Source` 加 `SCRIPT_AGENT`，`CHANNEL_PRIORITY` = `SCRIPT_SQL > SCRIPT_INFERRED > SCRIPT_AGENT > SCRIPT_MODEL`（FR-004a）。
- **凭据安全**：`api_key` 经 `DatasourceEncryptor` 加密入库；Controller/DTO 脱敏（只回 `sk-****` 尾 4 位）；client 日志脱敏。

## Complexity Tracking

> 无 Constitution 违规项需要豁免。原则 IV 边界已在 Constitution Check 显式论证并被 041 先例覆盖，不计入违规。
