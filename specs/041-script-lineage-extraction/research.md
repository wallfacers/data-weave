# Research: 脚本任务血缘解析（041-script-lineage-extraction)

**Date**: 2026-07-03 · 所有 file:line 基于 worktree `dw-041-script-lineage` 当前 HEAD。

## 现状基线（代码实证）

- 设计态血缘两条触发路径：`TaskService.createAndOnline → recordLineage`（TaskService.java:407/475）与 `ProjectSyncService.push` "5c-lineage" 段（ProjectSyncService.java:848-905）。两处均 try-catch 吞异常零阻断（FR-007 先例）。
- `SqlTableExtractor.extract(String sql)`（SqlTableExtractor.java:52）无状态、纯文本入参、内建 `parseStmtList` 多语句切分——**可直接对脚本中挖出的 SQL 片段复用**。
- `LineageStore.recordTaskIo`（LineageStore.java:36）内建 replace-per-task 语义：按 taskKey 先删 `READS|WRITES|READS_COL|WRITES_COL|FLOWS_TO|DERIVES_FROM` 旧边再建（Neo4jLineageStore.java:68-72）——FR-008 整体替换白得。
- 边属性现状：`READS/WRITES {source, confidence, version, taskDefId}`（Neo4jLineageStore.java:104）；`FLOWS_TO {taskDefId}` 仅一个属性（:118）——读侧 `FlowEdgeView.confidence` 期望有值但写侧未写，是既有缝。
- 前端 `lineage-flow.tsx:120` 已有 `confidence === "UNVERIFIED" → 虚线` 先例；边无 onClick、无详情面板；图谱节点类型只有 DATASOURCE/TABLE/COLUMN/METRIC——**任务不是节点，以边呈现**。
- 血缘 PG 表已全部退役（schema.sql:1004 空占位），neo4j 是血缘边唯一存储；当前 `schema_version = 0.6.2`。
- `task_def.content VARCHAR(4000)`（schema.sql:290）为脚本源码唯一存储，天然限制抽取输入规模。
- 任务类型为字符串常量（无枚举）：SQL/SHELL/PYTHON/SPARK/ECHO/DATA_SYNC。
- 写操作门禁先例：`GatedActionService.submit`（GatedActionService.java:49-102）+ `policy_rules` seed（data.sql:563-600，PROJECT_PUSH=L1）+ `DefaultPlatformActionExecutor.execute` switch 分发（:105-140）。

## D1 — 内嵌 SQL 抽取方式（P1 通道）

**Decision**: Java 侧轻量**词法扫描 + SQL 嗅探**，不引入 Python/Shell 真解析器：

1. 字符串字面量提取：Python（`'…' / "…" / '''…''' / """…"""`，跳过 `#` 注释；f-string 或含 `%s/{}/.format(` 插值痕迹的 SQL-like 字面量 → 判为动态 → 未解析提示）；Shell（单/双引号参数、heredoc 体，识别载体命令 `hive -e`、`beeline -e`、`psql -c`、`mysql -e`、`spark-sql -e` 及通用引号内 SQL）。
2. SQL 嗅探：字面量 trim 后首关键词 ∈ {INSERT, SELECT, CREATE, MERGE, UPDATE, DELETE, WITH, REPLACE} 才送解析，其余忽略。
3. 送 `SqlTableExtractor.extract` 复用 Calcite（多语句/CTE/UNION 已覆盖）；列级复用 `SqlColumnLineageExtractor`。

**Rationale**: 零新依赖、零子进程；`content ≤ 4000` 字符使词法扫描成本可忽略；Calcite 链路已被 SC-001 级别测试验证。
**Alternatives considered**: ① master 内起 python3 子进程跑 `ast` — 引入运行环境依赖与安全面，master 不该 spawn 进程（worker 才有 ControlledCommandExecutor），拒。② ANTLR Python/Bash 全语法 — 重依赖、维护成本高，对"提取字符串字面量"目标过度，拒。

## D2 — 推断通道（P2）与可插拔抽取器

**Decision**: 新增 `application/lineage/script/` 包：

