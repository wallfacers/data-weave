# Phase 1 Data Model: Weft 任务创作 Skill + dev-loop 体验收口

本特性**零持久化实体、零 schema 变更**。下列"实体"是知识资源、CLI 行为契约与文档对象——给出其结构、字段与不变量，供 tasks 拆解与测试设计。

## E1. Weft 任务创作 Skill（知识资源）

**位置**: `.claude/skills/weft-task-authoring/`

| 组成 | 字段/结构 | 约束 |
|------|-----------|------|
| `SKILL.md` frontmatter | `name`（kebab，= `weft-task-authoring`）、`description`（触发语：创建/编辑 Weft 任务或任务流、本地跑/push 时加载）、`allowed-tools`（`Bash,Read,Write,Edit,Grep`） | description MUST 能触发自动加载（FR-006）；与现有 skill frontmatter 格式一致 |
| `SKILL.md` 正文 | 文件契约说明 / params+占位符 / datasource 逻辑名查法 / flow nodes-edges 一致性 / dev-loop 步骤序 / GateResult 三态读法 | 正文渐进披露（FR-005）；引用的每个 `dw` 命令/flag MUST ⊆ CLI 实际（FR-027） |
| `file-contract.md` | 一页速查（project/_folder/*.task/*.flow/脚本体/tags 结构） | 数据术语（cron/DAG/SLA/lineage）保持英文（Edge Case） |
| `examples/` | `sample-task.task.yaml`+`.sql`、`sample-flow.flow.yaml`、`datasources.local.yaml` 样例 | MUST 可被 `dw run`/`dw push` 接受（FR-012） |

**状态/生命周期**: 静态资源，随仓库版本演进；无运行态状态。

## E2. dw 统一认证凭据（CLI 行为契约）

| 维度 | 合并前 | 合并后 |
|------|--------|--------|
| 凭据来源 | `DW_TOKEN`（两处分别用作 X-DW-Token / Bearer） | `DW_TOKEN` 单一来源 |
| 请求头 | `X-DW-Token`（/api/cli/*）+ `Authorization: Bearer`（其他） | `Authorization: Bearer`（全部）；过渡期服务端双接受 |
| 注入点 | `main.go:152–163` 手拼 + `client.go:101–102` | 统一在 `client.go` |
| 服务端校验 | `CliController.requireToken()` + `JwtAuthFilter`（/api/cli 白名单） | 两处同接受统一 Bearer；保留 X-DW-Token 过渡兼容 |

**不变量**: 合并只改 authentication；MUST NOT 改 PolicyEngine 授权/审计、MUST NOT 改 API 请求/响应体契约（FR-015/FR-028）。

## E3. dw 退出码契约

| 码 | 名 | 含义 | 变化 |
|----|----|------|------|
| 0 | ExitOK | 成功 | 不变（FR-016） |
| 2 | ExitUsage | 用法/参数错 | 不变 |
| 3 | ExitUnauthorized | 认证失败 | 不变 |
| 4 | ExitServer | 服务端错 | 不变 |
| 5 | ExitNetwork | 网络错 | 不变 |
| 6 | ExitRunFailed | **仅**任务执行失败（透传 runner 非零码语义） | 收窄：不再混入环境错 |
| 7 | ExitEnvironment | **新增**：环境/前置错（缺 JVM/worker classpath） | 新增（FR-016） |

**不变量**: "环境失败"与"任务失败"码可区分；agent/脚本可编程判别。

## E4. datasource 本地配置（CLI 行为契约）

**对象**: `.weft/datasources.local.yaml`（既有），map<逻辑名, {type, jdbcUrl, username, password, driver...}>

| 校验 | 合并前 | 合并后 |
|------|--------|--------|
| 时机 | 运行中途 `LookupDatasource():62–68` | 加载即校验 `LoadDatasources():29–49`（左移） |
| 缺字段 | 运行时底层异常 | 运行前可定位提示（缺哪个数据源/字段） |

## E5. MCP 定位文档对象

`docs/architecture.md` agent 接入小节 + `CLAUDE.md:69–74`：记载"创作主路径=Skill+dw、MCP=自动化/查询可选面"及上下文取舍。MCP 工具代码零改动（FR-021）。

## E6. constitution 原则 IV 修订对象

`.specify/memory/constitution.md` 原则 IV：措辞 "operating the platform through MCP" → "through MCP and/or the dw CLI"，记 Skill 为本地 agent 知识层；不可让渡内核三条不变（FR-021a）。

## 删除对象（残留清理）

| 对象 | 处置 | 理由 |
|------|------|------|
| `deploy/workhorse/serve-local.sh`、`merge-config.py` | git rm | 断脚本，引用已删 fetch-bin.sh/config.yaml/mcp.json（FR-022） |
| `openspec/specs/workhorse-supervisor/spec.md` | git rm | 残留 spec，描述已删 supervisor 代码（FR-023） |
