# Feature Specification: Weft 任务创作 Skill + dev-loop 体验收口

**Feature Branch**: `015-agent-authoring-skill`

**Created**: 2026-06-29

**Status**: Draft

**Input**: User description: "轨道 2（agent 体验）：用 Skill 取代 MCP 作为本地 AI agent 开发 Weft 任务的创作主入口（MCP 工具量大吃上下文、Skill 渐进披露省上下文），硬化 dw CLI 主路径毛糙处，提供一条文档化且 E2E 验证的 golden path，并清理服务端-AI 拆除后残留的断引导与残留 spec。"

## 背景与动机

Weft 的产品定位是"开发者本地用 AI 编程 agent 开发任务/任务流，pull/push 往返服务器治理与运行"，核心 tasks-as-code 闭环（文件契约 / pull-push API / CLI runtime / MCP 重塑 + 011/012/013/014）已落地并 E2E 验证。但**面向本地 AI agent 的创作体验**仍有三处断口：

1. **本地 agent 引导是断的**：`deploy/workhorse/serve-local.sh` 仍引用服务端-AI 拆除（commit `d8b53d7`）时删掉的 `fetch-bin.sh` / `config.yaml` / `mcp.json`，从干净 clone 出发照脚本根本无法启动一个本地 agent。
2. **MCP 作为创作主入口太重**：26 个 MCP 工具的 JSON Schema 是常驻上下文的——一个 agent 还没开始写任务，上下文就被工具定义占掉一大块。对"创作任务"这种以**知识+流程**为主、动作可由 CLI 承载的场景，常驻工具面是错配。
3. **没有可信的 golden path**：缺一条文档化、可复现、E2E 验证的"用 AI agent 从零写任务→本地跑→push 上线"路径，头条卖点无法被证明，也无法对外演示。

本特性把 agent 创作面重构为 **Skill 优先**：交付一个随仓库分发的 Weft 任务创作 Skill（Claude Code Skills 渐进披露——只 `description` 常驻、正文按需加载），Skill 正文即 golden path 知识，指挥已有的 `dw` CLI 完成动作；MCP 保留可跑但从"创作主入口"降级为"自动化/查询的可选面"。同时硬化 `dw` CLI 在该 golden path 上的毛糙处，并清理拆除后残留的断引导与残留 spec。

本特性形态是 **BYO-agent**（自带 agent）——Skill 对任何 Claude Code 兼容的本地 agent 生效，不把 Weft 绑定到某个特定 agent 二进制，符合 constitution 原则 IV「AI Lives in the Local Agent」。

## Clarifications

### Session 2026-06-29

- Q: Skill-first 降级 MCP 与 constitution 原则 IV「operating the platform through MCP」是否冲突，如何处置？ → A: 不触原则 IV 不可让渡内核（服务端无 AI 大脑、AI 归本地 agent）；本特性顺带**正式修订**原则 IV 措辞，把「through MCP」扩为「through MCP and/or the dw CLI」，并定位 Skill 为本地 agent 的知识层，使治理文档与 Skill+dw 主创作面的现实一致。
- Q: dw CLI 两套认证（X-DW-Token vs Bearer JWT）是仅文档化标注还是真正合并？ → A: **真正合并**为单一 dw 凭据，所有 dw 命令统一一套认证。合并范围限于**认证机制**（服务端 CLI/项目/ops 端点的认证校验接受统一凭据），MUST NOT 触碰 PolicyEngine L0–L4 授权内核与审计，MUST NOT 改动同步 API 的请求/响应体契约与端点路径。
- Q: 残留物（断脚本 + workhorse-supervisor 残留 spec）删除还是归档？ → A: **全部删除**（git rm），不留 archive：`deploy/workhorse` 断 tracked 脚本与 `openspec/specs/workhorse-supervisor/spec.md` 一律删除；本地 gitignore 的二进制/配置不动。
- Q: golden path E2E 的后端环境与「agent」步如何承载？ → A: 后端走 **H2 in-memory**（零外部依赖、干净 clone 可复现）；golden path 验收由 **真 LLM 本地 agent 真驱动**（workhorse + 真实 LLM 配置，开发者本地提供），加载 Skill 后实际完成创作→跑→push，以忠实证明"用 AI agent 从零写任务"的主张。
- Q: CI 确定性与真 LLM 验收如何分层？ → A: **双层**。① CI 确定性层：脚本化 dw 命令链（遵循 Skill 同一步骤序、H2、无 key）进 CI，防 golden path 管路回归。② 真 LLM 验收层：真本地 agent 加载 Skill 真驱动（开发者提供配置），作为特性验收 + 按需演示仪式跑，**不进 CI**。二者不互斥。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Skill 驱动"从零写任务并上线"的 golden path (Priority: P1)

