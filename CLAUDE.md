# Weft

**Tasks-as-Code** platform: developers author tasks/task-flows locally with AI coding agents, then pull/push to the server for governance and execution.

## Architecture

**Next.js frontend** → HTTP/SSE → **Spring Boot backend** (WebFlux, four-module DDD). Two independent projects. Full design: [docs/architecture.md](docs/architecture.md).

```
frontend/            # Next.js 16 (App Router) + React 19 + shadcn/ui; `/` = multi-tab Workspace
                     #   legacy routes redirect("/?open=<view>"); DESIGN.md = design source of truth
backend/             # Spring Boot 4.0 + Java 25, Maven multi-module (com.dataweave)
  dataweave-api/     #   entry: WebFlux, MCP /mcp, CORS/WebClient config
  dataweave-master/  #   scheduler + workflow + metrics/task/lineage + PolicyEngine + audit tables
  dataweave-worker/  #   task executor + ControlledCommandExecutor + localrun (CLI local runtime)
  dataweave-alert/   #   alert rules + channels (skeleton)
                     #   DDD layers per module: interfaces / application / domain / infrastructure
cli/                 # dw Go binary: task/logs ops + pull/push/diff/run (binary not in git)
specs/               # active SDD (Spec Kit): specs/NNN-feature/ spec·plan·tasks
openspec/            # legacy change proposals + archive
docker-compose.yml   # PostgreSQL + Redis
```

## Tech Stack

- **Frontend**: Next.js 16 (App Router, Turbopack), React 19, shadcn/ui (base style, hugeicons), next-themes, next-intl.
- **Backend**: Java 25, Spring Boot 4.0 / Spring Framework 7 (**Jackson 3**), WebFlux, Spring Data JDBC + JdbcTemplate.
- **Storage**: PostgreSQL (default) · H2 (`profiles=h2`, in-memory, no Docker, DDL-compatible) · Redis (EventBus/LogBus) · MinIO (log archive).
- **Scheduling**: peer masters + SKIP LOCKED claim + event-driven + soft preemption + cron guard table; `scheduler.mode=all-in-one|distributed`.
- **Metrics**: Micrometer + Actuator; `GET /api/ops/metrics` (JSON), `/actuator/metrics`, `/actuator/prometheus`.
- **SSE**: logs `GET /api/ops/instances/{id}/logs/stream`; DAG status `GET /api/ops/workflow-instances/{id}/events/stream`.

## Build & Run

```bash
# Backend (PostgreSQL by default)
cd backend
docker compose up -d                     # PostgreSQL + Redis (localhost:5432)
./dev-install.sh                         # fast local build (mvnd + cache, skip tests/fat jar)
./mvnw -pl dataweave-api spring-boot:run # port 8000; health: GET /api/health
# Zero external deps: append -Dspring-boot.run.profiles=h2 (skip docker compose)

# Frontend
cd frontend && pnpm install && pnpm dev  # http://localhost:4000
```

## Agent Authoring, MCP & CLI

- **Primary authoring path = Skill + dw CLI**: `.claude/skills/weft-task-authoring/SKILL.md` (progressive disclosure) drives the golden path `pull → edit → dw run → dw diff → dw push → dw run --test`. MCP is an **optional** automation/query surface only — never extend authoring capability into MCP.
- **MCP**: `POST /mcp` (JSON-RPC, Bearer `mcp.auth.token`). Token binds tenant/user identity: `McpAuthFilter` validates → `McpController` sets `TenantContext` before dispatch, clears in `finally`. Every tool MUST call `requireTenant(ctx)` (missing identity → `mcp.tenant_required`). **Authoritative tool list: `McpToolRegistry.registerTools()`.** Definition writes go exclusively through `project_push` (risk-adaptive gate: pure add/modify = L1 direct; delete/force = L2 approval pending; rules data-driven via `policy_rules`); inline `create_task/update_task/delete_task` permanently removed.
- **dw CLI**: `cd cli && ./build.sh`; env `DW_API` (default `http://localhost:8000`) + `DW_TOKEN` (single Bearer credential, all endpoints). `dw run <task>` executes locally reusing the worker executor subprocess (classpath via `DW_WORKER_CP` or auto-detected worker fat jar); `dw run --test` submits a server TEST run. Exit codes + details: `cli/README.md`.
- **Audit trail**: every write action records `agent_action`; the PolicyEngine gate applies uniformly.

