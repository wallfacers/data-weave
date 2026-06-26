# Weft

**任务即代码 (Tasks-as-Code)** 平台 —— 开发者本地用 AI 编程 agent 开发任务/任务流，pull/push 往返服务器治理与运行。

## Architecture

**Next.js frontend** → HTTP/SSE → **Spring Boot backend (WebFlux + four-module DDD)**. Two independent projects. Full design: [docs/architecture.md](docs/architecture.md).

## Repository Layout

```
frontend/                          # Next.js 16 (App Router) + React 19 + shadcn/ui
  app/page.tsx                     # `/` = Workspace (multi-tab workspace)
  app/{tasks,ops,fleet,...}/       # legacy routes → redirect("/?open=<view>")
  components/workspace/ + lib/workspace/  # tab bar + view container/registry + zustand store
  DESIGN.md                        # design-system source of truth (@google/design.md format)
  app/globals.css                  # effective oklch theme variables (preset-generated)
backend/                           # Spring Boot 4.0 + Java 25, Maven multi-module (com.dataweave)
  dataweave-api/                   # entry; WebFlux; MCP /mcp; CORS/WebClient config
  dataweave-master/                # scheduler + workflow + metrics/task/lineage + PolicyEngine/4 audit tables
  dataweave-worker/                # task executor + ControlledCommandExecutor
  dataweave-alert/                 # alert rules + channels (skeleton)
  # DDD layers per module: interfaces / application / domain / infrastructure
cli/                               # dw, single Go binary over master REST (binary not in git)
openspec/                          # change proposals, specs, archive (one change per dir)
docker-compose.yml                 # PostgreSQL + Redis
```

## Tech Stack

| Layer    | Stack                                                                       |
|----------|------------------------------------------------------------------------------|
| Frontend | Next.js 16 (App Router, Turbopack), React 19, shadcn/ui (base style / hugeicons), next-themes |
| Backend  | Java 25, Spring Boot 4.0 / Spring Framework 7 (**Jackson 3**), WebFlux, Maven multi-module |
| Data access | Spring Data JDBC + JdbcTemplate                                           |
| Storage  | **PostgreSQL (default)** · H2 (`profiles=h2` in-memory, no Docker, DDL-compatible) · Redis (EventBus/LogBus) · MinIO (log archive) |
| Scheduling | Peer masters + SKIP LOCKED claim + event-driven + soft preemption + cron guard table for dedup, `scheduler.mode=all-in-one\|distributed` |
| Metrics  | Micrometer + Actuator, four tiers (performance/resource/pipeline/SLA), `/api/ops/metrics` + `/actuator/prometheus` |
| Tools/Perms | MCP Server (`/mcp`, Bearer) exposes platform tools; all writes pass `PolicyEngine` L0–L4 + 4 audit tables |
| CLI      | `dw` Go binary (`cli/`): `task list/show/instances/rerun`, `logs cat` |
| Design system | `@google/design.md` (token source of truth + lint/export)              |
| Spec     | Methodology-agnostic — any SDD/TDD approach                                  |

## Build & Run

```bash
# Backend (PostgreSQL by default; start Docker DB first)
cd backend
docker compose up -d                               # PostgreSQL + Redis (localhost:5432)
./dev-install.sh                                    # fast local build (mvnd + cache, skip tests/fat jar)
./mvnw -pl dataweave-api spring-boot:run            # port 8000; health: GET /api/health

# Frontend
cd frontend && pnpm install && pnpm dev             # http://localhost:4000

# Zero external deps (H2 in-memory)
cd backend && ./dev-install.sh && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
```

Frontend `:4000`, backend `:8000`.

### Scheduling & Metrics Endpoints

- **Mode**: `scheduler.mode=all-in-one` (default, single JVM) | `distributed` (separate master/worker + PG + Redis + MinIO).
- **Metrics**: `GET /api/ops/metrics` (JSON snapshot), `/actuator/metrics` (Micrometer), `/actuator/prometheus`.
- **SSE**: logs `GET /api/ops/instances/{id}/logs/stream`; DAG status `GET /api/ops/workflow-instances/{id}/events/stream`.

### MCP & CLI

- **MCP**: `POST /mcp` (JSON-RPC initialize/tools.list/tools.call, Bearer `mcp.auth.token`).
- **dw CLI**: `cd cli && ./build.sh`; `DW_API` (default `:8000`), `DW_TOKEN` (write ops send `X-DW-Token` → `cli.auth.token`).
- **Audit trail**: every write action records `agent_action`; PolicyEngine gate applies uniformly.

## Key Conventions