一个开发者在本地用其 AI 编程 agent（Claude Code 或兼容者）开发一个新的 Weft 任务。agent 在仓库里加载到 Weft 任务创作 Skill，据其指引理解文件契约、写出任务定义与脚本、本地真跑验证、对账差异，最终 push 上线。整个过程 agent 无需常驻一大批平台工具定义，所需创作知识在 Skill 被触发时才加载。

**Why this priority**: 这是本特性的脊梁与头条价值——把"用 AI agent 开发 Weft 任务"从"口号"变成"可复现、可演示、被证明能跑通"的事实。Skill 与 dw 硬化都服务于这条路径。是 MVP 的核心。

**Independent Test**: 从一个干净工作副本出发，让一个本地 agent 仅凭 Skill 指引（不预读源码）完成 `pull → 写任务 → dw run 本地跑通 → dw diff → dw push → dw run --test`，每一步都得到符合预期的反馈，任务最终在服务器侧可见。

**Acceptance Scenarios**:

1. **Given** 开发者在 Weft 仓库工作副本中、本地 agent 可加载项目级 Skill，**When** 开发者表达"创建一个新任务"，**Then** agent 自动加载 Weft 任务创作 Skill，并据其呈现文件契约结构与 dev-loop 步骤，无需开发者手工粘贴说明。
2. **Given** Skill 已加载，**When** agent 按 Skill 指引写出一个最小任务（定义文件 + 脚本体），**Then** 该任务文件结构合法（符合文件契约），`dw run` 能在本地真跑通并输出执行日志。
3. **Given** 本地已写好任务，**When** agent 执行 `dw diff` 再 `dw push`，**Then** diff 如实显示新增、push 成功落库，agent 能从返回中明确判定结果（成功/挂起待审批/拒绝），不把"挂起"误判为失败。
4. **Given** golden path 的每一步命令，**When** 运行端到端验证，**Then** 从干净态一次跑通，过程无需依赖任何已被删除的引导脚本或缺失文件。

---

### User Story 2 - Skill 让文件契约对 agent 无歧义可仿写 (Priority: P1)

agent 要"从零写一个任务"，必须无歧义地理解 Weft 文件契约：项目/目录标记文件、任务定义、任务流定义、脚本体、标签、params 取值与占位符语法、datasource 逻辑名从哪查、任务流 nodes/edges 一致性。Skill 正文 + 支持文件（可仿写的最小示例任务与任务流、一页速查）提供这套知识，使 agent 不必逆向源码即可正确创作。

**Why this priority**: 与 US1 并列最高——golden path 的"写"这一步成立的前提就是文件契约可被 agent 无歧义理解。勘查显示当前 params 格式、datasource 枚举、占位符语法等对 agent 是"盲区"，必须由 Skill 知识补上。

**Independent Test**: 给 agent 一个自然语言任务诉求，仅凭 Skill 知识，agent 产出的任务/任务流文件首次即结构合法（字段齐全、引用一致、占位符语法正确），无需多轮试错。

