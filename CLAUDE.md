# DataWeave

AI-Agent-native data platform — **weave data with Agents**. Users state data needs in natural language; the Agent autonomously drives the full lifecycle: task development, scheduling, metrics, lineage. Chat connects to the backend directly over the **AG-UI protocol**, with an Agent orchestration engine driving each platform module.

## Architecture

Two independent projects: **Next.js frontend (CopilotKit v2)** → HTTP/SSE (AG-UI protocol) → **Spring Boot backend (WebFlux + four-module DDD)**. Full design in [docs/architecture.md](docs/architecture.md).

## Repository Layout

```
frontend/                          # Next.js 16 (App Router) + React 19 + shadcn/ui + CopilotKit v2
  app/page.tsx                     # `/` is the Workspace (left chat cockpit + right multi-tab workspace)
  app/{tasks,ops,fleet,...}/       # legacy routes → redirect("/?open=<view>") deep-link fallback
  components/agent-rail.tsx        # left Agent cockpit (CopilotChat → backend AG-UI, draggable width)
  components/workspace/            # tab bar (Pinned/Ephemeral + "+" launcher) + view container + views/
  lib/workspace/                   # zustand store (source of truth) / view registry / session persistence
  DESIGN.md                        # design-system source of truth (@google/design.md format)
  app/globals.css                  # effective oklch theme variables (preset-generated)

backend/                           # Spring Boot 4.0 + Java 25, Maven multi-module (root package com.dataweave)
  dataweave-api/                   # entry point; WebFlux; AG-UI /agui + MCP /mcp endpoints; bridge layer; CORS/WebClient config
  dataweave-master/                # scheduler + workflow + metrics/task/lineage domains + PolicyEngine/4 audit tables (Spring Data JDBC)
  dataweave-worker/                # task executor + controlled command execution (ControlledCommandExecutor)
  dataweave-alert/                 # alert rules + notification channels (skeleton)
  # DDD four layers per module: interfaces / application / domain / infrastructure

cli/                               # dw, single Go binary (thin shell over master REST, built separately, binary not in git)
deploy/workhorse/                  # workhorse-agent deploy config (config.yaml + mcp.json, provisional)
docs/architecture.md               # architecture source of truth
openspec/                          # OpenSpec SDD: changes / specs / archive
  changes/<name>/                  # one change per directory (proposal + design + specs + tasks)
docker-compose.yml                 # PostgreSQL + Redis (+ workhorse profile); backend connects to this PG by default
```

## Tech Stack

| Layer    | Stack                                                                       |
|----------|------------------------------------------------------------------------------|
| Frontend | Next.js 16 (App Router, Turbopack), React 19, shadcn/ui (base style / hugeicons), next-themes |
| Chat     | CopilotKit **v2** (`@copilotkit/react-core/v2`) + `@ag-ui/client` `HttpAgent`, direct to AG-UI, **no Node Runtime** |
| Backend  | Java 25, Spring Boot 4.0 / Spring Framework 7 (**Jackson 3**), WebFlux (AG-UI SSE), Maven multi-module |
| Data access | Spring Data JDBC + JdbcTemplate                                           |
| Storage  | **PostgreSQL (default)** · H2 (`-Dspring-boot.run.profiles=h2` in-memory, used without Docker, DDL-compatible) · Redis (EventBus/LogBus) · MinIO (log archive) |
| Scheduling | Peer masters + SKIP LOCKED claim + event-driven + soft preemption + cron guard table for dedup, `scheduler.mode=all-in-one\|distributed` |
| Metrics  | Micrometer + Actuator, four metric tiers (performance/resource/pipeline/SLA), `/api/ops/metrics` + `/actuator/prometheus` |
| Agent    | Dual mode `agent.mode=mock\|workhorse` (default mock=`IntentRouter`; workhorse=real LLM brain via bridge layer) |
| Tools/Permissions | DataWeave MCP Server (`/mcp`, Bearer) exposes platform tools; all writes pass `PolicyEngine` L0–L4 gates + 4 audit tables |
| CLI      | `dw` (single Go binary, `cli/`): `task list/show/instances/rerun`, `logs cat`, calls master REST |
| Design system | `@google/design.md` (token source of truth + lint/export)              |
| Spec     | OpenSpec (spec-driven, `/opsx:*`)                                            |

