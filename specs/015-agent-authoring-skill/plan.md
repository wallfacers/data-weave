# Implementation Plan: Weft 任务创作 Skill + dev-loop 体验收口

**Branch**: `015-agent-authoring-skill` | **Date**: 2026-06-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/015-agent-authoring-skill/spec.md`

## Summary

把面向本地 AI agent 的 Weft 任务创作面重构为 **Skill 优先**：交付一个随仓库分发的 `.claude/skills/weft-task-authoring/` Skill（渐进披露，正文即 golden path 知识），指挥已有 `dw` CLI 完成创作→本地跑→push 全程；MCP 保留可跑但文档降级为自动化/查询可选面。配套硬化 `dw` CLI 在 golden path 上的毛糙处（退出码歧义、datasource 校验、baseline 提示、**两套认证合并为单一凭据**），清理服务端-AI 拆除后的断脚本与残留 spec，并正式修订 constitution 原则 IV 措辞使治理与现实一致。golden path 双层验证：CI 确定性脚本化 E2E（H2、无 key）+ 真 LLM 本地 agent 验收仪式（不进 CI）。

## Technical Context

**Language/Version**: Go 1.2x（`dw` CLI）、Java 25 / Spring Boot 4（后端认证）、Markdown（Skill）；**无前端改动**。

**Primary Dependencies**: 既有 `dw` CLI（`cli/client`、`cli/run`、`cli/sync`）、后端 `JwtAuthFilter` + `CliController`、Claude Code Skills（Agent Skills 开放标准）。

**Storage**: N/A —— 零 schema 变更、零持久化实体改动。E2E 用 H2 in-memory profile。

**Testing**: Go `go test`（CLI 单测 + golden path 脚本化链）、JUnit5 + WebTestClient `@ActiveProfiles("h2")`（后端认证合并）、Skill 一致性 lint（校验 Skill 引用的 dw 命令/flag 真实存在）、真 LLM 验收仪式（手工/按需，文档化）。

**Target Platform**: 开发者本地（Linux/macOS/WSL2）+ 后端 JVM。

**Project Type**: CLI + backend（无 web 前端）。

**Performance Goals**: N/A（体验/正确性特性，非性能特性）。

**Constraints**: 不触 PolicyEngine L0–L4 授权/审计内核、不改同步 API 请求/响应体契约与端点路径、不改前端、不删/改 MCP 工具代码、不恢复 workhorse 二进制引导。**唯一例外**：FR-015 dw 认证合并可改服务端认证机制（统一凭据校验），不触授权/审计、不改 API 体契约。

**Scale/Scope**: 单 Skill 包 + ~5 处 dw CLI 局部硬化 + 1 处后端认证统一 + 文档/残留清理 + 双层 E2E。

## Constitution Check

*GATE: 必须在 Phase 0 前通过；Phase 1 设计后复检。*

| 原则 | 评估 | 结论 |
|------|------|------|
| **I. Files-First** | Skill 正文显式文档化文件契约（project/_folder/*.task/*.flow/脚本体/tags），强化"任务即文件"。 | PASS（强化） |
| **II. Server is Source of Truth** | golden path 用 pull/push；认证合并只改认证机制，不改 push 幂等覆盖+快照语义、不改 diff 可感知差异。 | PASS |
| **III. Two-Legged Debugging（NON-NEGOTIABLE）** | dw run 退出码硬化是**忠实报告 exit code** 这一原则本身的兑现；复用 worker 子进程执行器，**不**分叉第二执行引擎。退出码细分须由测试钉住 fidelity。 | PASS（兑现 fidelity 不变量） |
| **IV. AI Lives in the Local Agent（NON-NEGOTIABLE）** | Skill-first + BYO-agent 完全对齐"AI 归本地"；不恢复服务端 AI/workhorse 桥接；不损运行态观测与调度内核。**本特性正式修订原则 IV 措辞**（through MCP → through MCP and/or dw CLI，Skill 为知识层），守不可让渡内核不变（见下"治理修订说明"）。 | PASS（含有意修宪） |
| **V. Reuse the Kernel** | 认证合并复用既有 `JwtAuthFilter`/`CliController`，非重写；所有写操作仍过 PolicyEngine 写闸门 + 审计，不因来自 agent 而放行。 | PASS |

**治理修订说明**：原则 IV 字面"operating the platform through MCP"与 Skill+dw 主创作面有张力。经澄清（spec Q1）用户批准**正式修订**该措辞为"through MCP and/or the dw CLI"，并记 Skill 为本地 agent 知识层。修订**仅扩接口表述**，不动不可让渡内核三条（服务端无 AI 大脑、AI 由本地 agent 提供、拆除不得损运行态观测/调度内核）。属有意纳入的治理同步（FR-021a），非范围外溢。

**结论**：无违规需 Complexity Tracking 记录。修宪为已批准的治理同步项。

## Project Structure

### Documentation (this feature)

```text
specs/015-agent-authoring-skill/
├── spec.md              # 已完成（含 5 条 Clarifications）
├── plan.md              # 本文件
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出（本特性"实体"≈ 知识资源/CLI 行为/文档）
├── contracts/           # Phase 1 输出（Skill 契约 + 认证合并契约 + dw 退出码契约）
├── quickstart.md        # Phase 1 输出（golden path 双层验证指南）
└── checklists/
    └── requirements.md  # 已完成（16/16）