**Acceptance Scenarios**:

1. **Given** Skill 正文与支持文件，**When** agent 需要写任务定义，**Then** 它能从 Skill 获知必填/可选字段、params 取值约定、占位符 `{{...}}` 语法及其语义，产出文件字段合法。
2. **Given** 任务需要绑定数据源，**When** agent 需要填 datasource 逻辑名，**Then** Skill 明确告知"可用 datasource 逻辑名从何处查得"（本地配置 / dw 命令），agent 不臆造名字。
3. **Given** agent 写任务流，**When** 它定义 nodes 与 edges，**Then** Skill 指引其保证 edges 的 from/to 引用真实存在的 node key，产出的任务流引用自洽。
4. **Given** 一个最小示例任务/任务流模板随 Skill 提供，**When** agent 仿写，**Then** 仿写结果与示例同构、可被 `dw run` / `dw push` 接受。

---

### User Story 3 - dw CLI 在 golden path 上的毛糙处被硬化 (Priority: P2)

开发者（或代其操作的 agent）在 golden path 上使用 `dw` CLI 时，不应被环境配置与认证细节绊倒。本故事**只**硬化 golden path 实际触及的毛糙处：两套认证的清晰化、本地真跑的环境探测错误信息、退出码语义、数据源本地配置的可用性、基线过期的可读提示。

**Why this priority**: 次高——它是 US1 golden path 顺滑的保障，但本身是局部打磨而非新能力。严格限定在主路径触及范围内（YAGNI），不外溢到 lineage/审批/backfill。

**Independent Test**: 在缺失/错配各前置条件（缺 worker classpath、用错认证、数据源配置缺字段、基线过期）时分别运行对应 dw 命令，得到的错误信息能让使用者**自行定位并修复**，且退出码能区分"环境/前置错误"与"任务执行失败"。

**Acceptance Scenarios**:

1. **Given** 本地真跑缺少 worker 执行所需类路径，**When** 运行 `dw run`，**Then** 错误信息明确指出缺什么、如何设置，而非沉默或含糊报错。
2. **Given** 一个写操作命令与一个只读命令分别需要不同认证（其一为 CLI token、其一为 Bearer JWT），**When** 使用者查看命令帮助或在 Skill 中查阅，**Then** 每条命令用哪套认证被清楚标注，不需试错。
3. **Given** 本地真跑因"环境/前置缺失"失败 vs 因"任务本身执行失败"失败，**When** 命令退出，**Then** 两类失败以**不同退出码**区分，可被脚本/agent 编程判别。
4. **Given** 数据源本地配置文件，**When** 使用者据 Skill/脚手架填写后运行真跑，**Then** 缺字段/格式错在运行前即被基本校验提示，而非运行中途以底层异常暴露。
5. **Given** push 时本地基线已过期，**When** 运行 `dw push`，**Then** 提示清楚说明"基线过期"及推荐处置（如何安全覆盖或重新拉取），而非裸抛冲突。

---

### User Story 4 - MCP 重新定位为可选面，创作主路径走 Skill (Priority: P2)

平台维护者与未来贡献者需要清楚知道：Weft 的 agent 创作主路径是 Skill + `dw` CLI，MCP 工具面是"自动化/查询"的**可选**面而非创作入口。本故事**不删、不改** MCP 工具代码，仅在架构文档与项目导航文档中重新定位 MCP，并写明"创作走 Skill 以避免常驻上下文膨胀"的取舍理由。

**Why this priority**: 中等——它把战略决定固化进文档，防止后续贡献者误把 MCP 当创作主入口、继续往常驻工具面堆东西。零运行期改动。

**Independent Test**: 阅读架构文档与项目导航文档，能明确读到"创作主路径=Skill+dw、MCP=自动化/查询可选面"的定位与上下文成本取舍理由；MCP 工具本身行为不变、仍可调用。

**Acceptance Scenarios**:

1. **Given** 维护者查阅架构/导航文档的 agent 接入小节，**When** 阅读 agent 如何开发任务，**Then** 能看到"主路径走 Skill + dw、MCP 为可选自动化/查询面"的清晰定位与取舍理由。
2. **Given** 本特性完成，**When** 调用任一既有 MCP 工具，**Then** 其行为与本特性前完全一致（零代码改动、零契约变更）。

---

### User Story 5 - 清理服务端-AI 拆除后的残留断口 (Priority: P3)

开发者在仓库里仍会撞见拆除后残留的死肉：`deploy/workhorse/` 下引用了已删文件的断引导脚本（照它走起不来 agent），以及 `openspec/specs/workhorse-supervisor/spec.md`——一份描述已被 commit `af55cf1` 删除的 supervisor 代码的残留 spec。本故事清理这两处，让仓库诚实反映"Skill + BYO-agent"的当前形态。

**Why this priority**: 最低——纯整洁与诚实性收口，无新能力。但消除"看着像有引导/像有能力，实则断的/已删的"误导。

**Independent Test**: 清理后，仓库中不再存在引用已删文件的断引导脚本；不再存在描述已删代码的残留 spec；后端完整本地构建通过；全仓搜索无指向已删/已移除物的悬空引用。

**Acceptance Scenarios**:

1. **Given** `deploy/workhorse/serve-local.sh` 引用了已删的 `fetch-bin.sh`/`config.yaml`/`mcp.json`，**When** 本特性清理完成，**Then** 该断引导被移除或归档，仓库中无照之无法启动的死脚本，且无指向它的悬空引用。
2. **Given** `openspec/specs/workhorse-supervisor/spec.md` 描述的是已删代码，**When** 本特性清理完成，**Then** 该残留 spec 被归档或删除，不再以"当前能力规范"的形式误导读者。
3. **Given** 清理完成，**When** 运行后端完整本地构建与全仓悬空引用检查，**Then** 构建通过、悬空引用为 0。

---

### Edge Cases

- **Skill 与 CLI 漂移**：Skill 正文引用的 `dw` 子命令/参数若与 CLI 实际不符，agent 会被误导。须有一致性校验把"Skill 引用的命令/flag 必须真实存在"卡住，杜绝文档-实现漂移。
- **干净态可复现**：golden path E2E 必须从**干净工作副本**出发，不得依赖任何已删引导脚本或开发者机器上残留的二进制/配置，否则"可复现"是假的。
- **挂起≠失败的误判**：含删除或高危的 push/写操作会被权限闸门挂起（待审批），agent 若把挂起当失败会错误重试或回滚。Skill 须明确教 agent 区分 EXECUTED/PENDING_APPROVAL/REJECTED 三态。
- **数据术语不被"翻译"**：Skill 文档化文件契约时，cron/DAG/SLA/lineage 等数据术语按规约保持英文，不在中文叙述中被意译成易混表述。
- **认证混用**：golden path 同时用到两套认证（CLI token 与 Bearer JWT），Skill 与 CLI 帮助须把"哪条命令用哪套"讲到不需试错，避免 401 折返。
- **退出码语义回归**：拆分退出码语义时不得破坏既有脚本对成功（0）的判定，仅细分非零失败的子类。
- **残留清理误伤**：删 `deploy/workhorse` 断引导前须确认无活跃路径仍引用它（构建/CI/文档）；归档残留 spec 时确认其确实描述已删代码而非现存能力。

## Requirements *(mandatory)*

### Functional Requirements

#### US1 — golden path（脊梁）
- **FR-001**: 系统 MUST 提供一条文档化的 golden path，覆盖 `pull → 创作任务 → 本地真跑 → 差异对账 → push 上线 → 测试运行` 全程，作为"用 AI agent 开发 Weft 任务"的标准路径。
- **FR-002**: golden path MUST 被**端到端验证**为从干净工作副本一次可跑通，且**不依赖**任何已删除的引导脚本或缺失文件。
- **FR-003**: golden path 上每一步 MUST 给 agent 可据以判定的明确反馈（成功 / 挂起待审批 / 拒绝 / 本地跑通与否），使 agent 不需臆测下一步。

