# Weft

**Tasks-as-Code** platform: developers author tasks/task-flows locally with AI coding agents, then pull/push to the server for governance and execution.

## Architecture

**Next.js frontend** → HTTP/SSE → **Spring Boot backend** (WebFlux, four-module DDD). Two independent projects. Full design: [docs/architecture.md](docs/architecture.md).

```
frontend/            # `/` = multi-tab Workspace (legacy routes redirect("/?open=<view>")); DESIGN.md = design source of truth
backend/             # Maven multi-module (com.dataweave); DDD layers per module: interfaces/application/domain/infrastructure
  dataweave-api/     #   entry: WebFlux, MCP /mcp, CORS/WebClient config
  dataweave-master/  #   scheduler + workflow + metrics/task/lineage + PolicyEngine + audit tables
  dataweave-worker/  #   task executor + ControlledCommandExecutor + localrun (CLI local runtime)
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

- **Primary authoring path = Skill + dw CLI**: `.claude/skills/weft-task-authoring/SKILL.md` drives the golden path `pull → edit → dw run → dw diff → dw push → dw run --test`. MCP is **optional** automation/query only — never extend authoring into MCP.
- **MCP**: `POST /mcp` (JSON-RPC, Bearer `mcp.auth.token`); `McpAuthFilter` binds identity → `TenantContext`, every tool calls `requireTenant(ctx)` (missing → `mcp.tenant_required`). Authoritative tool list = `McpToolRegistry.registerTools()`. Definition writes go **only** via `project_push` (risk-adaptive gate: add/modify=L1, delete/force=L2); inline `create_task/update_task/delete_task` removed. See `specs/010-weft-mcp-tools/`.
- **dw CLI**: `cd cli && ./build.sh`; env `DW_API` + `DW_TOKEN`. `dw run <task>` runs locally (worker executor subprocess); `dw run --test` = server TEST run. Exit codes: `cli/README.md`.
- **Audit trail**: every write records `agent_action`; PolicyEngine gate applies uniformly.

## Key Conventions

- **Dependency direction**: domain ← application ← infrastructure ← interfaces (outer→inner only).
- **All side-effect ops pass the gate, no bypass**: any write tool (`node_exec`, CLI `rerun`, `applyFix`…) → `ActionRequest` → `GatedActionService.submit` → `PolicyEngine` (L0/L1 run; L2/L3 approval + `PENDING_APPROVAL`; L4 reject) + `agent_action` trail. Rules live in the `policy_rules` table.
- **Adding an MCP tool**: register in `McpToolRegistry.registerTools()` (name + JSON Schema + handler); call `requireTenant(ctx)`; queries reuse domain services; writes go through the gate above. `node_exec` command-string safe parsing lives in `PolicyEngine` (redirect/separator/subcommand → escalate to L2).
- **Scheduler deadlock-defense invariants (hard)**: ① claim only via SKIP LOCKED; ② all state advances via optimistic CAS (`WHERE state=?`); ③ fixed lock order task→workflow; ④ persist state only inside the transaction — HTTP dispatch happens outside it.
- **Scheduler dispatch verification (hard)**: any change to the claim→dispatch→execute path (`SchedulerKernel` / `ParallelDispatcher` / `InstanceStateMachine` dispatch CAS/guards / `isCurrentDispatch` / `reportStarted` / gateways / lease-reaper) MUST be verified under **real concurrent dispatch**, not just unit tests — run the every-minute cron flow end-to-end and confirm `started_at − created_at ≈ 0`, root-node `attempt=1`, **zero** `跳过下发`/`中止执行` stragglers. Guard = positive-staleness (`COALESCE(attempt,0) <= cmd.attempt`, no `state='DISPATCHED'` filter) + `reportStarted` boolean fencing; diagnose via attempt-count↔delay correlation in `task_instance`. (A guard/claim visibility race once caused a silent ~120s every-run delay that all unit tests passed through.)
- **Metric definitions are immutable**: change = insert a new incremented `version` in `metrics`, never UPDATE. `SchedulerMetrics` owns all instrumentation.
- **i18n ownership — three rules** (details: [docs/i18n-error-codes.md](docs/i18n-error-codes.md)): ① static UI copy → frontend next-intl, ICU `{name}`, by UI locale; ② backend-generated text (MCP descriptions, approval reasons) → `Messages.get`, MessageFormat `{0}`, by agent locale; ③ errors → `BizException(code, args)` + `GlobalExceptionHandler`, by UI locale. Toasts trust the backend message (no hardcoded fallback).
- **i18n keys**: `frontend/messages/{zh-CN,en-US}.json`, namespaced by area; both bundles MUST have identical key sets (CI-checked, every `t("key")` statically resolvable). Backend codes `<domain>.<semantic>` (e.g. `workflow.not_online`), stable, never reused. Data terms (cron/DAG/SLA/lineage/OOM) stay English; `data.sql` seed data is i18n-exempt.
- **No ellipsis for in-progress states**: never use `…` to mean "loading". Only allowed use: text truncation (e.g. `id.slice(0,13) + "…"`).
- **Spring Boot 4**: ① Jackson 3 — `ObjectMapper` lives in `tools.jackson.databind.*`, annotations stay `com.fasterxml.jackson.annotation.*`; ② no `WebClient.Builder` auto-config — declare your own `@Bean` (`WebClientConfig`); ③ some test/auto-config annotations moved packages — fix imports per actual package.
- **Java 25**: this machine symlink-swaps the JDK so non-interactive shells use JDK 25 transparently.

## Knowledge Map

Functional module index (features → services · spec dirs · sources): **[docs/knowledge-map.md](docs/knowledge-map.md)**. This file is the map; details live in `specs/NNN-*/`. Authoritative schema: `backend/dataweave-api/src/main/resources/schema.sql` (single DDL, no migrations; `schema_version` strict SemVer, any table change bumps it — DB row / file header / project version stay equal).

## Working Rules

### Post-Edit Verification
- Backend: after each edit `cd backend && ./mvnw -q -pl <changed-module> compile` — zero errors before continuing.
- Frontend: after each edit `cd frontend && pnpm typecheck` — zero errors before continuing.
- Skip only for high-confidence trivial changes (comments/copy/single literals); when unsure, run it.

### Backend Build
- `./dev-install.sh` = fast local build (`-pl <module> -am` for one module + upstream deps). It installs to `~/.m2` so `spring-boot:run` picks up new classes — skipping it leaves the running process on old jars. Plain `./mvnw install` is CI/deploy only.

### Long-Running Commands on WSL2 — MUST Detach (hard rule)
Bash tool detects completion by stdout EOF, not process exit; Maven/test children inherit the pipe and "hang" after finishing on WSL2 — `>log 2>&1` alone is NOT enough. Detach with `setsid`, poll with a single instant check (never a foreground `sleep` loop; one bounded `sleep 120; <poll>` per turn if you must wait). Logs → session scratchpad.
```bash
setsid bash -c 'cd backend && ./mvnw -pl <mods> test >build.log 2>&1; echo $? >build.exit' </dev/null >/dev/null 2>&1 & disown
[ -f build.exit ] && echo "exit=$(cat build.exit)" || tail -1 build.log   # poll
```

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
Spec Kit's single global active-feature pointer (`.specify/feature.json`) is silently stolen by a newer feature.
- **Isolate (hard)**: one git worktree per parallel feature; one working copy = one active feature. Pin within a shell via `export SPECIFY_FEATURE_DIRECTORY=specs/<NNN-feature>` (NOT `SPECIFY_FEATURE`). `git worktree remove` on merge; never commit a worktree path.
- **Cross-feature awareness** (before start / at plan / before merge): `git worktree list` + enumerate active `specs/*/` & `openspec/changes/*/`; read each sibling's `spec.md` and the surface it touches (files/tables/endpoints/services/config); on overlap reconcile first; at integration merge siblings' landed work first + re-run shared-surface tests.

### Concurrent Multi-Agent Editing — Never Discard Another Agent's Work (hard rule)
Multiple agents write this repo concurrently (separate or shared branches); work you didn't author is load-bearing until proven otherwise.
- **Never discard/revert/roll back/overwrite** another agent's changes or commits without explicit user authorization (no `reset --hard`/`checkout --`/`restore`/`clean -fd`/`revert`/`push --force`/deletion because it "looks wrong"). Route around unfamiliar code or ask.
- **Detect first**: before touching a file run `git status`/`git log --oneline -15`/`git diff` and read it fresh; when unsure whose work it is, assume another agent's.
- **On collision STOP and escalate** — never pick a winner, merge blindly, or delete a side. Surface exact files + line ranges + both intents with git evidence, ask the user to adjudicate. Applies even on a single shared branch.

### MCP / Skill Temporary Files
- All MCP/Skill temp files (screenshots, traces, drafts, scripts, downloads) → project-root `tmp/` (git-ignored; create if absent). Never write to repo root, `frontend/`, `backend/`, `docs/`, system `/tmp`, `~/`, or any tracked path.

### Response Style
- Concise and direct, no filler. Report faithfully: failed test → say so + paste output; skipped step → say it was skipped.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
at specs/072-companion-workhorse-prod/plan.md
<!-- SPECKIT END -->