- **Dependency direction**: domain ← application ← infrastructure ← interfaces (outer→inner only).
- **Side-effect ops must pass the gate**: any write tool (`node_exec`, CLI `rerun`, `applyFix`…) → `ActionRequest` → `GatedActionService.submit` → `PolicyEngine` (L0/L1 run; L2/L3 → approval + `PENDING_APPROVAL`; L4 reject) + `agent_action` trail, **no bypass**. Rules are data-driven (`policy_rules` table).
- **Adding an MCP tool**: register in `McpToolRegistry.registerTools()` (name + JSON Schema + handler); queries reuse domain services, writes pass the gate. `node_exec` command-string safe parsing lives in `PolicyEngine` (redirect/separator/subcommand → escalate to L2).
- **Scheduler deadlock-defense invariants** (hard): ① claim only via SKIP LOCKED; ② all state advances via optimistic CAS (`WHERE state=?`); ③ fixed lock order task→workflow; ④ inside the transaction persist state only — HTTP dispatch happens outside it. Waiting holds no resources.
- **Metric definitions are immutable**: change → add an incremented `version` in `metrics`, never UPDATE an old one. `SchedulerMetrics` owns all instrumentation.
- **i18n ownership — three rules** (see [docs/architecture.md](docs/architecture.md) + [docs/i18n-error-codes.md](docs/i18n-error-codes.md)): ① **Static UI copy** (buttons/labels/empty states/toasts) → frontend next-intl, ICU `{name}`, **by UI locale**; ② **Backend-generated** (MCP descriptions, approval reasons) → `Messages.get`, MessageFormat `{0}`, **by agent locale**; ③ **Errors** (`throw`, `ApiResponse.err`) → `BizException(code, args)` + `GlobalExceptionHandler`, **by UI locale**. Toasts trust the backend message (no Chinese fallback).
- **i18n keys**: `frontend/messages/{zh-CN,en-US}.json` namespaced by area (`common`/`workspace`/`ops` + one per view; `views.*` = tab titles only); **both bundles identical key sets** (CI checks, every `t("key")` statically resolvable); non-default locales deep-merge zh-CN via `i18n/request.ts`. Backend codes `<domain>.<semantic>` (e.g. `workflow.not_online`), **stable, never reused**; data terms (cron/DAG/SLA/lineage/OOM) stay English; `data.sql` seed data i18n-exempt (Chinese).
- **No ellipsis for in-progress states**: never use `…` to mean "loading/in progress" (the text already says it). Only allowed `…`: text truncation in code (e.g. `id.slice(0,13) + "…"`).
- **Spring Boot 4**: ① Jackson 3 — `ObjectMapper` in `tools.jackson.databind.*`, annotations stay `com.fasterxml.jackson.annotation.*`; ② no `WebClient.Builder` auto-config — build your own `@Bean` (`WebClientConfig`); ③ some test/auto-config annotations moved packages — fix imports per actual package.
- **Java 25**: this machine uses a symlink swap so non-interactive shells transparently use JDK 25.

## Development Methodology

**Methodology-agnostic.** Any SDD/TDD approach may be used — no mandated spec format or change-management tool. Requirement/design docs may live wherever appropriate.

## Knowledge Base Navigation

This file is the map; details live elsewhere:

| Looking for…           | Go to                                                       |
|------------------------|-------------------------------------------------------------|
| Architecture & layering | [docs/architecture.md](docs/architecture.md)               |
| Frontend design source / theme vars | [frontend/DESIGN.md](frontend/DESIGN.md) · `frontend/app/globals.css` |
| Scheduler kernel / metrics | `dataweave-master/.../application/SchedulerKernel.java` · `SchedulerMetrics.java` |
| Frontend metrics dashboard | `frontend/components/workspace/views/metrics-view.tsx`  |
| Catalog tree (folders+tags) | `dataweave-master/.../application/CatalogTreeService.java` (path/move/cycle guard) + `CatalogController`/`TagController` · frontend `catalog-tree.tsx` (drag MOVE_MIME/TASK_MIME) |
| Table lineage (build-as-you-create) | `LineageGraphService.java` (表=节点·任务=边二部图; 建任务即建血缘 `recordDesignTimeIo` + 全局/邻域/上下游 + 运行态 `syncedRowsLatestDay`) + `SqlTableExtractor.java` (Calcite reads/writes, A×B 交叉校验) + `LineageGraphController` (`/api/lineage/*`) |
| ETA prediction         | `SlaService.java` (`durationMedianMs` + `predictLatestEta`) → `GET /api/ops/eta-summary` |
| L1 真采集 + 故障注入     | `HeartbeatReporter.sample()`(worker, OperatingSystemMXBean) + `NodeTelemetryService`(master) · `scripts/fault-injection.sql` |
| How to run             | [README.md](README.md)                                      |