#### US1/US2 — Weft 任务创作 Skill
- **FR-004**: 系统 MUST 交付一个**随仓库分发**的 Weft 任务创作 Skill，使任何在该仓库工作副本中的 Claude Code 兼容本地 agent 都能加载它；clone 仓库即获得该 Skill，无需额外安装步骤。
- **FR-005**: Skill MUST 采用渐进披露——仅其触发描述常驻 agent 上下文，创作知识正文在 Skill 被触发/调用时才加载，使"尚未创作任务时"的常驻上下文成本最小。
- **FR-006**: Skill 触发描述 MUST 能让 agent 在用户表达"创建/编辑 Weft 任务或任务流、或本地跑/push"等意图时自动加载该 Skill。
- **FR-007**: Skill 正文 MUST 文档化完整的文件契约：项目标记文件、目录标记文件、任务定义文件、任务流定义文件、脚本体独立文件、标签文件，含各自结构与字段语义。
- **FR-008**: Skill 正文 MUST 说明 params 取值约定、占位符 `{{...}}` 语法及其语义，使 agent 产出的占位符用法合法。
- **FR-009**: Skill 正文 MUST 告知 agent "可用 datasource 逻辑名从何处查得"，使 agent 绑定数据源时不臆造名字。
- **FR-010**: Skill 正文 MUST 指引 agent 保证任务流 nodes/edges 引用自洽（edges 端点指向真实存在的 node key）。
- **FR-011**: Skill 正文 MUST 教 agent 读懂写操作/同步的权限闸门反馈三态（EXECUTED / PENDING_APPROVAL / REJECTED），明确"含删除/高危 → 挂起待审批 ≠ 失败"。
- **FR-012**: Skill MUST 附带可仿写的最小示例任务 + 示例任务流模板，以及一页文件契约速查作为支持文件。
- **FR-013**: Skill MUST 声明其在激活时需要的工具权限（至少覆盖运行 `dw` 命令与读写本地任务文件所需）。

#### US3 — dw CLI 硬化（严格限 golden path 缺口）
- **FR-014**: 本地真跑命令在缺少 worker 执行所需类路径时 MUST 给出可定位的错误信息（缺什么、如何设置），而非沉默或含糊报错。
- **FR-015**: 系统 MUST 把 dw CLI 的两套认证（CLI token 与 Bearer JWT）**合并为单一 dw 凭据**，使所有 dw 命令统一一套认证、不再因用错凭据而试错型 401；Skill 与 CLI 帮助 MUST 据合并后的单一认证更新说明。合并范围限于**认证机制**：服务端 CLI/项目/ops 端点的认证校验 MUST 接受该统一凭据；MUST NOT 改动 PolicyEngine L0–L4 授权与审计，MUST NOT 改动同步 API 的请求/响应体契约与端点路径。
- **FR-016**: 本地真跑 MUST 以**不同退出码**区分"环境/前置缺失失败"与"任务本身执行失败"，且 MUST NOT 改变"成功=0"的既有语义。
- **FR-017**: 系统 MUST 为数据源本地配置提供脚手架/示例，并在真跑前对其做基本校验（缺字段/格式错在运行前提示）。
- **FR-018**: push 遇本地基线过期时 MUST 给出可读提示，说明"基线过期"及推荐处置，而非裸抛冲突。
- **FR-019**: 本故事 MUST NOT 新增 lineage/审批/backfill 相关的 dw 命令（范围严格限定在 golden path 触及的毛糙处）。

