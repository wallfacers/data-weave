<!--
SYNC IMPACT REPORT
==================
Version change: 1.1.0 → 1.2.0
Bump rationale (MINOR): Amend Principle IV to broaden agent operating surface from
"through MCP" to "through MCP and/or the dw CLI" and document Skill as the local
agent's knowledge layer. The non-negotiable kernel (no server-side AI brain, AI
lives in local agent, teardown must not damage observability/scheduling) is unchanged.
This is a governance sync to reflect the Skill-first authoring reality (015-agent-authoring-skill).

Modified principles:
  IV. AI Lives in the Local Agent — operative clause broadened ("through MCP" →
       "through MCP and/or the dw CLI"); Skill documented as agent knowledge layer;
       non-negotiable kernel (3 items) preserved unchanged.
Added principles: (none)
Removed principles/sections: (none)

Templates requiring updates:
  ✅ .specify/memory/constitution.md (this file)
  ✅ plan-template.md — Constitution Check for IV now accepts Skill+dw as valid
     agent operating surface; no structural template change required.
  ✅ spec-template.md — unaffected
  ✅ tasks-template.md — unaffected

Follow-up TODOs: none deferred. RATIFICATION_DATE unchanged (2026-06-26).
Prior 1.1.0 report: amended Principle III (phased runtime clause).
-->

# Weft Constitution

Weft is a **Tasks-as-Code** platform. Data tasks and task-flows are developed like
local code: they live as plain-text files in a local git working copy, are authored
and debugged by the developer's local AI coding agent (Claude Code / Codex), and are
pushed to a server that governs and runs them. This constitution is the highest
authority governing the transformation and all of its sub-features.

The transformation is delivered as five ordered sub-features, each with its own spec:
**A** server-side AI teardown → **B** file-definition contract → **C** pull/push API →
**D** CLI + local runtime → **E** MCP tool reshape. The vision总纲 is
`specs/005-weft-pivot/spec.md`; every sub-spec MUST conform to the principles below.

## Core Principles

### I. Files-First (文件优先)

Project, catalog, task, and workflow MUST each have a deterministic plain-text on-disk
representation. The local directory tree IS the catalog tree (folder hierarchy = catalog
hierarchy). A task's metadata and its script execution body MUST be stored separately.
All definitions MUST be human- and AI-agent-friendly: readable, diff-able, and
code-reviewable as plain text.

Rationale: "Tasks-as-Code" only holds if every artifact is a file a developer or an AI
coding agent can read, edit, diff, and review — no opaque DB-only configuration.

### II. Server is the Source of Truth (服务器为治理真相源)

The server MUST remain the governance source of truth: it owns tenant/project isolation
and version-snapshot governance. The local copy is a working copy only. Sync is git-style
`pull` (fetch a project as files) and `push` (submit files back). `push` MUST be an
idempotent overwrite that generates a new version snapshot. Bidirectional sync and
conflict-merge are FORBIDDEN. Before an overwrite, the developer MUST be able to perceive
the local-vs-server difference. Every `pull`/`push`/run and MCP operation MUST be subject
to isolation; out-of-scope access MUST be rejected.

Rationale: Local-only is unreliable; governance, isolation, and immutable versioning must
stay server-side. One authoritative direction (push overwrites + snapshots) avoids the
complexity and footguns of two-way merge.

### III. Two-Legged Debugging (本地两条腿调试) — NON-NEGOTIABLE

The CLI MUST provide a runtime that **really executes** a task on the developer's machine,
reusing the platform's SQL/Shell executor semantics (it MUST NOT fork a divergent second
execution engine), connecting to local/dev datasources with output streamed straight to the
terminal and exit codes faithfully reported. The CLI MUST also support submitting a task in
TEST mode to the server, with run logs streamed back to the local terminal.

**Phased implementation** (amended v1.1.0): Execution fidelity MAY be achieved by reusing
the platform's **actual executor as a local subprocess** — code-level identity, the
strongest form of "reuse the executor semantics," at the cost of a local JVM dependency.
This is the **sanctioned approach for the current phase** and overrides 005 总纲 FR-010's
"Go-native / contract-level / lightweight" wording. A **Go-native lightweight runtime**,
aligned to the server executor by golden contract tests, is the **future target** but is NOT
required now. Whichever form, exit-code / stdout-stderr split / timeout-abort / datasource
driver-loading behavior MUST be **identical** to the server executor and MUST be verified by
tests — fidelity is the invariant; the implementation vehicle is phased.

Rationale: The core feel of "like writing local code" is fast local runs; environment
fidelity is then closed by server TEST instances. Reusing the real executor gives zero
semantic drift immediately; a leaner Go-native runtime is an optimization to pursue once the
contract is pinned by tests, not a precondition for shipping local debugging.