- 接口 `ScriptLineageExtractor { supports(taskType); ScriptExtraction extract(ScriptSource) }`，`ScriptExtraction = {reads, writes, columnEdges, hints, channel}`。
- 内置两实现：`EmbeddedSqlExtractor`（D1，产 **SCRIPT_SQL** 来源边）、`ApiPatternExtractor`（模式表识别 `saveAsTable("t")`、`insertInto("t")`、`.to_sql("t"…)`、`.read_sql(`、`spark.read.table("t")`、`sqoop import/export --table t [--columns a,b]` 等，产 **SCRIPT_INFERRED** 来源边；显式列清单形态如 `df[["a","b"]].to_sql`、`--columns a,b` 产字段级边）。
- 编排器 `ScriptLineageService`：按 taskType 聚合各抽取器结果 → 应用 FR-009 冲突消解（同一 (方向,表) SCRIPT_SQL 优先）→ 应用剔除抑制（D4）→ 产出 ioEdges/columnEdges + hints。整体带时间预算（单任务 2s 上限，超时降级为未解析留痕）。
- 模式表 MVP 硬编码在 `ApiPatternExtractor`（常量表），不入库——规则数据化留给小模型特性一并考虑。

**Rationale**: FR-010 的可插拔即接口 + Spring 多实现注入，后续小模型 = 新增一个 `ScriptLineageExtractor` 实现，存储/展示层零改动。
**Alternatives considered**: 规则存 `policy_rules` 式 DB 表 — 提前抽象，MVP 无第二消费者，拒（YAGNI）。

## D3 — 来源/置信度在图上的表达

**Decision**: 复用既有属性位与读侧枚举，不新增图模型概念：

- `READS/WRITES.source` 新增取值 `SCRIPT_SQL` / `SCRIPT_INFERRED`（现值旁路不动）；`confidence`：SCRIPT_SQL → `CONFIRMED`，SCRIPT_INFERRED → `UNVERIFIED`，人工确认后 → `CONFIRMED`（附 `confirmedBy`）。
- **补缝**：`FLOWS_TO` 边补写 `{source, confidence}`（取该任务读写边中较弱者），使 `FlowEdgeView.confidence` 读侧不再落空——SQL 任务同步受益。
- 前端沿用 `UNVERIFIED → 虚线` 既有先例，图例补"推断"条目。

**Rationale**: `FlowEdgeView.confidence` 枚举（CONFIRMED/UNVERIFIED/CONFLICT/DECLARED）语义现成贴合；不动 DTO 结构，只让空值变有值。
**Alternatives considered**: 新增独立 `provenance` 枚举贯穿 DTO — 与 confidence 语义重叠，前端双轴区分增加认知负担，拒。

## D4 — 人工修正与未解析提示的存储

**Decision**: 新增 **PG 两表**（neo4j 仍是血缘边唯一存储，此二者是治理状态/审计数据，不是边）：

- `lineage_edge_correction`：语义键（tenant_id, project_id, task_def_id, direction READ/WRITE, table_key, column_key NULL）+ status（CONFIRMED/REMOVED）+ operator + created_at；唯一约束于语义键。抑制在**写入时应用**：`ScriptLineageService` 产边后过滤 REMOVED 键、将 CONFIRMED 键的边升级 confidence——replace-per-task 每次重建也天然重放修正。
- `lineage_unresolved_hint`：task_def_id, version_no, kind（DYNAMIC_TABLE/DYNAMIC_SQL/TIMEOUT/PARSE_FAIL）, script_hint（截断片段+行号）, created_at；push 重建时按 task 整体替换（与边替换语义一致）。
- `schema_version 0.6.2 → 0.7.0`（新增两表，按约定 bump + 文件头 + 项目版本三处同步）。

**Rationale**: 抑制必须在 `recordTaskIo` 删边重建后仍生效 → 不能存边属性；关系型查询（按任务/操作人/时间列表）+ 与 agent_action 审计对账 → PG 合适。
**Alternatives considered**: neo4j 独立 `:Suppression` 节点 — 图里存非图数据，列表/审计查询别扭，且 h2/PG 测试链路无法覆盖，拒。

## D5 — 修正动作走门禁

**Decision**: 前端修正操作 → `POST /api/lineage/corrections`（controller 侧 `ProjectScope.require` 项目管理权限）→ 构造 `ActionRequest{actionType: LINEAGE_EDGE_CONFIRM | LINEAGE_EDGE_REMOVE | LINEAGE_CORRECTION_REVOKE, targetType:"LINEAGE_EDGE", command: 语义键 JSON, actorSource:"UI"}` → `GatedActionService.submit` → `DefaultPlatformActionExecutor` 新增 case 落 `lineage_edge_correction` 并同步修图（try-catch 降级）。`policy_rules` seed 三行均 **L1**（修正可撤销、非破坏性；剔除≠删数据，仅抑制展示）。

