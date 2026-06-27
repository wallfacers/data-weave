# Feature Specification: Weft 子特性 D —— CLI 同步命令 + 本地轻量 runtime

**Feature Branch**: `009-weft-cli-runtime`

**Created**: 2026-06-27

**Status**: Draft

**Input**: User description: "Weft 子特性 D:dw CLI 同步命令 + 本地轻量 runtime,本机真跑调试与 pull/push/diff/run 闭环"

## 概要

D 让开发者在本地 git 工作副本里用 `dw` 命令完成「拉取 → 本地真跑调试 → 推回」闭环。承接总纲(005)**FR-009/FR-010/FR-011**,落地宪法**原则 III「本地两条腿调试」**。

D 复用已落地的 **B(filecontract 文件契约)** 解析/装配文件,复用 **C(pull/push/diff API)** 完成服务器往返。D 不重发明文件格式、不重写同步逻辑;CLI 为 Go,**本地 runtime 本期复用平台 Java 执行器子进程以取得代码级语义一致**(见 Clarifications,对总纲 FR-010 的显式偏离,宪法原则 III 待修订;Go 原生留后续)。

依赖:**本地真跑子集(US2,总纲 FR-009/010)依赖 B**(能解析文件即可);**同步子集(US1)依赖 C**;**TEST 提交子集(US3)依赖 C**。

## Clarifications

### Session 2026-06-27

- Q: D 本地 runtime 用什么手段在本机真跑任务? → A: **本期复用平台 Java 执行器子进程**(代码级语义一致、难度低,需本机 JVM);Go 原生实现留后续看时机。**这是对总纲 FR-010「契约级对齐 / Go 原生 / 轻量」的显式偏离,用户裁定宪法可改(原则 III 待修订)。**
- Q: `dw run <X>` 里 X 用什么定位任务? → A: **相对文件路径与任务名两者都支持**(路径优先、名字作便捷别名)。
- Q: SQL 类任务本机执行的数据源逻辑名→本地连接配置从哪来? → A: **本地 git-ignored 配置文件**(按 datasource 逻辑名映射连接串,凭据本地持有、绝不上行)。
- Q: 本地 runtime MVP 覆盖哪些任务类型? → A: **SHELL + SQL + PYTHON**(DATA_SYNC 较重,排除在本期 MVP 外)。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 本地工作副本往返同步 (Priority: P1)

开发者在本地目录用 `dw pull <project>` 把服务器上某项目装配为纯文本文件树(目录即类目树),在编辑器/AI agent 里改定义后用 `dw diff` 预览将被覆盖/新增/删除的内容,确认无误用 `dw push` 幂等覆盖回服务器并生成新版本快照。受租户/项目隔离,越权命令被服务器拒绝且 CLI 以非零退出码忠实反映。

**Why this priority**: 没有 pull/push,本地文件副本无法与治理真相源往返,D 的存在意义不成立。这是 C 的服务端能力第一次有了人类可操作入口。

**Independent Test**: 对一个真实项目 `dw pull` 到空目录 → 改一个脚本 → `dw diff` 看到该任务进入 modified → `dw push` → 再 `dw pull` 到另一干净目录,两次文件树语义等价(复用 B 的 round-trip 字节稳定)。

**Acceptance Scenarios**:

1. **Given** 一个有权访问的项目, **When** `dw pull <project>` 到空目录, **Then** 生成的文件树目录结构映射类目树,任务元数据与脚本体分离,datasource 以逻辑名出现、无连接凭据。
2. **Given** 本地改了一个任务脚本, **When** `dw diff`, **Then** 终端列出 added/modified/removed 概览,且不向服务器写入任何东西(只读)。
3. **Given** 一份有效本地文件树, **When** `dw push`, **Then** 服务器幂等覆盖该项目定义并为受影响 task/workflow 生成新版本快照,CLI 报告 created/updated/deleted 统计 + 退出码 0。
4. **Given** 服务器侧自上次 pull 后已被他人改动(基线过期), **When** `dw push` 未带强制标志, **Then** CLI 以可读信息提示基线过期并拒绝,退出码非零;带 `--force` 时覆盖。
5. **Given** 无权访问的项目或无效令牌, **When** 任意同步命令, **Then** 服务器拒绝,CLI 打印可定位错误并以非零退出码结束。

### User Story 2 - 本地轻量 runtime 真跑调试 (Priority: P1)

开发者在本地工作副本里用 `dw run <task>` 直接在本机真实执行该任务的脚本体(SQL/Shell/Python),连接本地/开发数据源,输出直出终端,退出码忠实反映执行结果。该能力脱离服务器 worker 进程独立运行,执行行为与服务器执行器**契约级对齐**。

**Why this priority**: 这是宪法原则 III 两条腿里的本地腿,是 D 区别于"纯 CLI 客户端"的核心价值 —— 开发者改完定义秒级本地验证,不必每次提交服务器。仅依赖 B,可与 C 并行。