## Build & Run

```bash
# Backend (connects to PostgreSQL by default; start the Docker DB first)
cd backend
docker compose up -d                               # PostgreSQL + Redis (default localhost:5432)
./mvnw install -DskipTests                         # required on first run / after changing domain·application·infra
./mvnw -pl dataweave-api spring-boot:run           # port 8000; AG-UI: POST /agui; health: GET /api/health

# Frontend
cd frontend
pnpm install
pnpm dev                                           # http://localhost:4000 (left chat cockpit + right Workspace)

# Zero external deps (H2 in-memory, no Docker)
cd backend && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
```

Entry points: frontend `http://localhost:4000`, backend `http://localhost:8000`; frontend connects via `NEXT_PUBLIC_AGENT_URL` (default `http://localhost:8000/agui`).

### Scheduling & Metrics Endpoints

- **Scheduling mode**: `scheduler.mode=all-in-one` (default) single JVM all-in-one; `distributed` separate master/worker + PG + Redis + MinIO.
- **System metrics**: `GET /api/ops/metrics` (JSON snapshot), `GET /actuator/metrics` (Micrometer detail), `GET /actuator/prometheus` (Prometheus format).
- **Realtime SSE**: log stream `GET /api/ops/instances/{id}/logs/stream`, status stream `GET /api/ops/workflow-instances/{id}/events/stream`.

### Agent Brain Modes (agent-fabric-m1)

- **`agent.mode=mock` (default, zero deps)**: `IntentRouter` rule routing; runs on CI / fresh clone.
- **`agent.mode=workhorse`**: connects workhorse-agent (real LLM brain). `AguiController` forwards workhorse session SSE → AG-UI events via the bridge layer.
  ```bash
  # 1) Start workhorse (deploy config in deploy/workhorse/, needs ANTHROPIC_API_KEY/OPENAI_API_KEY)
  docker compose --profile workhorse up -d workhorse
  # 2) Switch backend to workhorse mode
  ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.arguments=--agent.mode=workhorse
  ```
- **MCP endpoint**: `POST /mcp` (JSON-RPC: initialize/tools/list/tools/call, Bearer `mcp.auth.token`). workhorse connects via `deploy/workhorse/mcp.json`; tokens must match on both sides.
- **dw CLI**: `cd cli && ./build.sh`; `DW_API` (default `:8000`), `DW_TOKEN` (write ops send `X-DW-Token`, maps to `cli.auth.token`).
- **Audit replay**: every run records `agent_session/agent_run/agent_step/agent_action`; both modes leave the same trail.

## Key Conventions