## Working Rules

### Post-Edit Verification
- **Backend**: after each edit `cd backend && ./mvnw -q -pl <changed-module> compile` — zero errors before continuing.
- **Frontend**: after each edit `cd frontend && pnpm typecheck` — zero errors before continuing.
- Skip only for high-confidence trivial changes (comments/copy/single literals); when unsure, run it.

### Backend Build
- **Use `./dev-install.sh` for local builds** — auto-detects `mvnd` (~5x faster, falls back to `mvnw`), skips tests/fat jar, content-hash module cache (`.mvn/extensions.xml`). `-pl <module> -am` for one module + upstream deps.
- It installs to `~/.m2` so `spring-boot:run` picks up new classes; skipping it means the running process keeps old jars. Use plain `./mvnw install` only for CI/deploy (needs tests + fat jar).

### Frontend Stack Gate (before any `frontend/` change)
- **base-style components**: custom triggers use `render` (not `asChild`); a `Button` rendered as `<a>` via `render={<Link/>}` needs `nativeButton={false}` or console errors.
- **Icons = hugeicons**: `<HugeiconsIcon icon={XxxIcon} />` from `@hugeicons/core-free-icons` (not lucide).
- shadcn rules: semantic tokens (`bg-primary`, `text-muted-foreground`), `gap-*` spacing, `size-*` for equal w/h, no hand-written `dark:` overrides.

### Design Contract Gate
- Before changing `frontend/` theme/visuals/design-system, **read [frontend/DESIGN.md](frontend/DESIGN.md) first** and state adopted constraints.
- Theme changes edit `DESIGN.md` (source of truth) + sync `app/globals.css`; `pnpm design:lint` validates.
- Conflict with `DESIGN.md` → stop and ask: ① follow it ② change it first ③ deviate with written rationale.

### Testing
- New features must have tests; no test = not done.
- Backend: JUnit 5 + AssertJ; WebFlux → `@SpringBootTest` or WebTestClient (SB4's `@WebFluxTest` is in `org.springframework.boot.webflux.test.autoconfigure`).
- Frontend: vitest as needed + browser verification of rendered page.

### Exploration & Brainstorming
- Ideas/design/troubleshooting: explore freely, no upfront artifacts.
- Major architecture changes (new module, cross-DDD-layer refactor) **must** run `superpowers:brainstorming` before proposing a design.

### Clarify Before Acting
- When requirements, scope, or approach are unclear, **ask first**, never guess.

### Parallel-Feature Isolation & Cross-Feature Awareness (SDD)
Applies when **more than one feature may be in flight**, especially with an SDD tool holding a **single global "active feature" pointer**. Spec Kit is this case: `/speckit-*` reads `.specify/feature.json` and `create-new-feature` silently rewrites it — a newer feature **steals the pointer** (this is how `/speckit-clarify` once mis-targeted `002-ops-dag-viewer` while we were on `001-distributed-cron-trigger`). The `.specify/` scaffold is deliberate-deviation footprint, not the default workflow.
- **Isolate (hard)**: each parallel feature gets its **own git worktree** (`git worktree add ../dw-<feature> -b <branch>`) so its SDD state can't clobber a sibling. One working copy = one active feature. Never interleave two features' SDD commands in one copy; if you must switch, pin per-shell with `export SPECIFY_FEATURE_DIRECTORY=specs/<NNN-feature>` (it's `SPECIFY_FEATURE_DIRECTORY`, **not** `SPECIFY_FEATURE` which only sets a label). `git worktree remove` on merge; never commit a worktree path.
- **Cross-feature awareness (habit, 防止相互不闭环)** — before starting, at plan time, and before merge: ① `git worktree list` + enumerate active `specs/*/` / `openspec/changes/*/`; ② read each sibling's `spec.md` intent + FRs and note the **surface it touches** (same files/tables/endpoints/domain services/config keys); ③ on overlap, **reconcile first** (record the dependency, agree ordering, or fold into one feature) — a change that compiles alone but breaks/no-ops once the sibling lands is **not done** (功能不闭环); ④ at integration, merge the sibling's landed work first, re-run shared-surface tests, confirm the seam closes.

### MCP / Skill Temporary Files
- MCP/Skill temp files (screenshots/traces, drafts, scripts, downloads) → project-root `tmp/` (create if absent). **Never** write to repo root, `frontend/`, `backend/`, `docs/`, system `/tmp`, `~/`, or any tracked path. `tmp/` is git-ignored.

### Response Style
- Concise and direct, no filler. Report faithfully: failed test → say so + paste output; skipped step → say it was skipped.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
at claude
<!-- SPECKIT END -->
