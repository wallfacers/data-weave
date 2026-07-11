# Feature Specification: 大数据任务类型真实引擎验证（Docker 环境实跑证明）

**Feature Branch**: `061-task-type-verification`

**Created**: 2026-07-10

**Status**: Draft

**Input**: User description: "前几个 spec 新增了很多任务类型，但这些任务类型单单是新增了，实际上是没跑过的。接下来要制定真实的验证环境，当前有 docker，比如测试 hive，那就必须安装，要真的、跑起来，才能证明没啥问题。外部有两个 agent 可以帮你干活，可以分摊给他们，环境优先 docker 安装。"

## 背景与目标 *(说明性，非需求)*

059（大数据任务类型补全）向平台新增/暴露了 7 类引擎家族的执行器：面向 OLAP 数仓的 `SQL`（StarRocks / Doris / ClickHouse）、`HIVE`、`SPARK`、`PYTHON`、`FLINK`、`DATAX`、`SEATUNNEL`。这些执行器目前的**证据只覆盖了两条路径**：① 命令/提交参数构造的纯函数单测；② 「缺引擎 → 已跳过（SKIPPED）」路径。**真实成功路径（SUCCESS）——连上真引擎、真提交作业、真透出日志与结果、真透传退出码——从未被跑通证明过。** 换句话说：单测全绿只证明了「没有引擎时会优雅跳过」，没有证明「有引擎时真能干活」。

本特性的目标：为**每一类引擎家族建立一套可复现的真实运行环境（优先 Docker 安装真实引擎）**，并对每类任务类型执行端到端真跑，产出可核验的证据（真实日志、结果集、退出码、影响行数），证明 059 的执行器在真实引擎下确实可用，而非仅在「缺引擎跳过」时表现正确。这是一个**验证/加固型统领特性**，按引擎家族天然可切分为可并行、可独立交付的验证工作流（供本人 + 2 个外部执行 Agent 并行推进）。

**核心原则（贯穿全篇）**：

- **证伪优先**：验证的目的是**推翻**「执行器可用」这一假设；只有真跑出成功证据链才算通过。禁止用「单测绿」「SKIPPED 正确」冒充「真跑通过」。（呼应仓库既有教训：假绿/桩/污染冒充真跑。）
- **真环境优先 Docker**：引擎一律优先用 Docker 镜像安装真实实例（Hive、StarRocks、Doris、ClickHouse、Spark、Flink、DataX、SeaTunnel）；不得用「假替身脚本」冒充真引擎作为通过依据（假替身只可用于说明性对照，不能作为 SUCCESS 证据）。
- **证据留痕**：每类任务类型的真跑必须产出可复查的证据（起止 banner + 逐行执行日志 + 结果集/影响行数 + 退出码 + 环境版本），并可被第三者按记录复现。

**范围边界**：本特性**不改动 059 执行器的功能语义**（除非真跑暴露出缺陷需修）；它交付的是**验证环境 + 真跑证据 + 修复真跑暴露的缺陷**。引擎的生产化部署、性能压测、跨引擎方言统一、可视化作业构建器均不在本轮范围。

## Clarifications

### Session 2026-07-10

- Q: 061 的交付形态（真跑证明以什么形式沉淀进仓库、作为 DoD）？ → A: 可复现脚本 + 证据台账（每引擎 docker-compose/安装脚本 + 一键真跑脚本 + 日志/退出码/版本证据归档进 specs/061；不落长期 CI 集成测试，重引擎不进 CI）。
- Q: 某引擎在本机 Docker 起不起来时 SC-002 的验收底线？ → A: 必须全部真跑（硬门）——7 类引擎家族无一例外须真跑成功；起不来是**阻塞项须解决**（加资源/换镜像/换接入方式），确实无法解决则如实记录为「未完成/阻塞」而非「达标」，且绝不以假替身或标注冒充通过。
- Q: 本机单台 Docker 资源有限，环境编排与三条并行工作流的资源策略？ → A: 按工作流分 profile 按需起——docker-compose 按引擎家族分 profile（olap / integration / compute），各工作流只起自己那批、跑完即 down，三 Agent 时间上错峰共享本机 Docker。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 数仓 SQL / HQL 任务对真实引擎跑通（OLAP + Hive）(Priority: P1)