#### US4 — MCP 重新定位
- **FR-020**: 系统 MUST 在架构文档与项目导航文档中把 agent 创作主路径定位为 Skill + `dw` CLI，把 MCP 工具面定位为"自动化/查询的可选面"，并写明"创作走 Skill 以避免常驻上下文膨胀"的取舍理由。
- **FR-021**: 本特性 MUST NOT 删除或修改任何 MCP 工具代码——MCP 工具行为与契约保持与本特性前完全一致。
- **FR-021a**: 系统 MUST 修订 constitution 原则 IV 的措辞，把「operating the platform through MCP」扩为「through MCP and/or the dw CLI」，并记载 Skill 为本地 agent 的知识层；修订 MUST 保持原则 IV 不可让渡内核（服务端无 AI 大脑、AI 归本地 agent、拆除不得损伤运行态观测与调度内核）不变。

#### US5 — 残留清理
- **FR-022**: 系统 MUST **删除（git rm）** `deploy/workhorse/` 下引用已删文件的断 tracked 脚本（`serve-local.sh`/`merge-config.py` 等），使仓库中不存在照之无法启动的死脚本，并清理指向它的悬空引用；本地 gitignore 的二进制/配置不在删除范围。
- **FR-023**: 系统 MUST **删除（git rm）** `openspec/specs/workhorse-supervisor/spec.md`（描述已删 supervisor 代码的残留 spec），不保留 archive 副本，使其不再以"当前能力规范"形式存在。
- **FR-024**: 残留清理后，后端完整本地构建 MUST 通过，且全仓对已删/已移除物的悬空引用 MUST 为 0。

#### 测试与防漂移
- **FR-025**: 系统 MUST 提供 golden path 的 **CI 确定性端到端测试**——脚本化 dw 命令链遵循 Skill 同一步骤序、跑在 H2 in-memory 后端+worker、**不依赖 LLM key**，证明 FR-001/FR-002 的路径管路从干净态可跑通、防回归（复用既有 CLI runtime / 同步 E2E 测试地基）。
- **FR-025a**: 系统 MUST 提供并文档化 golden path 的 **真 LLM 验收仪式**——由真本地 agent（workhorse + 真实 LLM 配置，开发者本地提供）加载 Skill 真驱动完成创作→跑→push，作为特性验收 + 按需演示证据；该仪式 MUST NOT 作为 CI 必跑项（无 key 环境照常绿）。
- **FR-026**: 系统 MUST 提供 dw 硬化项（新错误信息 / 退出码细分 / 数据源校验）的自动化测试。
- **FR-027**: 系统 MUST 提供 Skill 一致性校验，确保 Skill 正文引用的 dw 子命令/参数在 CLI 中真实存在，防止文档-实现漂移。

#### 全局约束
- **FR-028**: 本特性 MUST NOT 引入新的服务端运行期能力、MUST NOT 改动同步 API（pull/push/diff）的请求/响应体契约与传输形态、MUST NOT 触碰调度内核与权限/审计（PolicyEngine L0–L4 授权 + 审计）内核、MUST NOT 改动前端。**唯一例外**：FR-015 的 dw 认证合并可改动服务端端点的**认证机制**（authentication），但仅限统一凭据校验、不触授权与审计、不改 API 请求/响应体契约。
- **FR-029**: 本特性 MUST NOT 恢复 workhorse 二进制的捆绑引导（形态固定为 BYO-agent，符合 constitution 原则 IV）。

### Key Entities

本特性主要新增/修改的是开发者与 agent 面的**知识资源、CLI 行为与文档**，几乎不触持久化实体：

