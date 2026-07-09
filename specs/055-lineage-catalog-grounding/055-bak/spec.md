# Feature Specification: 血缘目录接地（Catalog Grounding）——用真实数据源目录裁决候选表真伪

**Feature Branch**: `055-lineage-catalog-grounding`

**Created**: 2026-07-08

**Status**: Draft

**Input**: User description: "目录接地(catalog grounding)——最大杠杆,借 053 新基建。机制:候选表拿去和真实数据源目录(DatasourceBoundCatalog)比对——在目录里的=真表(高置信自动采纳);不在的(CTE/temp/派生名/幻觉)直接丢。这把\"什么算血缘\"从猜约定变成查目录,一举干掉今天卡住的所有残余 FP(temp/view/CTE/系统表),且零过拟合。诚实caveat:这是生产运行时杠杆,不是 gold-A 可 benchmark 的——GitHub 脚本没有对应目录,所以今天 precision 0.68 有一部分正是\"无目录可消歧\"造成的;在 Weft 真实数据源场景下,部署 precision 会显著高于 gold-A 基准。这条同时改写了评测叙事。"

## 背景与动机 *(为什么做这件事)*

041-R/047/054 的实证反复撞到同一堵墙：血缘抽取的**精度天花板**不来自模型不够聪明，而来自**"什么算一张真表"这个判据本身是靠约定去猜的**。三条推断/抽取通道（本地小模型、云 AI Agent、脚本内嵌 SQL/规则）都会把下列非血缘对象误判成真表，构成残余假阳（FP）：

- **CTE / `WITH` 子句临时名**——只是查询内的局部视图，不是持久表；
- **临时表 / 派生子查询别名**——运行期临时物，无独立血缘身份；
- **模型幻觉表名**——脚本文本里根本不存在的表；
- **系统 / 元数据表**（`information_schema.*`、`pg_catalog.*`、`sys.*`）——库里确实有，但不是业务血缘节点。

054 的结论是：靠更多训练、更严启发式，AST 抽取器 precision 也只到 0.677——因为在 gold-A（GitHub 脚本）语料里**根本没有权威目录可以消歧**，"这个名字是不是真表"无从查证，只能猜。

**本特性把这个判据从"猜约定"换成"查目录"**：053 已经落地了 `DatasourceBoundCatalog`（缓存 → neo4j 列目录 → 数据源实时抓列的组合解析链），当前只用于**列展开**（`SELECT *`）。本特性把这套地基**复用成表级的真伪判据（grounding oracle）**：

- 候选表**在目录中存在** → 判定为真表，**高置信自动采纳**（并可免去人工复核）；
- 候选表在**可信目录中确定性缺席** → 判定为 CTE/临时/派生/幻觉，**直接丢弃并留痕**；
- 目录**无法判定**（任务未绑定数据源 / 目录不可达 / 超时） → **退回既有行为，不做任何惩罚**（宁缺毋滥，绝不误杀真表）。

这条杠杆**零过拟合**（不依赖任何训练分布，纯粹查真实元数据），且**改写评测叙事**：gold-A 的 precision 0.68 里，有相当一部分正是"无目录可消歧"造成的机制性损失；在 Weft 真实数据源部署场景下（每个任务绑定数据源、目录可达），接地后的部署 precision 会显著高于 gold-A 基准。本特性因此还需交付一份**带目录的评测夹具 + 诚实叙事**，把这份"部署精度 ≫ 基准精度"从口头 caveat 变成可量化、可复现的证据。

## Clarifications

### Session 2026-07-08