维护者要证明面向 StarRocks / Doris / ClickHouse 的 `SQL` 任务与 `HIVE` 任务能连上**真实数仓引擎**、真执行语句、把结果集与影响行数真实渲染进日志、真透传退出码——而不只是「无驱动时跳过」。

**Why this priority**: 这是 059 覆盖面最广、复用现有 SQL 执行路径、离用户价值最近的一类；也是「只被 SKIPPED 路径验证过、真连库从未证明」风险最高的一类（涉及真实 JDBC 驱动、方言、连接语义）。单独交付即证明了平台最主流的数据开发场景真能干活，构成本验证 MVP。

**Independent Test**: 用 Docker 起真实的 StarRocks / Doris / ClickHouse / HiveServer2 实例，登记对应数据源，创建绑定该源的 SQL/HQL 任务并试跑，看到「连接 → 逐条语句执行 → `SHOW TABLES`/`SELECT` 结果集真实渲染 → DML 影响行数 → 成功/退出码」的真实证据链；同一环境下故意写一条错误语句，验证真失败与退出码透传，并与「已跳过」清晰区分。

**Acceptance Scenarios**:

1. **Given** Docker 起了真实 StarRocks 实例并登记为数据源，**When** 创建绑定它的 SQL 任务（含建表 + 插入 + `SELECT`）并试跑，**Then** 日志真实透出逐条语句执行、`SELECT` 结果集（表头+数据行）、DML 影响行数，任务成功且退出码为 0——证据可复现。
2. **Given** Docker 起了真实 ClickHouse 与 Doris 实例，**When** 各自试跑对应 SQL 任务，**Then** 真连库执行成功，日志契约（banner + 逐行 + 结果集/影响行数）与 StarRocks 一致。
3. **Given** Docker 起了真实 HiveServer2（含 metastore），**When** 创建 HIVE 任务编写多语句 HQL（含分区写入 + `SHOW PARTITIONS`）并试跑，**Then** 经 Hive 执行器真连库运行，逐行透出执行日志与结果集，退出码忠实透传。
4. **Given** 上述任一真实引擎在位，**When** 提交一条语法/语义错误的语句，**Then** 得到**真实失败**（非 SKIPPED），退出码忠实透传，日志含引擎原生错误行；与「缺引擎已跳过」在结果分类上清晰可辨。

---

### User Story 2 - 数据集成任务对真实引擎跑通（DataX + SeaTunnel）(Priority: P2)

维护者要证明 `DATAX` 与 `SEATUNNEL` 任务能以子进程提交**真实安装的引擎**、真搬运一份最小 source→sink 数据、真透出引擎原生同步日志与退出码。

**Why this priority**: 数据集成是第二高频场景，且这两个引擎是「子进程 + 环境变量探测（DATAX_HOME / SEATUNNEL_HOME）」范式，真跑能一并验证「引擎程序由环境提供、缺失即跳过」的探测逻辑在真实安装下确实切到 SUCCESS 分支。独立于 SQL 与计算引擎，可单独交付。

**Independent Test**: 在容器内真实安装 DataX / SeaTunnel（配好 `DATAX_HOME` / `SEATUNNEL_HOME`），编写最小 source→sink 作业（如内置 stream reader → 内置 writer，或 MySQL→StarRocks），试跑看到引擎真实逐行同步日志与成功退出码；再制造一份指向不存在表/文件的作业，验证真失败与退出码透传。

**Acceptance Scenarios**:

1. **Given** 容器内真实安装了 DataX 且 `DATAX_HOME` 就位，**When** 创建 DATAX 任务粘贴一份可跑的 job JSON 并试跑，**Then** 平台子进程真提交 `datax.py`，逐行透出 DataX 原生同步统计日志，成功且退出码 0。
2. **Given** 容器内真实安装了 SeaTunnel 且 `SEATUNNEL_HOME` 就位，**When** 创建 SEATUNNEL 任务编写可跑的同步配置并试跑，**Then** 平台子进程真提交 `seatunnel.sh`，逐行透出引擎原生日志，成功且退出码 0。
3. **Given** 引擎真实在位，**When** 提交一份作业本身错误（源表不存在）的任务，**Then** 得到真实失败、退出码忠实透传、日志含引擎原生错误——与「缺 `DATAX_HOME` 已跳过」清晰区分。