**Rationale**: "所有副作用操作过门禁无旁路"是 CLAUDE.md 硬约定；L1 直通 + agent_action 自动留痕满足审计，无需 L2 审批开销。
**Alternatives considered**: 剔除设 L2 审批 — 图谱修正是治理人员高频动作且可逆，审批开销不成比例，拒（policy_rules 数据驱动，未来可调级无需改码）。

## D6 — 触发点接线

**Decision**: 在既有两条路径的类型分叉处并联脚本通道：`ProjectSyncService.push` 5c-lineage 段与 `TaskService.recordLineage` 中，`type ∈ {PYTHON, SHELL}` → `ScriptLineageService.extract`，产物走**同一个** `lineageStore.recordTaskIo`；SQL 路径行为零改动。SPARK 类型顺带走 EmbeddedSqlExtractor（受益但不验收，spec 假设已声明）。

**Rationale**: 与 SQL 任务同一 replace/降级/坐标语义，FR-001"同一图谱统一呈现"白得。
**Alternatives considered**: 独立异步 job 抽取 — 引入最终一致与新调度面，push 耗时预算（SC-003 ≤20%）用同步方式在 4000 字符输入下轻松满足，拒。

## D7 — 前端呈现

**Decision**: 最小增量三件：① `lineage-flow.tsx` 边 `<polyline>` 加 onClick + 命中区（透明加粗描边），选中边打开**边详情面板**（复用 ImpactPanel 的面板样式）：任务名/来源/置信度/人工状态 + 该任务未解析提示列表 + 确认/剔除/撤销按钮；② 按钮用 `useProjectPermissions().can(...)` 门禁（left-nav.tsx:199 先例），无权限只读；③ 图例补"推断（虚线）"。i18n 全部落 `lineageView` 命名空间，双语同步。

**Rationale**: 图谱无任务节点（实证），任务信息在边上（taskDefId）；边详情面板是满足 FR-004/006/007 的最小 UI 面。
**Alternatives considered**: 图中引入任务节点 — 改图模型与布局算法（现为硬编码网格），影响面大大超出本特性，拒。

## D8 — 测试与验收基准

**Decision**: ① 单测语料库：`EmbeddedSqlExtractorTest`/`ApiPatternExtractorTest` 各建 ≥20 条带标注样例（正例/动态表名负例/注释假 SQL 负例），SC-001/002 的百分比在语料库上断言；② `ScriptLineageServiceTest` 覆盖冲突消解/抑制重放/超时降级；③ 集成：h2 profile `@SpringBootTest` 验证 push 触发 + 门禁修正闭环（遵循 backend 测试隔离不变量：h2 唯一库、redis health off）；④ 前端 vitest（边样式/权限显隐逻辑）+ 浏览器实测（admin/admin JWT 登录，深链 `/?open=lineage`）。

**Rationale**: SC 百分比必须有确定性样本集才可测；语料库即未来小模型的种子标注数据（呼应 spec 假设）。

## D9 — 模型选型（12G 显存约束）

**Decision**: 主路线 **Qwen2.5-Coder-1.5B-Instruct**（Apache-2.0）+ **QLoRA SFT**，任务建模为"脚本源码 → 受约束 JSON"生成（`{reads:[{table,columns?}], writes:[…]}`），确定性解码（temperature=0）。

**Rationale**: ① 代码特化预训练底座对 Python/Shell 语法敏感，长上下文（32k）覆盖 `content ≤ 4000` 字符无需滑窗；② 1.5B QLoRA 在 12G 单卡从容（bf16 base + 4bit 量化 + LoRA r=16，峰值显存 ~8G），全参 0.5B 亦可作对照；③ Apache-2.0 许可干净，可私有再发布；④ 生成式对合成数据的形态泛化优于固定 BIO 标签集，字段级读写配对天然可表达。
**Alternatives considered**: ① encoder token-classification（CodeBERT-base 125M，BIO 标注 TABLE_READ/TABLE_WRITE/COL）——推理最快、CPU 友好、置信度天然，但 512 token 上限需滑窗、读写配对与跨行关系表达笨拙，作**备选路线**记录（若生成路线幻觉率压不到 SC-006 ≤2% 再切换）；② 3B/7B 模型——12G 可 QLoRA 训但部署推理预算（p95 ≤2s）紧张，收益存疑，拒；③ 直接 prompt 现成大模型 API——违反"服务端数据不出境/本地推理"与成本可控预期，且本特性目标就是专用小模型，拒。

## D10 — 训练数据管线（可复现合成为主）