**Independent Test**: 在本地副本里对一个 SHELL 任务 `dw run`,断言其 stdout/stderr/退出码与同一脚本在服务器执行器跑出的结果契约一致;故意写一个失败命令,断言 CLI 退出码非零。

**Acceptance Scenarios**:

1. **Given** 本地副本里一个 SHELL 任务, **When** `dw run <task>`, **Then** 脚本体在本机执行,stdout/stderr 直出终端,成功退出码 0。
2. **Given** 一个会失败的任务(命令返回非零或脚本异常), **When** `dw run`, **Then** CLI 以非零退出码结束,错误输出可见。
3. **Given** 一个 SQL 任务且本地配置了对应 datasource 连接, **When** `dw run`, **Then** runtime 按数据源逻辑名加载本地连接配置真实执行,结果/影响行数直出。
4. **Given** 一个引用了本地未配置数据源的任务, **When** `dw run`, **Then** CLI 以可定位错误提示缺失的数据源逻辑名,不静默空跑。
5. **Given** 一个设置了超时上限的任务, **When** `dw run` 且执行超时, **Then** runtime 按对齐的超时语义中止并以非零退出码报告。

### User Story 3 - TEST 模式提交服务器执行 (Priority: P2)

开发者用 `dw run --test <task>`(或 workflow)把任务以 TEST 模式提交服务器真实调度执行,日志流式回传本地终端,便于在贴近生产的环境验证后再正式 push 上线。

**Why this priority**: 两条腿里的服务器腿。本地真跑(US2)覆盖快速验证,TEST 提交覆盖"贴近生产环境"验证。依赖 C,优先级次于本地闭环。

**Independent Test**: 对一个任务 `dw run --test`,断言服务器创建了一个 TEST 实例且本地终端收到流式日志直至实例终态;断言该 TEST 执行受写闸门与隔离约束。

**Acceptance Scenarios**:

1. **Given** 一个有权项目里的任务, **When** `dw run --test <task>`, **Then** 服务器创建 TEST 实例并调度执行,本地终端流式显示日志直到终态,退出码反映实例成败。
2. **Given** TEST 提交是一次受控运行, **When** 提交, **Then** 该操作经写闸门与审计,越权被拒。
3. **Given** 流式连接中途断开, **When** 重连或结束, **Then** CLI 给出明确状态提示,不静默挂死。

### Edge Cases

- `dw push` 删除了一个被 ONLINE 工作流引用的任务定义 → 服务器(C 的删除守卫)整单拒绝,CLI 透传可定位错误,本地文件不变。
- `dw pull` 目标目录非空且含冲突文件 → CLI 需明确策略(拒绝/要求空目录/要求显式覆盖标志),不静默混写。
- 本地 runtime 缺少脚本语言解释器(如无 python)→ CLI 给出可定位的环境缺失错误,而非晦涩堆栈。
- 网络不可达 / 服务器 5xx → CLI 区分"客户端用法错误"与"服务端/网络错误",退出码与信息可区分。
- 全中文任务名在文件契约中退化为 hash 文件名(B 既有行为)→ CLI 命令需能用稳定标识(任务名或路径)定位任务,文档须说明。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: CLI MUST 提供 `dw pull <project>`,调用服务器 pull API,把返回文件集落地为本地目录树(目录映射类目树,元数据与脚本体分离),datasource 仅以逻辑名出现。
- **FR-002**: CLI MUST 提供 `dw push`,把本地文件树提交服务器幂等覆盖并触发版本快照,成功后报告 created/updated/deleted 统计,失败以可定位错误 + 非零退出码反映。
- **FR-003**: CLI MUST 提供 `dw diff`,只读预览本地与服务器差异(added/modified/removed),不产生任何服务器写入。
- **FR-004**: CLI MUST 在 push 时携带基线令牌做乐观并发;基线过期且未带 `--force` 时拒绝,带 `--force` 时覆盖。
- **FR-005**: CLI MUST 提供 `dw run <task>`,在本机真实执行任务脚本体,stdout/stderr 直出终端,退出码忠实反映结果。任务定位 MUST 同时支持**相对文件路径**(优先)与**任务名别名**;任务名因中文退化歧义时 MUST 提示改用路径。
- **FR-006**: 本地 runtime MUST 复用平台**真实执行器**保证**代码级语义一致**:本期以平台 Java 执行器(worker 模块中可脱离 master/scheduler 独立运行的执行切片)作为本机子进程被 Go CLI 调起,退出码、stdout/stderr 分流、超时中止、数据源驱动加载行为与服务器执行器一致;本机 MUST 具备 JVM。(本期对总纲 FR-010「Go 原生 / 契约级对齐 / 轻量」的显式偏离;Go 原生 runtime 留后续。)
- **FR-007**: 本地 runtime 的本期 MVP MUST 支持 **SHELL、SQL、PYTHON** 三类任务在本机执行;DATA_SYNC 排除在 MVP 外。对无法本地执行或缺依赖(无 JVM / 无 python 解释器)的情形 MUST 给出可定位错误,不静默空跑。
- **FR-008**: SQL 类任务的本地执行 MUST 按数据源逻辑名从**本地 git-ignored 配置文件**解析连接串;凭据 MUST 仅存于本地配置,MUST NOT 写入文件契约或随 push 上行。该配置文件路径/格式留 plan,但 MUST 被 git 忽略。
- **FR-009**: CLI MUST 提供 `dw run --test <task|workflow>`,以 TEST 模式提交服务器执行并流式回传日志至本地终端,退出码反映实例终态。
- **FR-010**: 所有同步与 TEST 提交命令 MUST 受租户/项目隔离约束(携带令牌鉴权);越权 MUST 被服务器拒绝,CLI 以非零退出码反映。
- **FR-011**: CLI 的服务器地址与令牌 MUST 通过既有约定配置(环境变量 `DW_API`/`DW_TOKEN`),与现有 `dw task/logs` 命令一致,不引入并行配置体系。
- **FR-012**: 新命令 MUST 复用现有 `cli/main.go` 的子命令分发风格与输出风格,保持单一 Go 二进制,不拆分发包。
- **FR-013**: 错误输出 MUST 区分用法错误(本地参数/文件)与服务端/网络错误,退出码可区分,信息可定位。