---

### User Story 3 - 计算与流式任务对真实引擎跑通（Spark + Flink，含流式 reattach）(Priority: P2)

维护者要证明 `SPARK`（pyspark / spark-sql / jar 形态）与 `FLINK`（SQL / jar 形态，含 060 的 `long_running` detached 提交 + reattach）能对**真实 Spark / Flink 集群**真提交、真出日志、真透传退出码；并证明 Flink 流式作业的 detached 提交 + JobID 回写 + reattach 状态轮询在真实 JobManager 下确实成立。

**Why this priority**: Spark 历史仅以「假 spark-submit 替身」跑过（016），真集群从未证明；Flink 是 059 新增 + 060 刚从桩转真实现的 reattach 链路，最需要真实 JobManager 端到端证据。二者共同构成「计算+流式」验证切片。

**Independent Test**: 用 Docker 起真实 Spark（standalone 或 local 集群）与 Flink（JobManager+TaskManager，REST 8081）；创建 SPARK 任务（至少一种形态）真提交跑通并看真实日志/退出码；创建 FLINK 有界 SQL/jar 任务真提交跑通；创建 Flink `long_running` 流式任务验证 detached 提交拿到真实 JobID → 句柄回写 → reattach 轮询到真实终态。

**Acceptance Scenarios**:

1. **Given** Docker 起了真实 Spark 集群且 `SPARK_HOME`/master 就位，**When** 创建并试跑一个 SPARK 任务（如 pyspark 计算），**Then** 平台子进程真提交 `spark-submit`，逐行透出 Spark 原生日志，成功且退出码 0，本地 `dw run` 与服务端行为一致。
2. **Given** Docker 起了真实 Flink（REST 8081 可达）且 `FLINK_HOME` 就位，**When** 创建并试跑一个有界 FLINK 任务，**Then** 平台子进程真提交 Flink，逐行透出原生日志，退出码忠实透传。
3. **Given** 真实 Flink 在位，**When** 提交一个 `long_running` 流式 Flink 任务，**Then** detached 提交返回**真实 JobID**、`external_job_handle` 真实回写、reattach 能对真实 JobManager 轮询到运行/终态——证明 060 的 reattach 去桩链路在真集群成立。
4. **Given** 上述任一引擎缺失（对照组），**When** 试跑，**Then** 仍正确返回「已跳过」——真跑环境的建立不破坏既有 SKIPPED 保真。

---

### Edge Cases

- **「真跑通过」判据严格**：只有连上真实引擎、真提交、真产出成功证据链（日志+结果/影响行数+退出码 0）才算通过；单测绿、SKIPPED 正确、假替身脚本成功，一律**不构成** SUCCESS 证据。
- **SUCCESS / FAILURE / SKIPPED 三态在真环境下互不混淆**：真引擎在位且作业成功=SUCCESS；真引擎在位但作业自身错误=真失败+退出码透传；真引擎缺失=SKIPPED。三者证据必须可区分，不新增状态机状态。
- **引擎版本漂移**：每套环境必须记录引擎镜像/版本（如 ClickHouse X.Y、Flink A.B），验证证据须绑定具体版本，避免「换个版本就不成立」的隐性假设；驱动版本与引擎版本的匹配须记录。
- **本地 `dw run` ↔ 服务端漂移**：同一任务经本地 `dw run` 与服务端调度对真实引擎运行，结果分类与语义须一致。
- **真跑暴露的执行器缺陷**：若真连引擎暴露出 059 执行器的 bug（连接参数、方言、日志吞并、退出码误判、结果集渲染缺失），须如实记录并修复，修复后重跑取证；不得为了「通过」而绕过或掩盖。
- **重资源引擎的可行性（硬门）**：StarRocks / Doris / Hive 栈较重（多容器、内存占用高）；真跑成功是硬门，起不来 MUST 当阻塞项解决（加内存/换轻量镜像/分批错峰起），不得以「环境受限跳过」冒充达标；穷尽手段仍不可行者如实记为「未完成/阻塞」（非达标），绝不谎称通过。按 profile 错峰起（olap/integration/compute 分批、跑完即 down）以缓解单台资源压力。
- **凭据安全**：验证用的数据源账号/密码、作业内嵌凭据不得明文落入可导出定义或提交进仓库的证据文件。
- **多 Agent 并行改同一共享面**：3 条验证工作流可能同时改 `docker-compose`、seed 数据源、验证脚本目录——共享面须约定隔离，合并前对齐，遵守仓库多 Agent 协作硬规则（不覆盖他人产出）。

