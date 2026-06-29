# Feature Specification: 深化执行 —— Spark 协议 + runtime 语义对齐

**Feature Branch**: `016-spark-runtime-parity`

**Created**: 2026-06-29

**Status**: Draft

**Input**: User description: "轨道 1（深化核心执行能力）。新增 Spark 执行协议（spark-submit 子进程，本地 local[*] / 服务端 yarn 同一执行器零改动漂移，三种内容形态 pyspark/spark-sql/jar），同时收口勘查暴露的三处执行语义裂缝：distributed 模式分发缺陷、环境缺失伪装成功、本地 runtime 协议不完整。全部由 constitution 原则 III 的 fidelity 不变量约束并以测试钉死。"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 用 AI agent 本地开发并上线 Spark（PySpark）任务 (Priority: P1)

数据开发者在本地用 AI 编程 agent 创作一个 Spark 任务：脚本体是 PySpark 代码，任务声明绑定一个逻辑数据源名。开发者在本地 `dw run` 真跑（本地解析为 `local[*]` 单机提交），看到真实的 stdout/stderr 与退出码；调通后 `dw push` 上线、`dw run --test` 在服务端以集群（yarn）模式真跑。同一份任务文件本地与服务端零改动漂移——差异只在数据源按环境解析出的提交目标（local[*] vs yarn）。

**Why this priority**: Spark 是数据开发的刚需协议，也是本特性的头牌新能力。它把"任务即代码 + 本地真跑 + pull/push 治理"的闭环从 SQL/Python 扩展到 Spark，直接兑现产品定位。是能独立交付价值的最小垂直切片。

**Independent Test**: 在干净工作副本中创作一个 PySpark 任务（type=SPARK, sparkMode=pyspark + .py 脚本体 + SPARK 数据源引用），本地 `dw run`：装了 Spark 的机器看到真实提交与忠实退出码；未装 Spark 的机器看到明确的 SKIPPED（非伪装成功、非报错阻塞）。`dw push` 后服务端 TEST 运行成功。全程不触碰其它任务类型即可验证。

**Acceptance Scenarios**:

1. **Given** 本地装有 Spark 且 SPARK 数据源解析为 local[*]，**When** 开发者对一个 PySpark 任务执行 `dw run`，**Then** 任务真实经 spark-submit 提交执行，stdout/stderr 实时直出终端，退出码忠实透传（成功 0 / 失败非 0）。
2. **Given** 本地未安装 Spark（无 SPARK_HOME / spark-submit 不可用），**When** 开发者执行 `dw run`，**Then** 结果为明确的 SKIPPED 状态（输出可辨识地说明"已跳过：本地无 Spark 环境"），既不伪装成功也不以错误阻塞开发流。
3. **Given** PySpark 任务已 `dw push` 上线且服务端 SPARK 数据源解析为 yarn，**When** 开发者执行 `dw run --test`，**Then** 任务在服务端以 yarn 模式真跑，运行日志回流本地终端，退出码语义与本地一致。
4. **Given** 同一份 PySpark 任务文件，**When** 分别在本地与服务端运行，**Then** 除提交目标（local[*] vs yarn，由数据源按环境解析）外，任务文件无需任何改动。

---

### User Story 2 - 跨运行模式的执行语义一致 + 环境缺失忠实可见 (Priority: P2)

平台运维/开发者信任"同一个任务无论在哪条执行路径上跑，行为一致、结果忠实"。当前存在三处裂缝需收口：① distributed 模式下任务被错误地一律当 SHELL 执行（忽略任务类型）；② 缺数据源/缺环境时被伪装成"成功"，掩盖了真实情况；③ 本地轻量 runtime 支持的任务类型集合与服务端不一致。收口后，任意任务在 all-in-one、distributed、本地 runtime 三条路径上选用相同的执行器、产出逐项相等的退出码/输出分流/超时行为；环境缺失统一呈现为可辨识的 SKIPPED。

**Why this priority**: 这是执行层的正确性骨架，修复影响所有任务类型的潜在缺陷，是 Spark 等新协议在 distributed 模式下能正确运行的前提。可独立于 Spark 验证并交付价值（例如修复 SQL 任务在 distributed 模式被当 SHELL 跑的缺陷）。

**Independent Test**: 用一个 SQL 任务在 distributed 执行路径上验证它选用 SQL 执行器而非 SHELL；用一个无数据源的 SQL 任务验证结果为 SKIPPED（带 skipped 标志且输出可辨识）而非伪装成功；用本地 runtime 验证其支持的任务类型集合与服务端一致，且各类型本地↔服务端退出码/输出/超时逐项相等。无需涉及 Spark 即可验证。

**Acceptance Scenarios**:

1. **Given** 一个 SQL（或 PYTHON）任务在 distributed 执行路径上下发，**When** 执行器被选择，**Then** 选用与任务类型匹配的执行器（SQL→SQL 执行器），且执行上下文携带完整的任务类型与解析后数据源信息——与 all-in-one 路径行为一致。
2. **Given** 一个 SQL 任务未绑定可用数据源（或连接失败），**When** 执行，**Then** 结果为 SKIPPED（携带 skipped 标志 + 可辨识的输出说明），不阻塞调度下游，且人/测试/AI 都能区分"跳过"与"成功"。
3. **Given** 平台服务端支持的任务类型集合，**When** 用本地 runtime 运行其中任一类型，**Then** 本地 runtime 同样支持该类型，且退出码 / stdout-stderr 分流 / 超时中止行为与服务端执行器逐项相等。