**Decision**: 三源合成管线（脚本化入库，`ml/` 目录，FR-014）：

1. **SQL 语料源**：`gretelai/synthetic_text_to_sql`（Apache-2.0，含表名/SQL 对）与 `b-mc2/sql-create-context`（cc-by-4.0）提供真实感 SQL 与表/列名池——implement 首个任务用 HF MCP **核实数据集可用性与许可**后锁定。
2. **模板注入**：将 SQL/表名程序化注入 Python/Shell 写表模板库（spark.sql/cursor.execute/saveAsTable/to_sql/sqoop/psql -c/hive -e/heredoc/自定义封装函数等 ≥30 种形态，含变量中转、多语句、干扰负例：注释假 SQL、f-string 动态表名、无关代码），标签由注入过程自动产生——**标注零人工且绝对准确**。
3. **种子与评估**：041 语料库（T006/T011 的 ≥40 手工样例）+ 追加手工样例构成 held-out 评估集（≥200 条，与训练模板池做形态隔离：评估集包含训练模板未见过的封装形态，专测泛化）。

规模目标：训练集 ~10k 样本（合成边际成本≈0），按形态分层采样防模板过拟合。**不使用租户生产脚本**（隐私边界，spec 假设）。
**Rationale**: "脚本→表血缘"无现成公开标注集（已知领域空白）；合成注入是唯一可全流程自动化且标签无噪声的路径；评估集形态隔离保证 SC-006 测的是泛化不是背模板。
**Alternatives considered**: the-stack/github-code 真实代码 + 规则弱标注——标签噪声引入天花板且许可审查重，仅作后续迭代增强，MVP 拒。

## D11 — 训练/评估/发布栈

**Decision**: Python 独立目录 `ml/lineage-extractor/`（不进 backend 模块）：`transformers + peft + trl(SFTTrainer) + bitsandbytes`；评估脚本输出 SC-006 三指标（表级 precision / 规则未覆盖子集 recall / 幻觉率）+ 混淆样本明细；工件经 `huggingface_hub` 发布到用户账号私有 repo（模型 `<user>/weft-lineage-extractor-1.5b`、数据集 `<user>/weft-script-lineage-synth`），评估报告 README 卡片化；HF 认证走 huggingface MCP / `hf auth login`。达标判据自动化：评估脚本以退出码表达是否过门槛（FR-014 未达门槛→不接入）。

**Rationale**: 训练栈与平台完全解耦（宪法 V 不适用于 ml 目录；backend 零 Python 依赖）；trl SFT + QLoRA 是 12G 单卡的社区标准配方，可复现性由固定 seed + 锁版本 requirements 保证。

## D12 — 推理服务与平台接入（宪法 IV 合规姿态）

**Decision**: 独立推理 sidecar `ml/lineage-extractor/serve/`（FastAPI + transformers，单端点 `POST /extract`，见 contracts）；部署两形态：GPU 常驻（开发机）或 CPU int8/GGUF 量化（生产可选，1.5B q8 单请求 ~1-3s 可接受，批量 push 场景异步无用户等待）。平台侧 `ModelExtractor implements ScriptLineageExtractor`（第三个抽取器）：WebClient 调 sidecar，超时 2s（可配 `lineage.model.timeout`），配置 `lineage.model.endpoint` 未设或探活失败 → `supports()` 返回 false 整体旁路；输出双重校验（JSON schema + **表名在脚本文本中可定位**，防幻觉，FR-012）后转 SCRIPT_MODEL/UNVERIFIED 边。冲突消解优先序扩展为 SQL > RULE > MODEL（ScriptLineageService 既有键消解逻辑加一级）。

**宪法原则 IV 偏差记录**：内核条文"服务端无 AI 大脑（不嵌入推理/决策/agent 逻辑）"。本设计：推理不嵌入四模块进程（独立 sidecar，平台仅 HTTP 客户端 + 降级）；该模型是**确定性抽取组件**（固定 schema 输出、温度 0、无对话/决策/工具面），与条文针对的"agent 大脑"（chat/IntentRouter/proactive）性质不同。用户 2026-07-03 明确指令将模型纳入本期，构成书面批准。已记入 plan Complexity Tracking；建议后续以修宪（原则 IV 措辞区分"对话式 agent 大脑"与"专用 ML 组件"）永久消解。

## 全部 NEEDS CLARIFICATION 状态

Technical Context 无遗留 NEEDS CLARIFICATION；spec Clarifications 4 项已回填（其中 3 项为用户暂离时采纳的推荐答案，实现前可复核）。
