# Feature Specification: 大数据开发任务类型补全（MVP）

**Feature Branch**: `059-bigdata-task-types`

**Created**: 2026-07-09

**Status**: Draft

**Input**: User description: "设置一个总的MVP，你外部有3个Agent帮你干活，接下来补全任务类型，当前仅有SHELL，任务，接下来，补全 大数据开发领域的任务，Spark，SHELL，Python，hive,各种数据类型(starrocks dorisdb) cklickhouse，Flink datax seatunnel 等等，你补全"

## 背景与目标 *(说明性，非需求)*

Weft 是「Tasks-as-Code」数据开发平台。平台已具备 `SHELL / SQL / SPARK / PYTHON` 执行能力，但**创建任务的界面只暴露了 `SQL` 与 `SHELL`**，导致数据开发者感知上「只有 SHELL/SQL」，无法在平台内覆盖真实大数据开发日常。

本 MVP 的目标：把平台的**任务类型目录补齐到覆盖主流大数据开发场景**，使数据开发者无需离开平台即可编写并运行分析、计算、流式与数据集成任务。这是一个「统领性 MVP」，其内容按引擎家族天然切分为可并行、可独立交付的工作流（供 3 个外部执行 Agent 并行推进）。

**范围决策（已与需求方确认）**：

- **全量补齐**——本轮一次性覆盖：OLAP 数据仓（StarRocks / Doris / ClickHouse）、Hive、Spark、Python、Flink、DataX、SeaTunnel，并把已有但未暴露的 `SPARK` / `PYTHON` 在创建入口暴露出来。
- **混合接入**——OLAP 数据仓（StarRocks / Doris / ClickHouse）以「新数据源类型」形式接入，任务仍是 `SQL` 类型绑定对应数据源，复用现有 SQL 执行器与驱动隔离；**Hive 因 HQL / 分区语义差异，走独立执行器**（不复用 SQL 执行器）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 面向数据仓库的 SQL 开发（OLAP + Hive）(Priority: P1)

数据开发者要针对企业数据仓库（StarRocks、Doris、ClickHouse）与 Hive 数仓编写并运行分析型 SQL / HQL。今天平台只能连通用 JDBC 数据源；开发者希望在平台内选择目标数仓类型、编写 SQL、试跑看到逐行执行日志与影响行数，并把脚本纳入调度。

**Why this priority**: 这是「大数据开发」使用频率最高、离用户价值最近的一类任务，且大部分能力可复用现有 SQL 执行器 + 驱动隔离，交付路径最短、风险最低，单独交付即构成可用 MVP。

**Independent Test**: 在具备（或以隔离驱动模拟）StarRocks/Doris/ClickHouse/Hive 的环境中，创建一个绑定该数据源的 SQL/HQL 任务，试跑并看到「连接 → 逐条语句执行 → 影响/返回行数 → 完成」日志；在缺失该引擎的环境中试跑得到可辨识的「已跳过」结果且不阻塞下游。

**Acceptance Scenarios**:

1. **Given** 已登记一个 StarRocks 数据源，**When** 开发者创建 SQL 任务绑定该数据源并试跑，**Then** 看到真实连库执行的逐行日志与每条语句影响行数摘要，任务成功。
2. **Given** 已登记一个 ClickHouse / Doris 数据源，**When** 开发者试跑对应 SQL 任务，**Then** 执行行为、日志契约与 StarRocks 一致（同一 SQL 执行路径）。
3. **Given** 一个 Hive 数仓，**When** 开发者创建 HIVE 任务编写 HQL（含分区写入）并试跑，**Then** 通过 Hive 专用执行器运行，逐行透出执行日志，退出码忠实透传。
4. **Given** 运行环境缺失目标引擎（无驱动 / 无 Hive 连接），**When** 试跑，**Then** 返回「已跳过：<原因>」，不伪装成功、不阻塞下游节点。

---

### User Story 2 - 数据集成/同步任务（DataX + SeaTunnel）(Priority: P2)

数据开发者要在数据源之间搬运/同步数据（如 MySQL → StarRocks、Hive → ClickHouse）。开发者希望在平台内编写 DataX 作业（JSON job）或 SeaTunnel 配置，选择对应任务类型，试跑并观察同步进度日志，并纳入调度。

**Why this priority**: 数据集成是数据开发第二高频场景，是打通异构数据源的关键；独立于 SQL 与计算引擎，可单独交付一条完整价值切片。

**Independent Test**: 创建一个 DataX 任务（或 SeaTunnel 任务），编写最小 source→sink 作业，试跑并看到引擎逐行日志；在缺失引擎（无 DataX/SeaTunnel 可执行程序）的环境中试跑得到「已跳过」而非失败。

**Acceptance Scenarios**:

1. **Given** 环境具备 DataX，**When** 开发者创建 DATAX 任务、粘贴一份 job JSON 并试跑，**Then** 平台以子进程提交 DataX，逐行透出同步日志，退出码忠实透传（成功/失败可辨识）。
2. **Given** 环境具备 SeaTunnel，**When** 开发者创建 SEATUNNEL 任务、编写同步配置并试跑，**Then** 平台以子进程提交 SeaTunnel，逐行透出日志。
3. **Given** 环境缺失 DataX/SeaTunnel 可执行程序，**When** 试跑，**Then** 返回「已跳过：<原因>」，不阻塞下游。

---

### User Story 3 - 分布式计算与流式任务，且创建入口全类型可选（Spark / Python / Flink + 统一暴露）(Priority: P2)

数据开发者要编写分布式批处理（Spark：pyspark / spark-sql / jar）、脚本化处理（Python）与流式作业（Flink：SQL / jar），并且能在**创建任务界面直接选择所有支持的任务类型**（今天只能选 SQL/SHELL），编辑器按类型给出正确的语言高亮与参数。

**Why this priority**: Spark/Python 执行能力已存在但未在入口暴露，暴露成本低、价值立现；Flink 补齐流式空白。三者共同构成「计算+流式」价值切片，并统一收口创建入口，使整个任务目录对用户可见、可选。

**Independent Test**: 在创建任务界面能看到并选择全部支持的任务类型；创建一个 Flink 任务试跑（有引擎→真跑、无引擎→跳过）；创建 Spark/Python 任务从入口直达并试跑，行为与既有执行器一致。

**Acceptance Scenarios**:

1. **Given** 开发者打开创建任务入口，**When** 展开任务类型选择，**Then** 可见并可选平台当前支持的全部类型（至少含 SQL、SHELL、PYTHON、SPARK、HIVE、FLINK、DATAX、SEATUNNEL），每种类型编辑器语言高亮正确。
2. **Given** 环境具备 Flink，**When** 开发者创建 FLINK 任务（SQL 或 jar 形态）并试跑，**Then** 平台以子进程提交 Flink，逐行透出日志，退出码忠实透传。
3. **Given** 开发者从入口创建 SPARK / PYTHON 任务，**When** 试跑，**Then** 复用既有执行器行为（三形态 Spark / Python 子进程），本地 `dw run` 与服务端运行一致。
4. **Given** 环境缺失 Flink，**When** 试跑 FLINK 任务，**Then** 返回「已跳过：<原因>」，不阻塞下游。

---

### Edge Cases

- **引擎缺失 vs 作业失败必须可区分**：环境缺失（无引擎/无驱动/无可执行程序）→ 「已跳过」（非失败完成，不阻塞下游）；作业自身错误 / 资产缺失（如 jar / job 文件指定但不存在）→ 真实失败并忠实透传退出码。二者不得混淆，不新增状态机状态。
- **超时**：任一任务类型超时→强制终止子进程、标记超时失败，日志保留已捕获行。
- **本地/服务端漂移**：同一任务经本地 `dw run` 与服务端调度运行，行为与结果语义一致（保真原则）。
- **未知任务类型**：调度到无对应执行器的类型→可辨识失败（列出可用类型），不静默吞掉。
- **OLAP 方言差异**：StarRocks/Doris（MySQL 协议）与 ClickHouse（HTTP/原生协议）JDBC 驱动不同，需各自可用的驱动/连接方式；语句分隔与影响行数汇报沿用现有 SQL 执行器语义。
- **Hive 分区/多语句 HQL**：Hive 执行器需正确处理 HQL 多语句与分区写入语义，不套用通用 JDBC SQL 的朴素分号切分假设。
- **日志量大**：长时运行任务（Spark/Flink/DataX）输出海量日志→按现有截断上限保护，标记 truncated。
- **凭据安全**：数据源账号/密码、集成作业内含凭据不得明文落入可导出定义或日志。

## Requirements *(mandatory)*

### Functional Requirements

**任务类型目录（新增与暴露）**

- **FR-001**: 平台 MUST 支持以下任务类型运行：`SQL`（含面向 OLAP 数仓）、`SHELL`、`PYTHON`、`SPARK`、`HIVE`、`FLINK`、`DATAX`、`SEATUNNEL`。
- **FR-002**: 平台 MUST 支持将 StarRocks、Doris、ClickHouse 作为**新的数据源类型**登记；`SQL` 任务绑定该数据源后经现有 SQL 执行路径运行（复用驱动隔离与连接语义）。
- **FR-003**: 平台 MUST 提供 `HIVE` 独立任务类型/执行器，处理 HQL（含多语句与分区写入语义），不复用通用 JDBC SQL 的语句切分假设。
- **FR-004**: 平台 MUST 提供 `DATAX` 任务类型：以 DataX 作业（JSON）为内容，经子进程提交运行。
- **FR-005**: 平台 MUST 提供 `SEATUNNEL` 任务类型：以 SeaTunnel 配置为内容，经子进程提交运行。
- **FR-006**: 平台 MUST 提供 `FLINK` 任务类型：支持 SQL 形态与 jar 形态，经子进程提交运行。
- **FR-007**: 创建任务界面 MUST 暴露平台当前支持的全部任务类型供用户选择（不再仅限 SQL/SHELL），并为每种类型提供正确的编辑器语言高亮。