### IV. AI Lives in the Local Agent (AI 归位本地) — NON-NEGOTIABLE

The server MUST NOT embed an AI brain. The chat cockpit, AG-UI protocol, workhorse bridge,
IntentRouter, and proactive-notify/findings MUST be removed cleanly (no active code or
dependency residue). AI capability is provided exclusively by the developer's local coding
agent operating the platform through MCP and/or the dw CLI.

**Agent 知识层**：面向本地 agent 的任务创作知识以 **Skill**（`.claude/skills/weft-task-authoring/`）
形态随仓库分发——渐进披露（仅 `description` 常驻上下文，正文按需加载），Skill 正文即 golden path
单一真相；Skill 不绑定特定 agent 二进制（BYO-agent）。

**不可让渡内核**（修订不改以下三条）：
1. 服务端无 AI 大脑（不嵌入推理/决策/agent 逻辑）。
2. AI 能力由开发者本地 agent 提供（Claude Code / Codex 或兼容者）。
3. 拆除不得损伤运行态观测（ops overview / metrics / run logs / DAG instance views）与调度内核。

Teardown MUST NOT damage run-time observability or the scheduling kernel.

Rationale: The AI is already in the developer's editor; a second server-side agent brain is
redundant and fragmented. Removing it nets a leaner, un-polluted platform. Skill + dw CLI
provides a leaner, context-efficient authoring surface than maintaining a large set of MCP
tool schemas in the agent's permanent context window.

### V. Reuse the Kernel (内核复用而非重写)

The transformation MUST reuse existing kernels rather than rewrite them: the scheduler
(peer master + SKIP LOCKED + cron guard), version snapshots, the SQL/Shell executors, the
PolicyEngine L0–L4 write gate, and the MCP server framework. Every write operation issued
via CLI or MCP MUST pass the write gate and leave an audit trail — it MUST NOT be waved
through merely because its origin is an AI agent.

Rationale: The scheduling, execution, versioning, and gating cores are mature and stable;
the pivot is a re-shaping of the development experience, not a kernel rewrite.

## Additional Constraints

- **Naming & repo**: The product MUST be renamed to **Weft**; the codebase MUST be
  refactored **in place** in the current repository (no fresh empty repo). The positioning
  shifts from "AI 数据中台" to "Tasks-as-Code platform".
- **Sub-feature dependency order** (MUST hold): B depends on A's cleaned environment;
  C depends on B; D's local-run subset depends only on B (so the local runtime may be
  built in parallel with C) while D's TEST-submit subset depends on C; E depends on C.
- **Sub-spec isolation**: Each sub-feature (A–E) MUST have its own spec with
  non-overlapping boundaries; a change that compiles alone but breaks/no-ops once a sibling
  lands is NOT done (不闭环).
- **Round-trip integrity**: `push` then `pull` to a clean directory MUST yield a
  semantically equivalent definition (no silent field loss).
- **No Legacy Migration (存量不予考虑)** — HARD RULE: Existing data created by the old
  Web editor and stored in the DB (tasks / workflows / catalogs) MUST NOT constrain the
  design. The transformation starts from a clean slate: legacy definition data is simply
  deleted — no export, migration, or coexistence path is built. The file format and
  pull/push serve only definitions created or rebuilt under the new paradigm.

## Development Workflow & Quality Gates

- Each sub-feature flows spec → plan → tasks → implementation, and every plan MUST include
  a Constitution Check that verifies conformance to Principles I–V before implementation.
- New features MUST ship with tests; no test = not done. Browser-verification and post-edit
  compile/typecheck gates from the repository guidance (CLAUDE.md) remain in force.
- Given the large deletion surface and main-line changes, the transformation SHOULD proceed
  in an isolated git worktree to avoid polluting recently merged work
  (distributed-cron, ops/instance-dag-viewer).

## Governance

This constitution supersedes other practices for the Weft transformation. All sub-feature
specs, plans, and reviews MUST verify compliance with Principles I–V; deviations MUST be
recorded with explicit written rationale and approved before implementation.

Amendments require: a documented change, a semantic version bump, and propagation to
dependent templates and guidance files. Versioning policy: MAJOR for backward-incompatible
principle removals/redefinitions; MINOR for a new principle/section or materially expanded
guidance; PATCH for clarifications and non-semantic refinements.

Compliance is reviewed at plan time (Constitution Check) and before merge (cross-feature
boundary and seam-closure check). Runtime development guidance lives in `CLAUDE.md`.

**Version**: 1.2.0 | **Ratified**: 2026-06-26 | **Last Amended**: 2026-06-29