- Q: grounding 的"确定性缺席即剔除"作用于哪些抽取通道？ → A: **仅推断类通道剔除**——只对规则推断（`SCRIPT_INFERRED`）/ 本地小模型（`SCRIPT_MODEL`）/ 云 AI Agent（`SCRIPT_AGENT`）的候选执行 `ABSENT` 剔除；Calcite 成功解析的 SQL（独立 `SQL_PARSED` 与内嵌 `SCRIPT_SQL`）及 agent 声明只做 `PRESENT` 核验加"已核验"标，从不因 `ABSENT` 被剔除。理由：① Calcite 已把 CTE/`WITH`/派生子查询解析为非表节点，确定性输出本就干净，残余 FP 几乎全来自无真解析器的推断类通道；② 规避**跨数据源引用**误杀——Calcite 能正确解析一张来自另一个数据源的真表，但它不在本任务绑定数据源目录里，全通道剔除会把它误判 `ABSENT` 错杀。
- Q: grounding 何时生效？ → A: **绑定数据源即默认开启**——任务绑定数据源即自动接地，无需显式配置；纯精度收益、零额外外呼（piggyback 053 既有目录查询），三态 `UNKNOWN` 保护保证目录答不上来时零误杀；另设一个全局 kill-switch 供回退/排障，关闭时血缘抽取回到 grounding 前的既有行为。
- Q: grounding 处置留痕的持久化载体？ → A: **新增专用审计表 `lineage_grounding_disposition`**——被 `ABSENT` 剔除的候选无边可挂元数据，必须有独立可查询、可审计的去处；新表记录候选名/判定/原因/数据源/时间/来源通道，采纳（`PRESENT`）的边另在边元数据标 `catalog-verified`。语义干净（覆盖全通道，非仅 AI），需 schema 版本 bump（不复用 053 AI 专用的 `lineage_agent_call`，避免语义拉伸）。
- Q: grounding 目录查询（尤其 cache-miss 触发的真连库探测）的执行路径？ → A: **强制异步富化**——所有 grounding 目录查询（含 cache-miss 真连库探测）在异步富化路径完成，push 同步路径零 grounding 开销；`catalog-verified` 标与 `ABSENT` 剔除在 push 后短时异步回填。对齐 053 异步富化设计，代价是核验标非瞬时（push 后数百 ms~数 s 出现）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 目录接地裁决候选表真伪（Priority: P1）

数据平台上有一批任务，脚本/SQL 经抽取通道（小模型、AI Agent、内嵌 SQL/规则）产出一组候选读/写表，其中混有 CTE 临时名、派生子查询别名与个别幻觉表名。这些任务绑定了数据源。平台在把候选表写入血缘图谱**之前**，逐个候选表拿去和该任务绑定数据源的真实目录（复用 `DatasourceBoundCatalog`）比对：

- 目录里**存在**的表 → 标记为"目录已核验（catalog-verified）"，以最高置信自动采纳，无需人工复核；
- 目录里**确定性缺席**的候选（数据源可达且成功查询、表确不存在） → 判定为伪表，从血缘边中剔除并留下可查看的处置留痕（丢弃原因 + 原始候选名）；
- 目录**无法判定**（未绑定数据源 / 连接失败 / 超时 / 名称无法规范化） → 该候选**原样保留**，走既有通道的置信度与复核流程，绝不因"查不到"而丢真表。

最终入图谱的表血缘只剩"目录已核验"与"无法判定但保留"两类，CTE/临时/派生/幻觉这类"确定性缺席"的残余假阳被一举清除。

**Why this priority**: 这是用户诉求的主干，也是当前 precision 天花板的直接破法。它以 053 的 `DatasourceBoundCatalog` 组合链为地基，新增一个"表级存在性裁决 + 三态处置"的验证阶段即可，存储/展示层零改动，单独交付即构成可用增量——在任一绑定数据源的项目上立刻拿到"确定性缺席即剔除"的精度提升。

**Independent Test**: 准备一个绑定了数据源、库中真实存在 `dw.orders`、不存在 `tmp_stage` 的项目；push 一个脚本，其抽取候选包含真表 `dw.orders`、一个 CTE 名 `tmp_stage`、一个幻觉名 `ghost_tbl`；grounding 后血缘图谱中 `dw.orders` 带"目录已核验"标记并被采纳，`tmp_stage`/`ghost_tbl` 被剔除且留下丢弃留痕；把数据源解绑后重跑同一脚本，三个候选全部因"无法判定"原样保留（回到既有行为）——即为通过。

**Acceptance Scenarios**:

1. **Given** 任务绑定的数据源目录中存在候选表 `dw.orders`，**When** 该候选进入 grounding 阶段，**Then** 它被标记"目录已核验"、以最高置信自动采纳、且不进入人工复核队列。
2. **Given** 候选名 `tmp_stage` 在可达数据源目录中确认不存在（成功连库查询后确属缺席），**When** grounding，**Then** 该候选被从血缘边中剔除，并生成一条含"丢弃原因=目录确定性缺席 + 原始候选名"的处置留痕。
3. **Given** 任务未绑定数据源（目录无法判定），**When** grounding，**Then** 全部候选原样保留、置信度不变、既有通道结果与 push/上线流程完全不受影响。
4. **Given** 数据源已绑定但连接超时 / 鉴权失败 / 名称无法规范化（无法判定），**When** grounding，**Then** 相关候选原样保留（判为"无法判定"而非"缺席"），grounding 绝不抛异常、绝不误杀。
5. **Given** 一个真实存在但列元数据尚未登记的表 `dw.orders`，**When** grounding，**Then** 其存在性经数据源实时探测确认后被采纳（存在性核验不要求列元数据已预登记）。

---

### User Story 2 - 排除系统 / 元数据 schema（Priority: P2）

候选表里出现 `information_schema.columns`、`pg_catalog.pg_class`、`sys.tables` 这类系统 / 元数据对象——它们在目录中**确实存在**（US1 的存在性判据会误采纳），但不是业务血缘节点。平台在存在性核验之上叠加一层**系统 / 元数据 schema 排除**：落在已知系统命名空间（按数据库引擎划定，如 `information_schema`、`pg_catalog`、`sys`、`mysql`、`performance_schema` 等）的候选，即使目录中存在也一律不作为血缘节点，并留下"系统对象排除"留痕。

**Why this priority**: 系统表是 US1 存在性判据的已知盲区（存在 ≠ 业务表），必须专门排除才能真正清掉用户点名的"系统表"这一类 FP。它独立于 US1 的"缺席即剔除"逻辑，价值可单独验证；但优先级次于主干，因为系统表在真实业务脚本中占比小于 CTE/临时/幻觉。

**Independent Test**: push 一个抽取候选包含 `information_schema.columns`（引擎侧真实存在）与业务表 `dw.orders` 的脚本；grounding 后 `dw.orders` 被采纳、`information_schema.columns` 被排除且留"系统对象排除"痕——即为通过。

**Acceptance Scenarios**:

1. **Given** 候选 `information_schema.columns` 落在系统命名空间且目录中存在，**When** grounding，**Then** 它被排除、不入图谱、留"系统对象排除"处置痕。
2. **Given** 候选 `dw.orders` 是普通业务 schema 下的真表，**When** grounding，**Then** 系统排除层不误伤，正常按 US1 采纳。
3. **Given** 数据源引擎类型已知（如 PostgreSQL / MySQL），**When** 判定系统命名空间，**Then** 采用与该引擎匹配的系统 schema 集合（引擎无关的通用集合 + 引擎特定补充），不同引擎的系统对象都能被识别。

---

### User Story 3 - 改写评测叙事：带目录的接地精度夹具（Priority: P3）

血缘方向的负结果论文（041-R/047/054）此前只能在无目录的 gold-A 上报 precision，并口头附带"真实部署会更高"的 caveat。本特性交付一份**带目录的评测夹具**：一个已知全表集合的合成/受控数据源，配一批候选表混入 CTE/临时/幻觉/系统表；在该夹具上量化 grounding 前后的 precision 提升，从而把"部署精度 ≫ gold-A 基准"从口头断言变成**可复现、可量化的证据**，并诚实标注该提升不可迁移到无目录的 gold-A GitHub 语料（GitHub 脚本无对应目录，接地在那里不适用）。

**Why this priority**: 这是把 grounding 的价值主张写成可信证据的收尾，改写整条血缘研究线的评测叙事。它不改变运行时行为，纯属评测/文档交付，可在 US1/US2 之后独立完成；价值真实但不阻塞功能上线，故列 P3。

**Independent Test**: 在带目录夹具上跑一次评测，输出 grounding 关闭 vs 开启两组 precision/recall，且报告显式声明"此提升依赖目录可达、不适用于无目录的 gold-A"——数字可复现（同夹具同种子两次一致）即为通过。

**Acceptance Scenarios**:

1. **Given** 带已知全表集合的评测夹具与一批含 CTE/临时/幻觉/系统表的候选，**When** 关闭 grounding 跑评测，**Then** 报告一个含这些 FP 的基线 precision。
2. **Given** 同一夹具与候选，**When** 开启 grounding 跑评测，**Then** precision 显著提升（FP 被剔除），且 recall 不因误杀真表而下降（真表全部保留）。
3. **Given** 评测报告，**When** 阅读其结论段，**Then** 明确标注该 precision 提升依赖目录可达、不可迁移到无目录的 gold-A GitHub 语料。