## Requirements *(mandatory)*

### Functional Requirements

**验证环境（真实引擎，优先 Docker）**

- **FR-001**: 本特性 MUST 为每一类引擎家族提供**可复现的真实运行环境**，引擎优先以 Docker 镜像安装真实实例，覆盖：StarRocks、Doris、ClickHouse、HiveServer2（含 metastore）、Spark、Flink、DataX、SeaTunnel。
- **FR-002**: 每套真实环境 MUST 可由记录在案的步骤（compose 文件 / 安装脚本 / 启动命令）一键或按清单复现，并 MUST 记录所用引擎镜像与版本号。**交付形态 = 可复现脚本 + 证据台账**：每引擎家族的 docker-compose/安装脚本 + 一键真跑脚本 + 证据（日志/退出码/版本）归档进 `specs/061`；本特性 MUST NOT 依赖将重引擎集成测试常驻代码库/CI（重引擎在 CI 起不动）。
- **FR-002a**: docker-compose 环境 MUST 按引擎家族分 profile（如 `olap` / `integration` / `compute`），使每条验证工作流只起自己那批引擎、跑完即 `down`；MUST NOT 要求全部引擎在本机同时常驻（单台资源受限，需错峰共享）。
- **FR-003**: 验证环境 MUST 与平台既有本地开发方式（后端 `spring-boot:run` / worker / `dw run`）联通，使平台执行器能真实连到这些引擎（数据源登记、`*_HOME`/PATH、master/REST 端点等接入信息须配齐并记录）。
- **FR-004**: 每一类引擎家族 MUST 真跑成功——这是**硬门**：本机 Docker 起不起来 MUST 视为**阻塞项须解决**（升级资源 / 换轻量或兼容镜像 / 换接入方式），MUST NOT 将「环境受限跳过」当作可接受终态。确实穷尽手段仍无法起真实实例的，MUST 如实记录为「未完成/阻塞 + 原因 + 已尝试手段」（计为未达标，非达标），并 MUST NOT 以假替身、单测或标注冒充真跑通过。

**真跑证明（每类任务类型）**

- **FR-005**: 每一类任务类型（OLAP `SQL`×3 数仓、`HIVE`、`SPARK`、`FLINK`、`DATAX`、`SEATUNNEL`；`PYTHON`/`SHELL` 视为已真跑，纳入回归确认）MUST 完成对真实引擎的**端到端真跑成功（SUCCESS）**，并留存证据链。
- **FR-006**: 真跑证据 MUST 包含：起止 banner（模式/类型/数据源或引擎/版本/时间）+ 逐行执行日志 + 结果证据（SQL/HQL：结果集渲染 + 影响行数；子进程引擎：引擎原生 stdout/stderr）+ 退出码 + 耗时。
- **FR-007**: 每一类任务类型 MUST 同时验证**真实失败路径**：对真实引擎提交一个作业自身错误的任务，得到真失败 + 退出码忠实透传 + 引擎原生错误行，且与「已跳过」在结果分类上清晰可辨。
- **FR-008**: 真跑 MUST 覆盖 SQL/HQL 结果集渲染契约（059 FR-011a）：诊断/查询类语句（`SHOW TABLES`/`DESCRIBE`/`SELECT`）在真库上执行时，结果集（表头+数据行，带截断标注）真实出现在日志中，而非仅「有返回」。
- **FR-009**: Flink `long_running` 流式任务 MUST 对真实 JobManager 验证 detached 提交返回真实 JobID、`external_job_handle` 真实回写、reattach 轮询到真实终态（证明 060 去桩 reattach 链路成立）。
- **FR-010**: 同一任务定义经本地 `dw run` 与服务端调度对真实引擎运行 MUST 产生一致的结果分类与执行语义（保真）。