---

### User Story 3 - Spark 三种内容形态全覆盖（Spark SQL + JAR） (Priority: P3)

在 PySpark 形态（US1）之上，开发者还能以另外两种形态创作 Spark 任务：① Spark SQL——脚本体是 SQL 文件，由统一提交路径执行；② JAR——提交预构建的 application jar 并指定主类。三种形态共享同一套提交语义、数据源解析与 SKIPPED 行为，开发者按作业性质自由选择。

**Why this priority**: 扩展 Spark 协议的内容形态覆盖面，满足 SQL 风格作业与已有 Scala/Java jar 作业的迁移需求。依赖 US1 的执行器与数据源地基，可在其之上增量交付，单独价值清晰但非首批必须。

**Independent Test**: 创作一个 Spark SQL 任务（sparkMode=spark-sql + .sql 脚本体）本地 `dw run` 真跑出 SQL 执行结果；创作一个 JAR 任务（sparkMode=jar + 引用上传的 jar 资产 + 主类）本地 `dw run` 真跑出 jar 作业结果。两者退出码/输出忠实透传，环境缺失同走 SKIPPED。

**Acceptance Scenarios**:

1. **Given** 一个 Spark SQL 任务（.sql 脚本体），**When** `dw run`，**Then** SQL 经统一提交路径在 Spark 上逐句执行，退出码与输出忠实透传。
2. **Given** 一个 JAR 任务引用了已上传的 application jar 资产并指定主类，**When** `dw run`，**Then** jar 作业经 spark-submit 提交执行，退出码与输出忠实透传。
3. **Given** 三种 sparkMode 中的任意一种且本地无 Spark 环境，**When** `dw run`，**Then** 均一致地呈现 SKIPPED。

---

### Edge Cases

- **本地无 Spark 环境**：`dw run` 一个 Spark 任务 → SKIPPED（可辨识说明），不报错阻塞、不伪装成功。
- **Spark 作业自身失败**（脚本异常 / SQL 语法错 / 主类抛错）：退出码忠实透传为非 0、stderr 直出——区别于"环境缺失"的 SKIPPED。
- **Spark 作业超时**：超过任务声明的超时秒数 → 子进程被中止，结果标记为超时，行为与现有 Shell/Python 超时一致。
- **JAR 资产缺失/损坏**：引用的 jar 资产不存在或不可读 → 忠实失败（非 SKIPPED，因为这是任务定义/资产问题而非环境缺失），输出可定位。
- **SPARK 数据源未配置**：任务声明了 SPARK 类型但未绑定可用 SPARK 数据源 → 缺少 master/SPARK_HOME 等关键提交参数，按"环境缺失"语义呈现 SKIPPED。
- **distributed 模式下未知任务类型**：无对应执行器的类型 → 与 all-in-one 路径同样地以可辨识方式呈现（不静默当 SHELL 跑、不伪装成功）。
- **同一任务在两条执行路径并发重复下发**：现有幂等键 (taskInstanceId, attempt) 去重不被本特性破坏。

## Requirements *(mandatory)*

### Functional Requirements

#### Spark 执行协议（WS1）

- **FR-001**: 系统 MUST 支持 SPARK 任务类型，经子进程方式提交执行（与现有 Shell/Python 任务同构的执行模型），忠实透传退出码、stdout/stderr 分流与超时中止行为。
- **FR-002**: 系统 MUST 通过任务声明中的子模式字段区分三种 Spark 内容形态：PySpark 脚本、Spark SQL、JAR + 主类。
- **FR-003**: 系统 MUST 以单一统一的提交路径承载全部三种内容形态（Spark SQL 经平台自带的轻量 SQL 执行入口提交，不依赖发行版附带的独立 SQL 命令行）。
- **FR-004**: 系统 MUST 通过一种 SPARK 类型数据源承载 Spark 集群提交配置（SPARK_HOME、master、deploy-mode、队列、附加 spark 配置项），并在执行前按当前环境解析（本地解析为单机 local 提交、服务端解析为集群提交）。
- **FR-005**: 同一份 Spark 任务文件 MUST 在本地与服务端零改动运行——本地与服务端的提交目标差异 MUST 仅来自数据源按环境的解析，而非任务文件内容差异。
- **FR-006**: JAR 形态的 application jar MUST 以上传资产方式引用（复用平台既有的驱动 jar 资产上传/隔离存储机制），任务文件只引用资产标识而不内联二进制。

#### runtime 语义对齐（WS2）