## Key Conventions

- **Dependency direction**: domain ← application ← infrastructure ← interfaces (outer→inner only).
- **All side-effect ops pass the gate, no bypass**: any write tool (`node_exec`, CLI `rerun`, `applyFix`…) → `ActionRequest` → `GatedActionService.submit` → `PolicyEngine` (L0/L1 run; L2/L3 approval + `PENDING_APPROVAL`; L4 reject) + `agent_action` trail. Rules live in the `policy_rules` table.
- **Adding an MCP tool**: register in `McpToolRegistry.registerTools()` (name + JSON Schema + handler); call `requireTenant(ctx)`; queries reuse domain services; writes go through the gate above. `node_exec` command-string safe parsing lives in `PolicyEngine` (redirect/separator/subcommand → escalate to L2).
- **Scheduler deadlock-defense invariants (hard)**: ① claim only via SKIP LOCKED; ② all state advances via optimistic CAS (`WHERE state=?`); ③ fixed lock order task→workflow; ④ persist state only inside the transaction — HTTP dispatch happens outside it.
- **Metric definitions are immutable**: change = insert a new incremented `version` in `metrics`, never UPDATE. `SchedulerMetrics` owns all instrumentation.
- **i18n ownership — three rules** (details: [docs/i18n-error-codes.md](docs/i18n-error-codes.md)): ① static UI copy → frontend next-intl, ICU `{name}`, by UI locale; ② backend-generated text (MCP descriptions, approval reasons) → `Messages.get`, MessageFormat `{0}`, by agent locale; ③ errors → `BizException(code, args)` + `GlobalExceptionHandler`, by UI locale. Toasts trust the backend message (no hardcoded fallback).
- **i18n keys**: `frontend/messages/{zh-CN,en-US}.json`, namespaced by area; both bundles MUST have identical key sets (CI-checked, every `t("key")` statically resolvable). Backend codes `<domain>.<semantic>` (e.g. `workflow.not_online`), stable, never reused. Data terms (cron/DAG/SLA/lineage/OOM) stay English; `data.sql` seed data is i18n-exempt.
- **No ellipsis for in-progress states**: never use `…` to mean "loading". Only allowed use: text truncation (e.g. `id.slice(0,13) + "…"`).
- **Spring Boot 4**: ① Jackson 3 — `ObjectMapper` lives in `tools.jackson.databind.*`, annotations stay `com.fasterxml.jackson.annotation.*`; ② no `WebClient.Builder` auto-config — declare your own `@Bean` (`WebClientConfig`); ③ some test/auto-config annotations moved packages — fix imports per actual package.
- **Java 25**: this machine symlink-swaps the JDK so non-interactive shells use JDK 25 transparently.

## Knowledge Map

This file is the map; details live elsewhere:

| Looking for… | Go to |
|---|---|
| Architecture & layering | [docs/architecture.md](docs/architecture.md) |
| Frontend design / theme vars | [frontend/DESIGN.md](frontend/DESIGN.md) · `frontend/app/globals.css` |
| Scheduler kernel | `dataweave-master/.../application/` `SchedulerKernel` · `SchedulerMetrics` · `TriggerEngine` · `TimingStrategy` · `MasterRegistry` |
| Metrics dashboard | `frontend/components/workspace/views/metrics-view.tsx` |
| Catalog tree (folders+tags) | `CatalogTreeService` + `CatalogController`/`TagController` · frontend `catalog-tree.tsx` |
| Table/column lineage | `LineageGraphService` + `SqlTableExtractor` (Calcite) + `/api/lineage/*`; **neo4j is the single store** (PG lineage tables retired); column lineage + synced rows: `specs/024-lineage-column-catalog`, `specs/025-lineage-synced-rows` |
| ETA prediction | `SlaService` → `GET /api/ops/eta-summary` |
| Node telemetry + fault injection | `HeartbeatReporter.sample()` (worker) + `NodeTelemetryService` (master) · `scripts/fault-injection.sql` |
| Project sync (pull/push/diff) | `ProjectSyncService` + `ProjectSyncController` (`POST /api/projects/{id}/pull\|push\|diff`) |
| CLI sync + local runtime | `cli/` + `dataweave-worker/.../localrun/LocalRunMain` + `PythonTaskExecutor` (CLI and server share the executor); `specs/009-weft-cli-runtime/` |
| MCP tools & tenant isolation | `McpToolRegistry` + `McpAuthFilter` + `McpController` + `TenantContext` + `DefaultPlatformActionExecutor`; `specs/010-weft-mcp-tools/` |
| Project-level isolation (036) | `TenantContext.projectId` + `JwtAuthFilter` (`X-Project-Id`/`?projectId=`) + `ProjectScope.require` (real `project_member` check) + `ProjectRoleService`; full endpoint inventory: `specs/036-project-isolation-sweep/sc-001-isolation-inventory.md` |
| How to run | [README.md](README.md) |
| **Authoritative schema** | `backend/dataweave-api/src/main/resources/schema.sql` — single authoritative DDL; no migration scripts. `schema_version` single-row table, strict SemVer (baseline `0.0.1`); any table change bumps the version; DB row / file header / project version must stay equal |

## Working Rules

### Post-Edit Verification
- Backend: after each edit `cd backend && ./mvnw -q -pl <changed-module> compile` — zero errors before continuing.
- Frontend: after each edit `cd frontend && pnpm typecheck` — zero errors before continuing.
- Skip only for high-confidence trivial changes (comments/copy/single literals); when unsure, run it.

### Backend Build
- Use `./dev-install.sh` for local builds — auto-detects `mvnd` (~5x faster), skips tests/fat jar, content-hash module cache. `-pl <module> -am` for one module + upstream deps.
- It installs to `~/.m2` so `spring-boot:run` picks up new classes; skipping it leaves the running process on old jars. Plain `./mvnw install` is for CI/deploy only.

### Long-Running Commands on WSL2 — MUST Detach (hard rule)
The Bash tool detects completion by stdout pipe EOF, not process exit; Maven/test child processes inherit the pipe and die slowly on WSL2, so builds "hang" after finishing. Plain `>log 2>&1` is NOT enough.
- Detach into a new session (returns in milliseconds):
  ```bash
  setsid bash -c 'cd backend && ./mvnw -pl <mods> test >build.log 2>&1; echo $? >build.exit' </dev/null >/dev/null 2>&1 & disown
  ```
- Poll with a single instant check — never a foreground `sleep` loop:
  ```bash
  [ -f build.exit ] && echo "DONE exit=$(cat build.exit)" || { echo running; tail -1 build.log; }
  ```
  Need to wait? One bounded `sleep 120; <single poll>` per turn.
- `setsid` inherits the shell env (JDK 25 + `mvnd` stay available); write logs under the session scratchpad, not a tracked dir.

### Frontend Stack Gate (before any `frontend/` change)
- base-style components: custom triggers use `render` (not `asChild`); a `Button` rendered as `<a>` via `render={<Link/>}` needs `nativeButton={false}`.
- Icons = hugeicons: `<HugeiconsIcon icon={XxxIcon} />` from `@hugeicons/core-free-icons` (not lucide).
- shadcn rules: semantic tokens (`bg-primary`, `text-muted-foreground`), `gap-*` spacing, `size-*` for equal w/h, no hand-written `dark:` overrides.

### Design Contract Gate
- Before changing theme/visuals/design-system: read [frontend/DESIGN.md](frontend/DESIGN.md) first and state adopted constraints.
- Theme changes edit `DESIGN.md` (source of truth) + sync `app/globals.css`; validate with `pnpm design:lint`.
- Conflict with `DESIGN.md` → stop and ask: ① follow it ② change it first ③ deviate with written rationale.