**缺陷处置与既有保真**

- **FR-011**: 真跑暴露的 059 执行器缺陷 MUST 如实记录并修复，修复后 MUST 重跑取证；相关执行器若被修改，MUST 补/更新对应单测（新行为有测试才算完成）。
- **FR-012**: 真实环境的建立 MUST NOT 破坏既有「缺引擎 → 已跳过」保真：无外部依赖（H2 / CI）下的 SKIPPED 闭环测试 MUST 仍全绿。
- **FR-013**: 涉及执行器/schema/共享面的任何修改 MUST 遵守仓库红线（无旁路分发、push 闸门与审计轨迹、schema 变更 bump `schema_version`、多 Agent 不覆盖他人产出）。

**协作与留痕**

- **FR-014**: 本特性 MUST 可拆分为多条互不阻塞的并行验证工作流（供本人 + 2 个外部 Agent），每条工作流各自可独立搭环境、独立真跑、独立取证；共享面（compose、seed、脚本目录、任务类型枚举）MUST 在合并前对齐、不互相覆盖。
- **FR-015**: 每类任务类型的真跑结果 MUST 汇总成一份可核验的验证报告/证据台账（哪类通过、证据在哪、引擎版本、未通过项及原因），使第三者仅凭报告即可判断真伪与复现路径。
- **FR-016**: 验证用凭据与作业内嵌凭据 MUST NOT 明文提交进仓库或落入可导出定义/日志。

### Key Entities *(include if data involved)*

- **验证环境（Verification Environment）**：一套让某引擎家族真实可跑的运行环境；含引擎 Docker 镜像/版本、启动方式、平台接入信息（数据源/`*_HOME`/端点）、复现步骤。
- **真跑证据（Real-Run Evidence）**：一次对真实引擎的端到端运行留痕；含任务类型、引擎版本、完整日志（banner+逐行+结果/影响行数+原生输出）、退出码、结果分类（SUCCESS/FAILURE/SKIPPED）、复现命令。
- **验证工作流（Verification Workflow）**：按引擎家族切分、可独立交付给一个 Agent 的一束验证任务（搭环境 → 真跑成功 → 真跑失败 → 取证 → 修缺陷重跑）。
- **验证报告/台账（Verification Ledger）**：全体任务类型的真跑结论汇总；每类一行：状态、证据位置、引擎版本、未通过原因。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 059 新增/暴露的 **6 类新增/暴露引擎家族**——OLAP-SQL（含 StarRocks/Doris/ClickHouse 3 数仓）、`HIVE`、`SPARK`、`FLINK`、`DATAX`、`SEATUNNEL`——均有一套可复现的真实 Docker 环境，且每套环境记录了引擎镜像与版本号。（`PYTHON`/`SHELL` 视为已真跑，仅纳回归确认，不单列环境。**台账口径**：6 家族展开为 10 条引擎行——StarRocks/Doris/ClickHouse/Hive/DataX/SeaTunnel/Spark/Flink + Python/Shell 回归。）
- **SC-002**: 每一类任务类型均完成**对真实引擎的端到端真跑成功**并留存证据链（banner + 逐行日志 + 结果集/影响行数或引擎原生输出 + 退出码 0）——**硬门：7 类引擎家族无一例外须真跑成功方为整体达标**；起不来须解决而非跳过，穷尽手段仍不可行者如实记为「未完成/阻塞」（不计达标、不冒充），零虚假通过。
- **SC-003**: 每一类任务类型均验证了**真实失败路径**（引擎在位、作业自身错误 → 真失败 + 退出码透传），且 SUCCESS / FAILURE / SKIPPED 三态在真环境下证据可区分。
- **SC-004**: SQL/HQL 类任务在真库上执行诊断/查询语句时，结果集内容真实出现在日志中（可见表头+数据行），验证 059 FR-011a 结果集渲染契约在真引擎下成立。
- **SC-005**: Flink `long_running` 流式任务在真实 JobManager 上验证 detached 提交真实 JobID + reattach 轮询到真实终态成功。
- **SC-006**: 同一任务定义在本地 `dw run` 与服务端运行下对真实引擎的结果分类一致率 100%。
- **SC-007**: 无外部依赖（H2/CI）环境下 059 既有 SKIPPED 闭环与单测 MUST 仍 100% 绿（真跑加固不引入回归）；真跑暴露的缺陷全部记录并修复重跑取证。
- **SC-008**: 全部真跑结论汇入一份验证报告/台账，第三者仅凭报告即可判断每类任务类型的真伪状态与复现路径。
- **SC-009**: 验证工作可拆为多条互不阻塞的并行工作流交付（本人 + 2 外部 Agent），各自可独立搭环境、真跑、取证。