- **Dependency direction**: domain ← application ← infrastructure ← interfaces (outer depends on inner, never reverse).
- **Adding Agent capability**: mock mode adds an intent branch in `IntentRouter`; real-brain mode registers a platform tool in `McpToolRegistry` (queries go straight to master domain services, writes pass the `GatedActionService` gate). Both modes emit isomorphic AG-UI events through the same `AguiEvents` exit.
- **Side-effect ops must pass the gate**: any write tool (incl. `node_exec`, CLI `rerun`, `applyFix`) builds an `ActionRequest` → `GatedActionService.submit` → `PolicyEngine` ruling (L0/L1 execute directly, L2/L3 create an approval and return `PENDING_APPROVAL`, L4 reject) + `agent_action` trail, **no bypass path**. Grading rules are data-driven (`policy_rules` table).
- **Adding an MCP tool**: register in `McpToolRegistry.registerTools()` (name + JSON Schema + handler); query tools reuse domain services, write tools pass the gate. `node_exec` command-string safe parsing lives in `PolicyEngine` (redirect/separator/subcommand → escalate to L2).
- **AG-UI event sequence** (`/agui`, `text/event-stream`, `type` in SCREAMING_SNAKE_CASE): `RUN_STARTED → TEXT_MESSAGE_START → N×TEXT_MESSAGE_CONTENT(markdown) → TEXT_MESSAGE_END → [CUSTOM(name="dataweave.result")] → [CUSTOM(name="dataweave.ui.open")] → RUN_FINISHED`. Text goes via Markdown (CopilotChat native render); structured results via `CUSTOM` events; view summoning via `CUSTOM(dataweave.ui.open)` (payload `{view, params?}`, frontend Workspace dedupes and activates, unknown view ignored).
- **Scheduler deadlock-defense invariants** (hard): ① claim only via SKIP LOCKED; ② all state advances via optimistic CAS (`WHERE state=?` guard); ③ fixed lock order task→workflow; ④ inside the transaction only persist state, HTTP dispatch happens outside it. Waiting holds no resources.
- **SSE endpoints** (Phase 4 realtime-streams): ① `GET /api/ops/instances/{id}/logs/stream` (live log stream, Last-Event-ID resume); ② `GET /api/ops/workflow-instances/{id}/events/stream` (DAG status stream, nodes recolor live).
- **System metrics endpoints**: `GET /api/ops/metrics` (four-tier snapshot, for the frontend dashboard); `/actuator/metrics` + `/actuator/prometheus` (Prometheus scrape). The `SchedulerMetrics` service owns all instrumentation.
- **Metric definitions are immutable**: changing a definition → add an incremented `version` in `metrics`, never UPDATE an old version.
- **i18n ownership — three rules** (must hold for any new/changed user-visible copy; see [docs/architecture.md](docs/architecture.md) i18n section + [docs/i18n-error-codes.md](docs/i18n-error-codes.md)): ① **Static UI copy** (buttons/tabs/form labels/empty states/toasts) → frontend next-intl key table, ICU `{name}` placeholders, **by UI locale**; ② **Backend-generated** (AG-UI markdown, MCP tool descriptions, diagnostic suggestions, approval reasons) → backend `Messages.get`, MessageFormat `{0}` placeholders, **by agent locale**; ③ **Errors/exceptions** (`throw`, `ApiResponse.err`) → backend `BizException(code, args)` + `GlobalExceptionHandler` localization, **by UI locale**. Frontend toasts trust the backend-localized message, no more `|| "Chinese fallback"`.
- **i18n key naming**: frontend messages (`frontend/messages/{zh-CN,en-US}.json`) are namespaced by area at the top level (`common`/`workspace`/`ops` + one per view such as `cockpit`/`metrics`/`instanceTable`…), `views.*` holds only tab titles; **both bundles must have identical key sets** (CI/script checks zh-only/en-only are empty, every `t("key")` statically resolvable); non-default locales deep-merge zh-CN fallback for missing keys via `i18n/request.ts`. Backend codes are named `<domain>.<semantic>` (kebab/snake consistent, e.g. `workflow.not_online`), **stable and never reused**; data-platform terms (cron/DAG/SLA/lineage/OOM) keep their English form; `data.sql` business seed data is i18n-exempt (keeps Chinese).
- **No ellipsis for in-progress states**: user-visible copy (loading states, buttons, placeholders, menu items, etc.) **must not use `…` to mean "in progress / loading"**. The text already conveys the state (e.g. "Loading", "Connecting", "Thinking"), no extra symbol needed. The only allowed ellipsis: **text truncation in code** (e.g. ID truncation `id.slice(0,13) + "…"`), which correctly signals clipped content.
- **Spring Boot 4 notes**: ① Jackson 3 — `ObjectMapper` is in `tools.jackson.databind.*`, annotations stay in `com.fasterxml.jackson.annotation.*`; ② no `WebClient.Builder` auto-config, build your own `@Bean` (see `WebClientConfig`); ③ some test/auto-config annotations moved packages, fix import errors per the actual package.
- **Java 25**: this machine uses a symlink swap so non-interactive shells transparently use JDK 25.