- **FR-007**: distributed 执行路径 MUST 按任务类型选择匹配的执行器（不得忽略任务类型而一律按 SHELL 执行），并 MUST 构建携带任务类型与解析后数据源的完整执行上下文。
- **FR-008**: 当任务因环境缺失（无可用数据源 / 无 Spark 环境）而无法真实执行时，系统 MUST 返回可辨识的 SKIPPED 结果（携带显式的 skipped 标志 + 输出说明），MUST NOT 伪装为成功，且 MUST NOT 以错误阻塞调度下游。
- **FR-009**: SKIPPED 结果 MUST 对人类用户、自动化测试与本地 agent 三类参与者均可辨识（区别于真实成功与真实失败）。
- **FR-010**: 本地 runtime 支持的任务类型集合 MUST 与服务端执行器集合一致（覆盖 SHELL/SQL/PYTHON/ECHO/SPARK）。
- **FR-011**: 对任意任务类型，本地 runtime 与服务端执行器的退出码 / stdout-stderr 分流 / 超时中止行为 MUST 逐项相等，并 MUST 由测试钉死（fidelity 不变量）。
- **FR-012**: SKIPPED 语义引入 MUST NOT 新增调度状态机状态——SKIPPED 在调度层按"非失败完成、不阻塞下游"处理，仅以结果标志与日志体现。

#### 创作面 + CLI 对齐（WS3）

- **FR-013**: 系统 MUST 文档化 Spark 任务的文件契约（任务类型、子模式、脚本体文件 / jar 资产引用、SPARK 数据源引用的结构语义）。
- **FR-014**: 任务创作 Skill MUST 补充 Spark 创作知识与至少一个最小可跑的 Spark 示例任务。
- **FR-015**: `dw run` MUST 支持本地真跑 SPARK 类型任务；本地无 Spark 环境时 MUST 呈现 SKIPPED 而非报错。

#### 测试与质量门（贯穿）

- **FR-016**: 本特性的每项行为变更 MUST 有对应自动化测试（Spark 三形态命令构造与透传、distributed 按类型分发、SKIPPED 保真、本地↔服务端 parity 扩展覆盖 ECHO + SPARK）。
- **FR-017**: 所有写/执行路径的既有授权与审计行为 MUST NOT 因本特性被弱化（执行层变更不绕过既有治理）。

### Key Entities *(include if feature involves data)*

- **Spark 任务**：一种任务类型，含子模式（PySpark / Spark SQL / JAR）、脚本体文件或 jar 资产引用、绑定的 SPARK 数据源逻辑名。
- **SPARK 数据源**：承载 Spark 集群提交配置（SPARK_HOME、master、deploy-mode、队列、附加 spark 配置项）的数据源；按环境解析出实际提交目标。
- **执行结果**：执行器产出的结果，新增"已跳过（SKIPPED）"这一可辨识状态，与"成功""失败"并列；携带退出码、输出、超时标志与 skipped 标志。
- **Spark JAR 资产**：以平台既有 jar 资产上传/隔离存储机制管理的预构建 application jar，被 JAR 形态任务按资产标识引用。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 开发者能在本地从零创作并真跑一个 PySpark 任务（装有 Spark 的环境真实提交、未装的环境明确 SKIPPED），再 push 上线、服务端 TEST 真跑——全程同一份任务文件零改动。
- **SC-002**: 对每种受支持任务类型（SHELL/SQL/PYTHON/ECHO/SPARK），本地 runtime 与服务端执行器的退出码、stdout-stderr 分流、超时中止行为逐项相等，由 parity 测试 100% 覆盖且全绿。
- **SC-003**: 同一个非 SHELL 任务（如 SQL）在 all-in-one 与 distributed 两条执行路径上选用相同的（按类型匹配的）执行器——distributed 不再将其当 SHELL 执行；由测试断言。
- **SC-004**: 环境缺失场景（无数据源 / 无 Spark）100% 呈现为可辨识的 SKIPPED，不再出现"伪装成功"的结果；由测试断言 skipped 标志存在且 success 不为伪装值。
- **SC-005**: 三种 Spark 内容形态（PySpark / Spark SQL / JAR）各自能本地真跑出预期结果，且失败（作业自身错误）与跳过（环境缺失）两种结果可被清晰区分。
- **SC-006**: 后端改动模块编译零错误、CLI 构建与测试全绿；既有任务类型的执行行为无回归。

## Assumptions

- 装有 Spark 的开发机/服务端通过 SPARK 数据源提供 SPARK_HOME 与 master 等配置；CI 与零外部依赖环境默认无 Spark，相关 Spark 执行在该环境表现为 SKIPPED（不影响构建绿）。
- 复用平台既有的数据源机制（解析、按类型输出不同格式）与 jar 资产上传/隔离存储机制，不新建并行机制。
- 本特性为纯执行层 / CLI / Skill 面变更，无前端改动。
- distributed 模式的执行器选择以 all-in-one 路径既有的"按类型映射"实现为正确基准对齐。
- 不引入嵌入式 Spark 运行时（不将 Spark 核心库拉入常驻进程类路径），统一以子进程提交，避免依赖膨胀。
- 超出范围（YAGNI）：Spark 字段级历史血缘解析（留待发布期图数据库重做）、Spark UI 监控集成、yarn 提交的 kerberos principal/keytab 鉴权、新增 MCP 工具。