---

### Edge Cases

- **真表存在但目录暂不可达**（数据源短时故障）：判为"无法判定"→ 原样保留，绝不误判为缺席而剔除。目录恢复后的重 push 会重新核验（复用 053 的重 push 失效 + TTL 兜底新鲜度策略）。
- **裸表名 vs 限定名歧义**：候选是裸名 `orders`，需按数据源默认 schema/catalog 规范化后再比对（复用 053 FR-015 的规范化）。规范化失败 → 无法判定 → 保留。
- **同名跨 schema**：`orders` 在 `dw` 与 `staging` 各有一张；规范化到默认 schema 后比对，不做跨 schema 模糊匹配（避免把候选错绑到另一个 schema 的同名表）。
- **视图**：视图在目录元数据中作为表存在 → 视为真血缘节点采纳（视图是合法血缘对象，不在剔除之列）。
- **写侧目标表尚不存在**（首次 `CREATE TABLE AS` / `INSERT` 建目标）：目标表可能在 push 时目录中还不存在 → 判"无法判定"保留，不因"缺席"剔除写侧新表。
- **确定性通道（Calcite 已成功解析）的候选**：只做 `PRESENT` 核验加"已核验"标，从不因 `ABSENT` 被剔除（FR-011）——保护跨数据源真表边不被误杀。
- **跨数据源引用**：任务读一张位于另一个数据源的真表，本任务绑定数据源目录中查为缺席；若该候选来自 Calcite 确定性解析则不剔除（FR-011）；若来自推断类通道则判 `ABSENT` 剔除（推断类通道产出跨源真表的可信度低，宁缺毋滥）。
- **大量候选的目录查询开销**：grounding 逐表查目录，须复用 053 的进程内 TTL 缓存 + neo4j 层，避免每次 push 对每个候选都真连库。
- **grounding 自身故障**（缓存/neo4j/数据源全线异常）：整个 grounding 阶段降级为"全部无法判定"→ 原样保留，绝不阻断 push/上线主链路。
- **核验标非瞬时**：grounding 全程异步，`catalog-verified` 标注与 `ABSENT` 剔除在 push 之后短时才落地——push 刚返回的瞬间图谱上尚无核验标属预期，非缺陷。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 在候选表血缘边写入图谱之前，增设一个"目录接地（grounding）"验证阶段，逐个候选表（读侧 + 写侧）向任务绑定数据源的目录查询其存在性。
- **FR-002**: grounding MUST 复用 053 的 `DatasourceBoundCatalog` 组合解析链（进程内 TTL 缓存 → neo4j 列目录 → 数据源实时抓取）作为目录来源，不新建独立目录存储。
- **FR-003**: grounding 的存在性判定 MUST 为**三态**：`PRESENT`（目录中存在）/ `ABSENT`（可达目录成功查询后确认缺席）/ `UNKNOWN`（未绑定数据源、连接失败、超时、名称无法规范化等无法查证的情形）。系统 MUST 严格区分 `ABSENT` 与 `UNKNOWN`，绝不把"查不到"当作"确认缺席"。
- **FR-004**: 对 `PRESENT` 的候选，系统 MUST 标记为"目录已核验（catalog-verified）"、以最高置信采纳、且不进入人工复核队列。
- **FR-005**: 对 `ABSENT` 的候选，系统 MUST 将其从血缘边中剔除，并向专用审计表 `lineage_grounding_disposition` 落一条含"丢弃原因（目录确定性缺席）+ 原始候选名 + 数据源标识 + 来源通道 + 时间"的可查询处置记录（被剔除候选无边可挂元数据，故必须独立落库）。
- **FR-006**: 对 `UNKNOWN` 的候选，系统 MUST 原样保留，置信度与既有通道处置（复核/冲突消解）完全不变，绝不因无法判定而剔除或降级。
- **FR-007**: grounding MUST 绝不抛异常打断主链路：任一层（缓存/neo4j/数据源/规范化）失败一律降级为该候选 `UNKNOWN`；整阶段故障降级为"全部候选 `UNKNOWN`"。
- **FR-008**: grounding 的全部目录查询（含 cache-miss 触发的真连库探测）MUST 在**异步富化路径**完成，push 同步路径承担零 grounding 开销；`catalog-verified` 标注与 `ABSENT` 剔除在 push 之后短时异步回填入图谱，push 返回时延与无 grounding 基线完全一致。
- **FR-009**: 候选名比对前 MUST 按数据源默认 schema/catalog 规范化（复用 053 FR-015），并只做规范化后的精确匹配，不做跨 schema 模糊匹配。
- **FR-010**: 系统 MUST 在存在性核验之上叠加**系统 / 元数据 schema 排除**：落在已知系统命名空间的候选即使 `PRESENT` 也一律排除、不作为血缘节点，并留"系统对象排除"处置痕；系统命名空间集合 MUST 按数据源引擎类型确定（通用集合 + 引擎特定补充）。
- **FR-011**: 所有通道产出的候选都参与接地并接受 `PRESENT` 核验（加"已核验"标）；但 `ABSENT` / `SYSTEM_EXCLUDED` 的**剔除动作** MUST 只作用于**推断类通道**——即**规则推断（`SCRIPT_INFERRED`）/ 本地小模型（`SCRIPT_MODEL`）/ 云 AI Agent（`SCRIPT_AGENT`）**。**确定性/声明来源**——Calcite 解析成功的独立 SQL（`SQL_PARSED`）与脚本内嵌 SQL（`SCRIPT_SQL`）、agent 显式声明（`AGENT`）、表单（`FORM`）、以及无 source 的既有列级路径（`null`）—— MUST **从不因 `ABSENT`/系统命中被剔除**，命中时只留痕（保护 Calcite 正确解析的跨数据源真表边不被误杀）。
  > 注：区分判据是"是否 Calcite 成功解析"，而非"是否在脚本内"。脚本内嵌 SQL 一旦成功解析即 `SCRIPT_SQL`（受保护）；解析失败退化到规则/模型/AI 通道时才属可剔除的推断类。