## OpenSpec Workflow (SDD)

DataWeave uses **OpenSpec (spec-driven schema)** as the main workflow for proposal, design, implementation, and archive. Requirement/MVP docs belong in `openspec/`; **do not add new requirement docs to `docs/`** (`docs/` holds only references like the architecture source of truth).

| Command | Purpose |
|---------|---------|
| `/opsx:explore [topic]` | Open-ended exploration — think, compare, diagram. No artifacts, no code. |
| `/opsx:propose <change-name>` | Create a change: proposal.md + design.md + specs/ + tasks.md |
| `/opsx:apply [change]` | Implement tasks.md checkbox by checkbox |
| `/opsx:archive [change]` | Merge delta specs into base specs, move change to `changes/archive/YYYY-MM-DD-<name>/` |

```
/opsx:explore "idea"   → think it through (no artifacts)
   ↓
/opsx:propose <name>   → generate proposal + design + specs + tasks
   ↓ review
/opsx:apply            → implement item by item
   ↓ all done
/opsx:archive          → merge delta, archive
```

The first change `dataweave-mvp` is in place (`openspec status --change dataweave-mvp`).

## Knowledge Base Navigation

This file is the map; details live elsewhere:

| Looking for…           | Go to                                                       |
|------------------------|-------------------------------------------------------------|
| Active change proposals | `openspec/changes/` (`openspec list`)                      |
| System behavior specs  | `openspec/specs/`                                           |
| Archived changes       | `openspec/changes/archive/`                                 |
| Architecture & layering | [docs/architecture.md](docs/architecture.md)               |
| MVP requirements & acceptance | `openspec/changes/dataweave-mvp/`                    |
| Frontend design-system source | [frontend/DESIGN.md](frontend/DESIGN.md)             |
| Effective theme CSS variables | `frontend/app/globals.css`                           |
| AG-UI endpoint impl    | `backend/dataweave-api/.../interfaces/AguiController.java`  |
| Agent intent routing   | `backend/dataweave-api/.../application/IntentRouter.java`   |
| Scheduler kernel       | `backend/dataweave-master/.../application/SchedulerKernel.java` |
| System metrics         | `backend/dataweave-master/.../application/SchedulerMetrics.java` |
| Frontend metrics dashboard | `frontend/components/workspace/views/metrics-view.tsx`  |
| Catalog tree (folders+tags) | `backend/dataweave-master/.../application/CatalogTreeService.java` (path maintenance/move/cycle guard) + `CatalogController`/`TagController` |
| Frontend catalog tree component | `frontend/components/workspace/catalog-tree.tsx` (left canvas panel; two drag types MOVE_MIME/TASK_MIME) |
| How to run             | [README.md](README.md)                                      |

## Working Rules

### Post-Edit Verification

- **Backend**: after each edit run `cd backend && ./mvnw -q -pl <changed-module> compile` — confirm zero compile errors before continuing.
- **Frontend**: after each edit run `cd frontend && pnpm typecheck` — confirm zero type errors before continuing.
- **Exception**: high-confidence small changes (comments/copy/single-line literals) may skip; when unsure, run it.

### Backend Run vs Compile

- `./mvnw -pl dataweave-api spring-boot:run` loads sibling modules (master/worker/alert) from `~/.m2`, **not** `target/classes`. After changing domain/application/infrastructure you must first `./mvnw install -DskipTests` (or `-pl <module> -am`), otherwise the running process still uses old classes.
- Single-module run without a prior install errors with "sibling jar not found".

### Browser Verification Gate (hard)