## Assumptions

- **059 已合入 main 且执行器齐备**：7 类执行器（`SqlTaskExecutor` / `HiveTaskExecutor` / `SparkTaskExecutor` / `PythonTaskExecutor` / `FlinkTaskExecutor` / `DataXTaskExecutor` / `SeaTunnelTaskExecutor`）已在 main（已核验），本特性在其之上做真实引擎验证与加固，不重做功能。
- **引擎接入范式沿用 059/060 约定**：OLAP 与 Hive 走数据源 + 驱动隔离（JDBC）；DataX/SeaTunnel/Spark/Flink 走子进程 + `*_HOME`/PATH/master/REST 探测；Flink 流式走 060 的 `long_running` detached + reattach。真跑即验证这些约定在真引擎下切到 SUCCESS 分支。
- **优先 Docker 安装真实引擎**：能有官方/社区 Docker 镜像的引擎一律用真实镜像起实例；DataX/SeaTunnel 等以 tar 包安装形式装进容器并配好 `*_HOME`。假替身脚本仅可作说明性对照，不得作为通过证据。
- **交付形态 = 可复现脚本 + 证据台账（已澄清）**：真跑证明沉淀为 `specs/061` 下的 compose/安装脚本 + 一键真跑脚本 + 证据（日志/退出码/引擎版本）+ 台账；不将重引擎集成测试常驻 CI（起不动）。既有 059 SKIPPED 单测仍留在代码库跑绿（FR-012）。
- **重资源引擎真跑是硬门（已澄清）**：StarRocks / Doris / Hive 栈较重，本机 Docker 起真实实例是硬要求；起不来当阻塞项解决（加内存/换轻量镜像/分批错峰），穷尽手段仍不可行者如实记为「未完成/阻塞」（不达标、不冒充），见 FR-004。
- **资源错峰（已澄清）**：docker-compose 按引擎家族分 profile（olap/integration/compute），各工作流按需起自己那批、跑完即 down，三 Agent 时间上错峰共享本机单台 Docker。
- **凭据沿用现有数据源密钥与隔离机制**，本轮不新建密钥体系；验证用弱口令仅用于本地隔离环境，不提交进仓库。
- **不在本轮范围**：引擎生产化部署/高可用、性能与并发压测、跨引擎方言统一层、可视化作业构建器、引擎自动版本管理。
- **并行工作流切分（供 plan/tasks 落地，非最终约束）**：
  - **工作流 A**：OLAP 数仓真环境 + 真跑（StarRocks / Doris / ClickHouse SQL）+ Hive 独立执行器真跑（US1）。
  - **工作流 B**：数据集成真环境 + 真跑（DataX + SeaTunnel）（US2）。
  - **工作流 C**：计算与流式真环境 + 真跑（Spark + Flink，含 `long_running` reattach）（US3）。
  - 每条工作流各自使用独立 git worktree 隔离，共享面（`docker-compose` / seed 数据源 / 验证脚本目录 / 任务类型枚举 / i18n key）在合并前对齐，遵循仓库多 Agent 协作硬规则。