- **Weft 任务创作 Skill**：随仓库分发的 Skill（触发描述 + 创作知识正文 + 示例模板与速查支持文件 + 工具权限声明）。本特性新增。
- **示例任务 / 任务流模板**：随 Skill 提供的最小可仿写样例。本特性新增。
- **dw CLI 行为面**：认证标注、错误信息、退出码语义、数据源校验、基线提示。本特性局部硬化，不改命令契约/传输形态。
- **agent 接入文档定位**：架构/导航文档中关于"创作主路径=Skill+dw、MCP=可选面"的记载。本特性新增/修改。
- **残留物**：断引导脚本与残留 supervisor spec。本特性移除/归档。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: golden path 双层验证均成立——① CI 确定性 E2E（脚本化、H2、无 key）通过率 100%、防回归；② 真 LLM 验收仪式中，一个真本地 agent 仅凭加载的 Weft 任务创作 Skill（不预读平台源码）从干净工作副本完成"从零写任务并 push 上线"全程成功。
- **SC-002**: golden path 从干净工作副本一次跑通，全程零依赖任何已删除引导脚本或缺失文件。
- **SC-003**: agent 创作任务时**尚未触发 Skill** 的常驻上下文中，不含 Weft 创作知识正文与平台工具全量定义——创作知识仅在 Skill 触发时加载（渐进披露成立）。
- **SC-004**: agent 据 Skill 写出的任务/任务流文件，结构合法（字段齐全、引用自洽、占位符语法正确）首次通过率达标，无需多轮试错即可被 `dw run`/`dw push` 接受。
- **SC-005**: golden path 触及的 dw 毛糙处（worker 类路径缺失、认证混用、退出码歧义、数据源配置缺陷、基线过期）在各自失败场景下，使用者凭错误信息即可自行定位修复；"环境失败"与"任务失败"退出码可区分。
- **SC-006**: 架构/导航文档清楚记载"创作主路径=Skill+dw、MCP=自动化/查询可选面"及上下文成本取舍；MCP 工具行为零变更。
- **SC-007**: 仓库中不再存在引用已删文件的断引导脚本与描述已删代码的残留 spec；残留清理后后端完整构建一次通过、悬空引用为 0。
- **SC-008**: Skill 一致性校验通过——Skill 正文引用的每个 dw 子命令/参数在 CLI 中真实存在。
- **SC-009**: 本特性全程零同步 API 契约变更、零调度/权限内核改动、零前端改动、零 MCP 工具代码改动。

## Assumptions

- **agent 平台**：目标本地 agent 为 Claude Code 或遵循 Agent Skills 开放标准的兼容实现；Skill 以项目级 `.claude/skills/` 形态随仓库分发，clone 即得。
- **dw CLI 已覆盖主路径动作**：`dw` 现有 `pull/push/diff/run/run --test/task/logs` 已覆盖 golden path 所需动作；本特性只硬化毛糙处，不新增主路径之外的命令。
- **本地真跑前置**：本地真跑复用既有 worker 执行器（Python/SQL/Shell），其 runtime 前置（如 worker classpath、python3）由使用者按 Skill 指引准备；本特性只改善其错误可定位性，不改执行器本身。
- **MCP 仍可用**：MCP 工具面保持现状可用，供偏好工具调用的自动化/查询场景；本特性仅做文档定位，不动其代码。
- **残留物确为死肉**：`deploy/workhorse` 断引导与 `workhorse-supervisor` 残留 spec 经勘查确认分别引用已删文件、描述已删代码；清理前再次确认无活跃路径引用。
- **golden path 范围**：golden path 限定为 dw 可覆盖的主路径（写/跑/对账/push/测试运行）；lineage/审批/backfill 等 richer ops 不纳入本特性，由 Skill 指引使用者按需走 MCP 或留待后续。
- **真 LLM 验收依赖**：真 LLM 验收仪式（FR-025a）依赖开发者本地提供可用的 workhorse + 真实 LLM 配置（key/endpoint）；该仪式不进 CI，无 key 环境下 CI 仍靠确定性层（FR-025）保持绿。
- **constitution 修订在范围内**：本特性顺带修订 constitution 原则 IV 措辞（FR-021a），守其不可让渡内核不变；这是有意纳入的治理同步，非范围外溢。