- **FR-012**: grounding 的核验结果 MUST 与 053 既有冲突消解协同：目录已核验的边在同语义键冲突时优先于未核验的同键边（catalog-verified 作为消解的最高可信信号）。
- **FR-013**: grounding MUST 复用 053 的新鲜度策略保证目录变更（新建表 / 删表）在下次 push 反映到核验结论——具体：① grounding 前对候选表（reads+writes）逐表 `DatasourceBoundCatalog.evict(...)` 再 probe（重 push 失效，刷新 `PRESENT` 侧陈旧缓存）；② `ABSENT`/`UNKNOWN` 三态不缓存（新建表能翻转为 `PRESENT`）；③ 底层列目录缓存 TTL 兜底。
- **FR-014**: grounding MUST 在任务**绑定数据源即默认开启**（无需显式配置）；系统 MUST 另提供一个全局 kill-switch 供回退/排障，关闭时血缘抽取回到 grounding 前的既有行为。
- **FR-015**: 系统 MUST 交付一份带目录的评测夹具与叙事文档：在已知全表集合的受控数据源上量化 grounding 前后的 precision/recall，且显式标注该提升依赖目录可达、不可迁移到无目录的 gold-A GitHub 语料。
- **FR-016**: grounding 的每一次候选处置（采纳 / 缺席剔除 / 系统排除 / 无法判定保留）MUST 可观测——`ABSENT` 剔除与系统排除落 `lineage_grounding_disposition` 审计表、`PRESENT` 采纳边带 `catalog-verified` 元数据，使人工能查询审计"某条血缘边为何被保留或剔除"。

### Key Entities *(include if feature involves data)*

