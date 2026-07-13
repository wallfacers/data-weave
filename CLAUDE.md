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
- **Scheduler dispatch verification (hard)**: any change to the claim→dispatch→execute path (`SchedulerKernel` / `ParallelDispatcher` / `InstanceStateMachine` dispatch CAS/guards / `isCurrentDispatch` / `reportStarted` / gateways / lease-reaper) MUST be verified under **real concurrent dispatch**, not just unit tests — run the every-minute cron flow end-to-end and confirm `started_at − created_at ≈ 0` with root-node `attempt=1` and **zero** `跳过下发`/`中止执行` stragglers. The `isCurrentDispatch` false-negative (guard read racing the claim-commit visibility on the fire-and-forget dispatch thread) caused a silent 100%-every-run ~120s delay that ALL unit tests passed through — the fix is guard = positive-staleness (`COALESCE(attempt,0) <= cmd.attempt`, no `state='DISPATCHED'` filter) + `reportStarted` boolean fencing. Watch attempt-count vs delay correlation in `task_instance` to diagnose.
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
| 节点容错闭环（060） | 坏/假/丢失节点自动剔除+任务转移+不丢不双跑不误判终态。节点可用性门单点收口 `SlotManager`（心跳新鲜+`incarnation_since` 稳定窗+`quarantined_until` 熔断隔离，判据 `NodeHealthService.isAvailable`）；熔断计数原子/单调 SQL（`NodeHealthService.recordInfraFailure`/`clearOnSuccess`）。**计数双拆（硬）**：`attempt`=纯下发纪元栅栏（`isCurrentDispatch`/`casDispatch` 语义零改动），`business_attempt`=业务重试（`RetryService` 比它），`infra_redispatch_count`=infra 重派。infra 回收（`InstanceStateMachine.casRequeueInfra`/`reclaimInfra`）不烧业务重试、永不判终态；超 `infra-redispatch-max` → `SUSPENDED`（非终态，人工 kill/rerun 转出）。`FleetService.handleWorkerRestart`=节点级即时回收（incarnation 变即回收该节点全部活跃实例，I1）；`LeaseReaper` WORKER_LOST 兜底。RUNNING 真续约=`HeartbeatReporter` 真 `runningInstanceIds()`（去硬编码 `[]`）+ `WorkerExecService.running` 集；分区自我中止 `abortAll()`（`self-fence-grace ≥ 2×心跳间隔`）。`TimeoutSweeper` max-runtime（`long_running` 豁免）；`StuckInstanceSweeper` 无节点等待检测+恢复唤醒。Flink 流式=`task_def.long_running`+`external_job_handle` detached/reattach（REST 轮询+句柄回写**已去桩真实现并经 061 T038 真跑核实**：detached→JobID→handle 回写→REST GET /jobs/{id} 轮询→cancel）；schema `0.15.0`；`specs/060-node-eviction-failover/` |
| Metrics dashboard | `frontend/components/workspace/views/metrics-view.tsx` |
| Catalog tree (folders+tags) | `CatalogTreeService` + `CatalogController`/`TagController` · frontend `catalog-tree.tsx` |
| Table/column lineage | `LineageGraphService` + `SqlTableExtractor` (Calcite) + `/api/lineage/*`; **neo4j is the single store** (PG lineage tables retired); column lineage + synced rows: `specs/024-lineage-column-catalog`, `specs/025-lineage-synced-rows` |
| Catalog grounding（血缘目录接地） | `CatalogGroundingService.ground()` 三态真伪裁决 + `DatasourceBoundCatalog.probeExistence` + `SystemNamespaceClassifier`; 审计 `lineage_grounding_disposition`; `specs/055-lineage-catalog-grounding/` |
| 创作上下文（数据开发 LSP） | `AuthoringContextService`（`context`/`contextForDraft`/`taskDependencies`）复用 `TaskLineageResolver` 只读共享核（push/authoring 同抽取）+ 三态接地 + 表级上下游；REST `/api/authoring-context/*` · MCP `query_authoring_context`/`query_task_deps` · CLI `dw context`/`dw deps`；`specs/058-authoring-context/` |
| ETA prediction | `SlaService` → `GET /api/ops/eta-summary` |
| Node telemetry + fault injection | `HeartbeatReporter.sample()` (worker) + `NodeTelemetryService` (master) · `scripts/fault-injection.sql` |
| Project sync (pull/push/diff) | `ProjectSyncService` + `ProjectSyncController` (`POST /api/projects/{id}/pull\|push\|diff`) |
| CLI sync + local runtime | `cli/` + `dataweave-worker/.../localrun/LocalRunMain` + `PythonTaskExecutor` (CLI and server share the executor); `specs/009-weft-cli-runtime/` |
| MCP tools & tenant isolation | `McpToolRegistry` + `McpAuthFilter` + `McpController` + `TenantContext` + `DefaultPlatformActionExecutor`; `specs/010-weft-mcp-tools/` |
| Project-level isolation (036) | `TenantContext.projectId` + `JwtAuthFilter` (`X-Project-Id`/`?projectId=`) + `ProjectScope.require` (real `project_member` check) + `ProjectRoleService`; full endpoint inventory: `specs/036-project-isolation-sweep/sc-001-isolation-inventory.md` |
| How to run | [README.md](README.md) |
| 大数据任务类型真跑验证（061） | 059 的 7 类执行器（Sql/Hive/Spark/Python/Flink/DataX/SeaTunnel）此前单测只覆盖「命令构造 + 缺引擎 SKIPPED」，真 SUCCESS 从未证明。061 = 建真实引擎环境逐类真跑取证 + 修真跑暴露缺陷 + 台账（`specs/061-task-type-verification/evidence/{LEDGER.md,ledger.json}` + `evidence/<engine>/*.log` 脱敏，SC-008 第三者可判真伪）。**10 引擎行全 PASS**：ClickHouse24.3/StarRocks3.3/Doris2.1（MySQL/HTTP 协议内置驱动免上传）·**Hive4.0（唯一需上传驱动**：standalone jar 缺 `DelegationTokenIssuer`→自组含 hadoop-common 的 uber jar；本地 dw run 经 Spring Boot PropertiesLauncher 的 `LOADER_PATH` 挂载，非拼 `DW_WORKER_CP`）·DataX/SeaTunnel/Spark3.5/Flink1.20·Python/Shell 回归。三态硬门=真 SUCCESS+真 FAILURE（引擎原生错误+退出码透传）+SKIPPED（引擎缺失「已跳过」exit0）。harness `verification/061-task-types/`（按 family 拆 compose·错峰起防 OOM·`scripts/capture.sh` 脱敏+台账 upsert）。真跑暴露 5 缺陷：D1（dw push/diff 用基线数当 expectedFileCount，已修）·D2/D3（SeaTunnel 缺 --master local·JDK25 不兼容，已修）·D4（Python/Shell 缺解释器时诊断不经 onLine→实例日志裸 -1 静默，已修：抽 `interpreterExecutable()` seam+onLine 吐诊断）·D5（上传驱动引擎在**分布式 worker** 不可执行=028 已知限制 `SqlTaskExecutor.openConnection` 无 isolatedLoader bean+LOCAL 存储 jar worker 不可达，契约正确 SKIPPED，超范围）。**Flink `executeLongRunning` 已去桩真跑核实（T038）**：detached→JobID→handle 回写→REST 轮询→cancel（SC-005）。`specs/061-task-type-verification/` |
| 实时任务运维（062） | 运维中心 OpsView 追加独立「实时任务」面板（第 5 tab，`StreamingTasksPanel` fork DataTable），把 `long_running` 流式任务提为一等运维对象：集中视图/最新日志（复用 SSE）/优雅停止（保留 checkpoint,区别强制 kill）/检查点续跑（N=3 滚动+可选回滚点）/SUSPENDED 一等化+健康监控。新表 `task_checkpoint`（`CheckpointRepository`+`CheckpointService` 滚动淘汰）+ `task_instance.long_running`/`resume_checkpoint_id` 列（schema `0.15.0→0.16.0`）。`OpsService.stopWithSavepoint`（`FlinkSavepointClient` REST stop-with-savepoint→写检查点→CAS STOPPED）/`resumeFromCheckpoint`（`InstanceStateMachine.casResumeFromCheckpoint` STOPPED/SUSPENDED→WAITING 保留句柄+记 resume_checkpoint_id，**不动 attempt/business_attempt** 守 060 七红线）；保留 `killTask`/`rerunInstance` 存量语义；全 L1 直执+audit。`SchedulerMetrics` +`scheduler.streaming.checkpoint.total`/`.recovering`。**🔴 Option C 缺口闭合（硬）**：060 只加了 `long_running` 列+运行时+reattach，但**服务端创作→下发链路从未接通**（`TaskDef` 实体/push 映射/`TaskDoc`·`TaskMapper` 无此字段；`SchedulerKernel`/`WorkerExecController` 下发不传，7 参兼容构造硬编码 false；仅 CLI `dw run --long-running` 通）→ 062 补全：`TaskDoc`/`TaskMapper`/`TaskDef` 实体+push 复制（创作）+`DispatchCommand`/`SchedulerKernel` claim/`WorkerExecController` 9 参构造/两 gateway 传播 `long_running`+`external_job_handle`（下发生效 detached+reattach）。`specs/062-streaming-task-ops/` |
| 血缘抽取器 · 召回回收分层信封（063） | 自托管小模型 ETL 血缘抽取在 `ml/lineage-extractor/`（041 训练/052-054 蒸馏可用/059 北极星精度超 teacher + 语义 grounding serving）。063 补召回维度：059 后唯一弱项=召回（3B 0.703 vs teacher 0.77-0.81），把 052/054 置信度校准接进 serving 产出**分层复核信封**——`reads/writes`=自动采纳层（治理安全可入库）+`reviewReads/Writes`=复核候选层（并集召回 surface 人工队列）。**免费确定性召回天花板 0.764（探针定界，低于 teacher 带，不追）**；SC-001 达成=3B 自动∪复核召回 0.764 vs 独抽 0.703（+6.1pt）。**★R1 诚实**：无独立非泄漏带标集（测试集 A 删/pool-c-held⊇gold C/pool-c-train 泄漏）→ gold C 嵌套 CV 去偏（`conf_calibration_cv`）；**CV 暴露样本内累计乐观不泛化**——thr=0.95 自动层 held-out P 1.0 但仅 sql_qual（recall 0.047），真膝点 0.85（P 0.87/recall 0.72），召回回收价值全在复核层。`realeval/{calibrate_tiers,tier_classify,rescore_tiered}.py`+`serve/app.py`（env `LINEAGE_AUTOACCEPT_MIN_PRECISION` 默认0.95/`LINEAGE_TIERING`=0回滚）；语料/权重/preds gitignored 走 HF。`specs/063-recall-tiered-envelope/` |
| 血缘论文可投加固 · 泄漏科学脊椎（065） | 把 059/063 三发现（泄漏曲线+诚实去偏+分层信封）加固成可投 MSR/EMSE 实证论文，**不新增 GPU/花费/人工标注**；**列级血缘显式 defer 为 future work**（空壳：gold/评测/serving 全表级）。**头条=泄漏科学（方法学）脊椎，明确不靠「3B 超 teacher」**（gold=teacher 派生=循环论证）。US1 统计诚实层（`eval/significance.py` bootstrap CI + paired-diff + **McNemar 手写精确二项 math.comb 免 scipy**）·US2 工具基线（`eval/baselines/sqllineage_baseline.py`+regex，招牌图=SQL 工具在脚本 recall≈0 结构性失效、模型救回）·US3 可复现 benchmark（`benchmark/` 标签+指针，硬禁 content/合成集）。**★磁盘清空毁原人工 gold A/C → 从零重建 teacher 共识 silver gold C′**（`realeval/{collect_stack,teacher_label,build_gold_b}.py`：the-stack→m1(qwen-max)∩m3(deepseek-v4-pro)双 teacher min-agree=2→130 行/非空 104/空 26；deepseek 官方 Anthropic 端点，`.env` 的 `DEEPSEEK_ANTHROPIC_*`）。真跑：model-3b p **0.743**[0.620,0.856]，vs teacher-m1 precision Δ−0.023 **不显著**（McNemar p=0.096）=诚实站住；**逐规模单调**（0.5/1.5/3B f1 0.416→0.563→0.785）与泄漏曲线（记忆 37→22→11%）**反向耦合**=脊椎最强锚；SC-003 SQLLineage@script r 0.029 vs 模型 Δr 三档全显著。arbitrate 复标空脚本 0 翻标=gold 空侧干净；US4 auto-gold 扩容（`realeval/expand_gold_c.py`，`robustness_only`）SC-006 字面达标但边际混合→印证「默认砍」。证据溯源 `out/PAPER-EVIDENCE.md`（FR-010 无裸数字）；语料/权重/preds/gold gitignored 走 HF。`specs/065-lineage-paper/` |
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
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
at specs/067-agent-incident-ops/plan.md
<!-- SPECKIT END -->
