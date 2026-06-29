# Phase 0 Research: Weft 任务创作 Skill + dev-loop 体验收口

本特性几乎不引入未知技术栈——以下是据代码勘查（精确到文件:行）固化的决策。

## R1. Skill 形态与分发

- **Decision**: 在 `.claude/skills/weft-task-authoring/` 建项目级 Skill 包：`SKILL.md`（frontmatter `name`/`description`/`allowed-tools: Bash,Read,Write,Edit,Grep` + 渐进披露正文）+ 支持文件 `file-contract.md`（速查）+ `examples/`（可仿写最小任务/任务流模板）。
- **Rationale**: Claude Code Skills 遵循 Agent Skills 开放标准；项目级随仓库 clone 即得，零安装；渐进披露使"未创作时"仅 `description` 常驻、正文按需加载，正面解决 MCP 26 工具 schema 常驻吃上下文。与现有 `.claude/skills/openspec-*`/`speckit-*` 的 kebab-case + frontmatter 约定一致。
- **Alternatives considered**: ① plugin 命名空间发布（更重、对"clone 即得"无增益，YAGNI）；② 把知识塞 CLAUDE.md（常驻、违背省上下文初衷）；③ 继续以 MCP 工具承载创作（正是要解决的上下文膨胀）。

## R2. dw 两套认证合并为单一凭据

- **Decision**: 统一为单一 Bearer 凭据（沿用 `DW_TOKEN` 环境变量、`Authorization: Bearer`）。CLI 端把 `cli/main.go:152–163` 手拼的 `X-DW-Token` 注入迁到 `cli/client/client.go:101–102` 统一逻辑；服务端在 `JwtAuthFilter.java:32–42` 移除 `/api/cli` 白名单或令其同接受 JWT，`CliController.java:106–122 requireToken()` 改为接受统一凭据。**保留旧 `X-DW-Token` 一段过渡兼容期**（双接受），避免老 CLI 立刻断。
- **Rationale**: 现状两套（`X-DW-Token` 走 `/api/cli/*`、Bearer JWT 走 `/api/projects|ops|tasks/*`）是 golden path 401 试错的根源。统一为 Bearer 复用既有 `JwtAuthFilter`（原则 V 复用内核）。合并限**认证**（authentication），不触 PolicyEngine 授权与审计、不改 API 体契约（FR-028 例外）。
- **Alternatives considered**: ① 仅文档标注不合并（用户 Q2 否决）；② 统一为 `X-DW-Token`（放弃 JWT 身份信息，劣）；③ 不留兼容期硬切（破坏在跑的老 CLI，违 round-trip 稳健）。

## R3. dw run 退出码歧义拆分

- **Decision**: 新增 `ExitEnvironment`（建议 7）专表"环境/前置错"（缺 JVM/worker classpath）；`cli/run/local.go:85–95` 把环境错从 `ExitRunFailed`(6) 改判为 `ExitEnvironment`(7) 或 `ExitUsage`(2)，**任务执行失败透传 runner 原始退出码**而非一律外层 6。保持 `ExitOK`=0 语义不变（FR-016）。
- **Rationale**: 现状 `local.go:88`（环境错）与 `local.go:93`（任务失败）同用 6，脚本/agent 无法区分。`FindWorkerClasspath()`（`local.go:172–202`）探测失败已返回 `UsageError`(2，可定位)——退出码拆分让"为何失败"可编程判别，正是原则 III"忠实报告 exit code"。
- **Alternatives considered**: ① 不拆，仅改文案（agent 仍无法编程区分）；② 复用 2（环境错与用法错混淆，劣于专用码）。

## R4. datasources.local.yaml 提前校验

- **Decision**: 在 `cli/run/datasource.go:29–49 LoadDatasources()` 加载即做基本 schema 校验（必填字段如 `jdbcUrl`/类型缺失在**运行前**报可定位错），并随 Skill `examples/` 提供 `datasources.local.yaml` 脚手架样例。
- **Rationale**: 现状缺字段只在 `LookupDatasource():62–68` 运行中途暴露。提前校验把错误左移到运行前，错误信息含"缺哪个字段、哪个数据源"。
- **Alternatives considered**: 引入完整 JSON Schema 校验库（过重，YAGNI；手写必填校验足够）。

## R5. push baseline 过期提示可读化

- **Decision**: `cli/sync/push.go:46–49` 透传服务端 `project.sync.stale`(409) 时，CLI 侧把它渲染成可读提示——说明"本地基线过期"+推荐处置（先 `dw pull` 或 `dw push --force` 覆盖），而非裸抛 errorCode。服务端检测逻辑不动。
- **Rationale**: 现状直接透传服务端 error（`sync_e2e_test.go:83–86` 示 mock 行为）。CLI 侧加一层人读渲染即可，零服务端改动、零契约改动。
- **Alternatives considered**: 改服务端错误体（违 FR-028 不改 API 体契约）。

## R6. golden path 双层 E2E

- **Decision**: ① **CI 确定性层**：复用 `cli/sync/sync_e2e_test.go` 的 mock/httptest 地基 + 真后端 `@ActiveProfiles("h2")`，脚本化 dw 命令链跑 pull→author(写文件)→run→diff→push→run --test，断言每步退出码/输出，**无 LLM key**、进 CI。② **真 LLM 验收层**：文档化仪式（quickstart.md），真本地 agent（workhorse + 开发者提供配置）加载 Skill 真驱动，**不进 CI**（无 key 环境靠①保持绿）。
- **Rationale**: 真 LLM 非确定性且需 key，不能进 CI；脚本化层钉住 golden path 管路防回归；真 LLM 层忠实证 agent-driven 主张（spec Q4/Q5）。后端 H2 共享内存库须注意跨测试类串台（见记忆 h2-shared-mem-db-test-pollution）——净库测试用独立库名。
- **Alternatives considered**: 仅真 LLM（CI 不覆盖，Q5 否决）；仅脚本化（证不到 agent-driven，Q5 否决）；全集成 PG+Redis（WSL2 长跑易卡 flaky，Q4 否决）。

## R7. Skill 一致性 lint（防文档-实现漂移）

- **Decision**: 加一个轻量校验（Go test 或脚本）：解析 `SKILL.md` 正文里引用的 `dw <subcommand> [--flag]`，逐个比对 `dw` 实际支持的子命令/flag（来自 `cli/main.go` 命令表），不匹配即 fail。
- **Rationale**: Skill 正文是 golden path 单一真相，若引用了不存在的命令/flag 会误导 agent（spec Edge Case）。把"Skill 引用 ⊆ CLI 实际"做成 CI 卡点。
- **Alternatives considered**: 纯人工评审（易漂移，不可靠）。

## R8. MCP 重新定位（仅文档）

- **Decision**: `docs/architecture.md` 的 MCP/agent 接入小节 + `CLAUDE.md:69–74` MCP & CLI 小节，把创作主路径定位为 Skill + `dw`，MCP 定位为自动化/查询可选面，写明"创作走 Skill 避免常驻上下文膨胀"取舍。`McpToolRegistry.java` 零改动。
- **Rationale**: 固化战略决定，防后续贡献者往常驻工具面堆创作能力。FR-021 要求 MCP 行为零变更。
- **Alternatives considered**: 删/改 MCP 工具（用户明确不删，FR-021）。

## 未决项

无 NEEDS CLARIFICATION 残留——spec 的 5 条 Clarifications 已闭合所有高影响歧义。