- **候选表（Table Candidate）**：抽取通道产出的一个待核验读/写表引用，含原始候选名、来源通道、所属任务与数据源绑定；grounding 的输入单元。
- **接地判定（Grounding Verdict）**：对一个候选表的三态裁决结果 `PRESENT` / `ABSENT` / `UNKNOWN`，附带判定依据（缓存命中 / neo4j 命中 / 实时探测 / 未绑定 / 失败原因）与是否命中系统排除。
- **处置留痕（Grounding Disposition Record）**：专用审计表 `lineage_grounding_disposition` 中的一条记录，含原始候选名、判定（`PRESENT`/`ABSENT`/`UNKNOWN`/系统排除）、原因、数据源标识、来源通道、时间；`ABSENT` 剔除与系统排除必落此表（无边可挂）。`PRESENT` 采纳的边另在图谱边元数据标 `catalog-verified`。
- **系统命名空间集合（System Namespace Set）**：按数据库引擎划定的、需从血缘节点中排除的系统 / 元数据 schema 名集合。
- **评测夹具（Grounded Eval Fixture）**：已知全表集合的受控数据源 + 混入 FP 的候选集合，用于量化并复现接地精度提升。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 在带目录的评测夹具上，开启 grounding 后候选表 precision 相较关闭时**显著提升**（CTE/临时/派生/幻觉类 `ABSENT` 候选被 100% 剔除），且真表召回率不下降（`PRESENT` 真表 100% 保留）。
- **SC-002**: 系统 / 元数据 schema 候选（如 `information_schema.*`、`pg_catalog.*`）在开启 grounding 后被 100% 排除、不入血缘图谱。
- **SC-003**: 在目录**无法判定**的场景（未绑定数据源 / 目录不可达），grounding 开启与关闭的血缘产物**完全一致**——即绝无因无法判定而误杀真表（误杀率 = 0）。
- **SC-004**: grounding 阶段的任何故障（缓存/neo4j/数据源异常）下，push / 上线主链路成功率不受影响（0 例因 grounding 抛异常导致的 push 失败），且相关候选一律降级为保留。
- **SC-005**: grounding 全程在异步富化路径执行，push 同步返回时延相对无 grounding 基线**完全一致**（增幅 = 0，交互 push 不承担任何目录查询开销）；`catalog-verified` 标注 / `ABSENT` 剔除在 push 之后短时（数百 ms~数 s）异步落地。
- **SC-006**: 每一条被剔除 / 排除 / 采纳 / 保留的候选都有可查看的处置留痕，人工可 100% 追溯任一条血缘边的 grounding 处置原因。
- **SC-007**: 评测夹具的接地精度提升数字可复现（同夹具同种子两次运行结论一致），且报告显式声明该提升不可迁移到无目录 gold-A。

## Assumptions

- **复用 053 地基**：`DatasourceBoundCatalog` / `DatasourceSchemaResolver` / neo4j 列目录 / 新鲜度策略均已就绪且可用；本特性在其上新增"表级存在性裁决 + 三态处置 + 系统排除"，不重建目录存储。存在性核验可用比列抓取更轻的元数据查询（表存在性 vs 全列清单），但复用现有 `lookupTable` 的"命中即存在"语义亦可接受。
- **新增审计表需 schema 版本 bump**：`lineage_grounding_disposition` 是新表，按项目约定（`schema.sql` 单一权威 DDL + `schema_version` 严格 SemVer）任何表变更须 bump 版本，DB 行 / 文件头 / 项目版本三者保持一致；不复用 053 AI 专用的 `lineage_agent_call`。
- **缺席即剔除（默认动作）**：对 `ABSENT` 候选采用"直接丢弃 + 留痕"（符合既有"宁缺毋滥"原则与用户明确诉求），而非"降级到复核队列"。若后续需要更保守，可改为 quarantine——但 v1 默认丢弃。
- **系统 schema 集合内置 + 可覆盖**：内置一套常见引擎（PostgreSQL / MySQL / 通用）的系统命名空间集合作为默认，允许配置覆盖/补充；`data.sql` 种子数据命名豁免不受影响。
- **任务已绑定数据源是接地生效的前提**：未绑定数据源的任务，grounding 一律 `UNKNOWN`、行为与今天完全一致——本特性对无数据源场景零影响。绑定数据源即默认开启（FR-014），另有全局 kill-switch 供回退。
- **确定性通道免剔除**：Calcite 确定性解析边不受 `ABSENT` 剔除，仅享受 `PRESENT` 加"已核验"标（FR-011），以规避跨数据源真表误杀。
- **gold-A 不变**：不改动无目录的 GitHub gold-A 基准；grounding 的精度提升只在带目录夹具与真实部署场景成立，评测叙事对此诚实设界。
- **视图视为真表**：目录元数据中作为表存在的视图属合法血缘节点，不在 `ABSENT`/系统排除之列。
- **前端零改动（v1）**：处置留痕作为血缘边的来源/置信元数据留存与可查看即可，是否新增独立的 grounding 审计 UI 面板不在 v1 范围（沿用 053 的来源标记展示）。