### Key Entities *(include if feature involves data)*

- **Local Working Copy**:本地目录树形态的项目定义工作副本;含 B 契约文件集 + 一份记录上次 pull 基线令牌与 projectId 的本地元数据(隐藏文件,不入服务器契约)。
- **Local Run Result**:本地 runtime 一次执行的结果(退出码、stdout/stderr、耗时);仅本地呈现,不落服务器。
- **TEST Instance Handle**:一次 TEST 提交对应的服务器实例引用(实例 id + 日志流)。
- **Datasource Local Binding**:数据源逻辑名 → 本地连接配置的映射(凭据本地持有,不上行)。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `dw pull` → 改定义 → `dw push` → 再 `dw pull` 到干净目录,两次文件树语义等价(round-trip 一致,复用 B 的 R3 字节稳定);≥99% 字段无丢失。
- **SC-002**: 同一脚本本地 `dw run`(经复用的 Java 执行器子进程)与服务器执行器执行的退出码 100% 一致;stdout/stderr 分流与超时中止行为一致(代码级复用,非口号)。
- **SC-003**: 越权 pull/push/run --test 100% 被拒,CLI 退出码非零且无服务器副作用。
- **SC-004**: 失败任务本地 `dw run` 100% 以非零退出码结束,不误报成功。
- **SC-005**: `dw diff` 在任何情况下对服务器零写入(只读);每次成功 `dw push` 为受影响 task/workflow 生成新版本快照。
- **SC-006**: 开发者可在不启动服务器 worker/master 调度进程的前提下完成一次本地 `dw run` 真跑(US2 脱机可用;本期前提为本机有 JVM)。

## Assumptions

- 复用 C 已落地的 `/api/projects/{id}/pull|push|diff` 与既有 TEST/日志流端点;D 不新增服务器同步端点(若 TEST 提交需要的服务器端点缺失,以最小补充实现,记入 plan)。
- 本地 runtime 本期**复用平台 Java 执行器子进程**(代码级一致、难度低,需本机 JVM),对总纲 FR-010 的「Go 原生 / 轻量」属**显式偏离**,用户已裁定**宪法原则 III 待修订**;Go 原生 runtime 留后续看时机。plan 须决定:worker 执行器如何被切成"可脱离 master 独立运行 + 被 CLI 调起"的子进程入口。
- 数据源本地连接配置走**本地 git-ignored 配置文件**(按逻辑名映射连接串),格式留 plan;凭据绝不上行。
- 单一 Go 二进制,沿用 `cli/main.go` 现有 switch 分发与 `DW_API`/`DW_TOKEN` 约定。
- 不含 MCP 工具(留 E)、不含服务端 AI、不做双向同步/冲突合并。

## Dependencies

- **B(007)filecontract**:解析/装配本地文件树。**零修改复用**。
- **C(008)pull/push/diff API**:US1/US3 的服务器往返与 TEST 提交。
- **现有 CLI 基座** `cli/main.go`(`task`/`logs` 命令、`DW_API`/`DW_TOKEN`)。
- **平台执行器(`dataweave-worker` 执行切片)**:US2 本地真跑复用其代码作为本机 JVM 子进程;plan 须给出可脱离 master 独立运行的执行入口(本期偏离 FR-010 的结果)。
- **现有 TEST 实例调度 + 日志流 SSE**(US3)。

## Out of Scope

- MCP 工具(E)。
- 服务端 AI 任何部分。
- 双向同步 / 冲突自动合并(宪法原则 II 禁止)。
- 存量数据迁移(宪法 No Legacy Migration)。
- 本地 runtime 对全部任务类型的 100% 覆盖(仅本机可执行子集)。