**执行语义（保真与三态）**

- **FR-008**: 每种新任务类型的执行 MUST 遵循「三态」结果语义：成功 / 失败（作业自身错误、资产缺失、真实超时）/ 已跳过（运行环境缺失该引擎或依赖）。已跳过与成功互斥，且不得伪装成功。
- **FR-009**: 运行环境缺失某引擎/驱动/可执行程序时，对应任务 MUST 返回可辨识的「已跳过：<原因>」，且 MUST NOT 阻塞下游节点、MUST NOT 新增状态机状态。
- **FR-010**: 作业真实失败时，系统 MUST 忠实透传退出码，并将失败与「已跳过」清晰区分。
- **FR-011**: 每种任务类型执行 MUST 逐行透出运行日志到平台的实时日志管道（试跑与调度运行一致可见），并遵循统一日志规范：起始 banner（运行模式/类型/数据源/时间）→ 执行过程逐行日志 → 收尾 banner（状态/退出码/耗时/时间）；主管/运维**仅凭日志即可定位问题**，任何执行分支都不得静默无日志。
- **FR-011a**: 涉及 SQL/HQL 的任务类型（OLAP SQL、Hive）执行诊断/查询类语句（如 `SHOW TABLES`、`DESCRIBE`、`SELECT`）时，MUST 将**返回结果集渲染进运行日志**（表头 + 数据行，带行数上限截断并标注「已截断」），而非仅汇报「有返回」；DML/DDL 语句 MUST 输出影响行数与每条语句耗时。此为对现状「本期不打印结果集」的显式修订。
- **FR-011b**: 引擎子进程类型（Spark、Flink、DataX、SeaTunnel、Python、Shell）MUST 将引擎/进程的原生 stdout/stderr 逐行忠实透出到日志管道（不吞、不改写语义），保留引擎自身的进度/错误行，供定位问题。
- **FR-012**: 同一任务定义经本地 `dw run` 与服务端调度运行 MUST 产生一致的执行语义与结果分类（保真：本地↔服务端零改动漂移）。
- **FR-013**: 任一任务类型 MUST 支持超时终止：超时强杀子进程、标记超时失败、保留已捕获日志。
- **FR-013a**: 系统 MUST 支持大体量任务内容（DataX job JSON / SeaTunnel 配置 / Flink 多语句 SQL 常见可达数 KB～数十 KB），任务内容持久化不得因长度上限被静默截断。

**集成与治理**

- **FR-014**: 新任务类型的运行 MUST 沿用现有按类型分发机制（新增即注册，无旁路），未知类型可辨识失败并列出可用类型。
- **FR-015**: 新增任务类型/数据源类型的写入与运行 MUST 沿用既有 push 发布闸门与审计轨迹（无新增旁路）。
- **FR-016**: 涉及 SQL/HQL 的任务类型（OLAP SQL、Hive）SHOULD 能被现有血缘抽取识别（表/列级血缘），与现有 SQL 任务一致；无法解析的方言以最小降级（不产错血缘）。
- **FR-017**: 数据源凭据与作业内嵌凭据 MUST NOT 明文出现在可导出的任务定义文件或运行日志中。

**质量**

- **FR-018**: 每种新增执行器 MUST 具备单元测试，至少覆盖：命令/提交参数构造的纯函数正确性、「已跳过」判定路径、失败退出码透传。
- **FR-019**: 每种新增任务类型 MUST 在缺引擎环境（如 H2 / CI 无外部依赖）下可跑通「已跳过」闭环，保住零外部依赖即跑底线。

### Key Entities *(include if feature involves data)*