### Testing
- New features must have tests; no test = not done.
- Backend: JUnit 5 + AssertJ; WebFlux → `@SpringBootTest` or WebTestClient (SB4's `@WebFluxTest` is in `org.springframework.boot.webflux.test.autoconfigure`).
- Frontend: vitest as needed + browser verification of the rendered page.

### Exploration & Clarification
- Ideas/design/troubleshooting: explore freely, no upfront artifacts. Major architecture changes (new module, cross-DDD-layer refactor) MUST run `superpowers:brainstorming` before proposing a design.
- When requirements, scope, or approach are unclear: ask first, never guess.

### Parallel-Feature Isolation & Cross-Feature Awareness (SDD)
Spec Kit holds a single global "active feature" pointer (`.specify/feature.json`) that a newer feature silently steals — this has mis-targeted `/speckit-clarify` before.
- **Isolate (hard)**: each parallel feature gets its own git worktree (`git worktree add ../dw-<feature> -b <branch>`). One working copy = one active feature; never interleave two features' SDD commands in one copy. To switch within a shell, pin with `export SPECIFY_FEATURE_DIRECTORY=specs/<NNN-feature>` (NOT `SPECIFY_FEATURE`, which only sets a label). `git worktree remove` on merge; never commit a worktree path.
- **Cross-feature awareness** — before starting, at plan time, and before merge: ① `git worktree list` + enumerate active `specs/*/` and `openspec/changes/*/`; ② read each sibling's `spec.md` intent and note the surface it touches (files/tables/endpoints/services/config keys); ③ on overlap, reconcile first (record dependency, agree ordering, or fold into one feature) — a change that compiles alone but breaks once the sibling lands is not done; ④ at integration, merge the sibling's landed work first, re-run shared-surface tests.

### Concurrent Multi-Agent Editing — Never Discard Another Agent's Work (hard rule)
Multiple agents write this repo at the same time — separate worktrees/branches, sometimes the same branch. Work you did not author is load-bearing until proven otherwise.
- **Never discard, revert, roll back, or overwrite another agent's changes or commits** without explicit user authorization: no `git reset --hard` / `checkout --` / `restore` / `clean -fd` over work you didn't create; no `git revert` / `push --force` over commits you didn't make; no deleting files or rewriting sections because they "look wrong" or clash with your plan. If unfamiliar code blocks your change, route around it or ask — don't delete it.
- **Before touching a file, detect others' work**: `git status`, `git log --oneline -15`, `git diff`, and read the file fresh — never trust a stale in-context copy. When unsure whose work it is, assume it's another agent's.
- **On collision, STOP and escalate — mandatory.** Conflicting/duplicated/ambiguous edits: do NOT pick a winner, merge blindly, or delete a side. Halt, surface the exact conflict (files + line ranges + both intents, with `git log`/`git diff` evidence), and ask the user to adjudicate. Silent overwrite is forbidden even under deadline pressure.
- This rule governs the whole repo — broader than the SDD worktree rule above, and applies even on a single shared branch.

### MCP / Skill Temporary Files
- All MCP/Skill temp files (screenshots, traces, drafts, scripts, downloads) → project-root `tmp/` (git-ignored; create if absent). Never write to repo root, `frontend/`, `backend/`, `docs/`, system `/tmp`, `~/`, or any tracked path.

### Response Style
- Concise and direct, no filler. Report faithfully: failed test → say so + paste output; skipped step → say it was skipped.

<!-- SPECKIT START -->
Current feature: [040-ops-summary-date-filter](specs/040-ops-summary-date-filter/spec.md)
Implementation plan: [plan.md](specs/040-ops-summary-date-filter/plan.md)
Research: [research.md](specs/040-ops-summary-date-filter/research.md)
Data model: [data-model.md](specs/040-ops-summary-date-filter/data-model.md)
API contracts: [contracts/](specs/040-ops-summary-date-filter/contracts/)
<!-- SPECKIT END -->