- **`pnpm build` passing ≠ the page renders.** CopilotKit/AG-UI seams only surface at browser runtime; neither build nor Node seam-checks catch them.
- Any task touching `/agent`, the CopilotKit Provider, the AG-UI protocol, or theme/layout **must actually run once in the browser when done** (`mcp__playwright__*` or `playwright-cli`): confirm CopilotChat renders the input box (not "just a few divider lines"), console has no errors, and a message can be sent and streamed back.
- Browser verification artifacts (screenshots/traces) go in project-root `tmp/`, cleaned up after; **never left in the repo**.

### Frontend Stack Gate

Before writing or changing any `frontend/` code:
- **Chat uses CopilotKit v2 only**: import `CopilotKitProvider`/`CopilotChat` from `@copilotkit/react-core/v2`, `selfManagedAgents={{ dataweave: httpAgent }}`, import `@copilotkit/react-core/v2/styles.css`. **No v1** (it hard-requires `runtimeUrl` at runtime and doesn't recognize `selfManagedAgents`).
- **`@ag-ui/client` version must match CopilotKit's internal binding** (currently `@copilotkit/react-core@1.59.5` → `@ag-ui/client@0.0.53`). A wrong version makes `tsc` report a private-property `_debug` conflict between `HttpAgent` and `AbstractAgent`. Align on every CopilotKit upgrade.
- **base-style components**: custom triggers use the `render` prop (not `asChild`). A base UI `Button` rendered with `render={<Link/>}` (becomes `<a>`) must add `nativeButton={false}`, or the console errors.
- **Icons use hugeicons**: `<HugeiconsIcon icon={XxxIcon} />`, names from `@hugeicons/core-free-icons` (not lucide).
- Follow shadcn rules: semantic tokens (`bg-primary`, `text-muted-foreground`), spacing `gap-*`, equal width/height `size-*`, no hand-written `dark:` color overrides.

### Design Contract Gate

- Before changing `frontend/` theme/visuals/design-system, **read [frontend/DESIGN.md](frontend/DESIGN.md) first** and state the constraints adopted in the proposal.
- Theme changes edit `DESIGN.md` (source of truth) + sync `app/globals.css`; `pnpm design:lint` validates.
- If the direction conflicts with `DESIGN.md`, stop and ask: ① follow DESIGN.md ② change DESIGN.md first ③ deviate deliberately with written rationale.

### AG-UI Protocol Contract Gate

- Before changing the `/agui` endpoint or event structure, change both sides together and align: event `type` in SCREAMING_SNAKE_CASE, complete sequence (RUN_STARTED…RUN_FINISHED), CORS allowing the frontend origin (`http://localhost:4000`).
- After the change, run the Browser Verification Gate once for real.

### Testing

- New features must have tests; no test = not done.
- Backend: JUnit 5 + AssertJ; for WebFlux/AG-UI use `@SpringBootTest` or WebTestClient (note SB4's `@WebFluxTest` is in `org.springframework.boot.webflux.test.autoconfigure`).
- Frontend: vitest (as needed) + the end-to-end real run from the Browser Verification Gate.

### Exploration & Brainstorming

- New ideas, design discussion, troubleshooting: prefer `/opsx:explore` (carries OpenSpec context, writes no code).
- Major architecture changes (new module, cross-DDD-layer refactor, AG-UI protocol change) **must** run `superpowers:brainstorming` before `/opsx:propose`. Regular features just use `/opsx:explore`.

### Clarify Before Acting

- When requirements, scope, or approach are unclear, **ask first**, never guess.

### MCP / Skill Temporary Files

- Temp files from MCP/Skill (Playwright screenshots/traces, brainstorming drafts, intermediate scripts, downloads, etc.) **must** go in project-root `tmp/` (create if absent).
- **Never** write to: repo root, `frontend/`, `backend/`, `docs/`, system `/tmp`, `~/`, or any tracked source path.
- `tmp/` is not committed to git.

### Response Style

- Concise and direct, no filler. Report faithfully: if a test fails, say so and paste the output; if a step was skipped, say it was skipped.