- **任务类型（Task Type）**：标识一类可执行任务的枚举代号（如 `SQL`/`HIVE`/`FLINK`/`DATAX`/`SEATUNNEL`），与其执行器一一对应；决定编辑器语言、所需绑定资源与提交方式。
- **数据源类型（Data Source Type）**：可登记的外部数据存储种类；本轮新增 StarRocks、Doris、ClickHouse；承载连接信息与驱动/隔离配置，被 SQL 任务绑定。
- **执行结果（Execution Result）**：三态结果（成功/失败/已跳过）+ 退出码 + 日志（含 banner、逐行执行日志、SQL 结果集渲染、引擎原生 stdout/stderr）+ 截断/超时标记；跳过态携带可辨识原因。
- **集成作业定义（Integration Job）**：DataX 的 JSON job 与 SeaTunnel 的配置，描述 source→sink 同步；作为任务内容持久化。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 数据开发者可在平台内、不离开平台，端到端创建并运行以下每一类任务：面向 StarRocks/Doris/ClickHouse 的 SQL、Hive HQL、Spark、Python、Flink、DataX、SeaTunnel（共 7 类引擎家族全部可用）。
- **SC-002**: 创建任务界面可选任务类型从当前 2 种（SQL/SHELL）提升到覆盖全部支持类型（≥8 种），且每种类型编辑器语言高亮正确。
- **SC-003**: 在缺失对应引擎的环境中，100% 的新任务类型试跑得到可辨识的「已跳过」结果，且不阻塞下游节点、不导致调度中断。
- **SC-004**: 同一任务定义在本地 `dw run` 与服务端运行下，结果分类（成功/失败/跳过）一致率 100%。
- **SC-005**: 每种新增任务类型均具备可在无外部依赖环境（CI）绿灯的自动化测试，覆盖命令构造、跳过判定与退出码透传。
- **SC-006**: 本 MVP 的实现工作可拆为 3 条互不阻塞的并行工作流交付，各自可独立测试与演示（详见 Assumptions 的工作流切分）。
- **SC-007**: 运维/主管仅凭任务运行日志即可定位问题：任一任务类型运行后，日志含起止 banner + 执行过程；SQL/HQL 查询类语句（如 `SHOW TABLES`）能在日志中看到结果集内容；引擎类（Spark/Flink/DataX/SeaTunnel）能看到引擎原生日志行——无「任务跑了但日志空白/只有摘要」的情形。

## Assumptions

- **执行器扩展模式沿用既有约定**：新增任务类型通过在 worker 基础设施层新增执行器组件（`type()` + 现有按类型分发映射）实现，为**纯新增**，不改动调度状态机；以既有 `SparkTaskExecutor`（子进程 → 逐行回调 → 缺环境跳过 → 退出码透传）为范式。
- **混合接入**：StarRocks/Doris/ClickHouse 作为数据源类型复用 SQL 执行器与驱动隔离；Hive 走独立执行器。（已确认）
- **Hive 执行器实现载体 = HiveServer2 JDBC（非 beeline 二进制）**：保留 `HIVE` 为独立任务类型/执行器以隔离 HQL 语义，但内部经 HiveServer2 JDBC 建连（复用已就绪的 `HIVE` 数据源类型 + 驱动隔离），避免 beeline 二进制依赖、改善 SKIPPED 保真与 CI 友好。为对澄清措辞「beeline」的有记录偏离（宪法允许），理由详见 research.md D2。
- **任务内容列上限需评估（G1）**：现 `task_def.content`/`task_def_version.content` 为 `VARCHAR(4000)`；若 DataX/SeaTunnel/Flink 真实作业体普遍超限，则扩列（`VARCHAR(1048576)`/`TEXT`，H2+PG 兼容）+ bump `schema_version`——见 plan Storage 行与 tasks T002a。
- **驱动/引擎程序由运行环境提供**：JDBC 驱动经现有驱动隔离上传机制提供；Spark/Flink/DataX/SeaTunnel 可执行程序由环境（`SPARK_HOME` 等约定路径 / PATH）提供，缺失即触发「已跳过」，平台不负责安装引擎。
- **本地运行时复用**：`dw run` 与 `localrun` 本地运行时对新类型的支持与服务端共享同一执行器（保真原则），本轮同步覆盖。
- **凭据管理沿用现有数据源密钥与隔离机制**，本轮不新建独立密钥体系。
- **不在本轮范围**：新引擎的可视化拖拽式作业构建器、引擎自动安装/版本管理、跨引擎统一 SQL 方言层、实时流式作业的运维监控看板（这些留待后续 feature）。
- **3 Agent 并行工作流切分（供 plan/tasks 阶段落地，非最终约束）**：
  - **工作流 A**：OLAP 数据源类型（StarRocks/Doris/ClickHouse）+ Hive 独立执行器（US1）。
  - **工作流 B**：数据集成执行器 DataX + SeaTunnel（US2）。
  - **工作流 C**：Flink 执行器 + 创建入口全类型暴露与编辑器语言映射（US3）。
  - 每条工作流应各自使用独立 git worktree 隔离，共享面（任务类型枚举、创建入口、schema、i18n key）在合并前对齐，避免互相覆盖（遵循仓库多 Agent 协作硬规则）。