```

### Source Code (repository root)

```text
.claude/skills/weft-task-authoring/        # 新增：Skill 包
├── SKILL.md                               #   frontmatter(name/description/allowed-tools) + golden path 正文
├── file-contract.md                       #   一页文件契约速查（支持文件）
└── examples/                              #   可仿写最小模板
    ├── sample-task.task.yaml + sample-task.sql
    └── sample-flow.flow.yaml

cli/                                        # dw CLI 硬化（Go）
├── client/client.go                        #   认证：统一单一凭据头注入（合并 Bearer/X-DW-Token）
├── main.go                                 #   认证：task/logs 改走统一凭据（移除手拼 X-DW-Token）
├── run/local.go                            #   退出码：环境错 vs 任务失败 分离（ExitEnvironment 新码）
├── run/datasource.go                       #   datasources.local.yaml 提前 schema 校验
└── sync/push.go                            #   baseline 过期提示可读化

backend/dataweave-api/src/main/java/com/dataweave/api/
├── infrastructure/JwtAuthFilter.java       #   认证合并：/api/cli 统一接受单一凭据
└── interfaces/CliController.java            #   认证合并：requireToken 接受统一凭据（兼容路径）

docs/architecture.md                        # MCP 重新定位 + 创作主路径=Skill+dw 取舍
CLAUDE.md                                   # MCP & CLI 小节定位更新 + SPECKIT 标记指向 plan
.specify/memory/constitution.md             # 原则 IV 措辞修订（FR-021a）

# 删除（git rm）
deploy/workhorse/serve-local.sh             # 断脚本（引用已删文件）
deploy/workhorse/merge-config.py            # 断脚本配套
openspec/specs/workhorse-supervisor/spec.md # 残留 spec（描述已删代码）

# 测试
cli/sync/  + cli/run/                        # golden path CI 确定性脚本化 E2E（复用 sync_e2e_test.go 地基）
backend/dataweave-api/src/test/...           # 认证合并 @ActiveProfiles("h2") 测试
```

**Structure Decision**: CLI（Go）+ backend（Java）双语改动 + 一个 Markdown Skill 包 + 文档/治理清理。无前端。Skill 放 `.claude/skills/`（与现有 `openspec-*`/`speckit-*` kebab-case 约定一致），随仓库 clone 即得。

## Complexity Tracking

> 无 Constitution 违规需要记录。原则 IV 措辞修订为用户已批准的治理同步项（FR-021a），守不可让渡内核不变，不构成违规。
