# Weft Knowledge Map

This file is the map; details live in `specs/NNN-*/` and the referenced sources. Referenced from [CLAUDE.md](../CLAUDE.md).

| Looking for… | Go to |
|---|---|
| Architecture & layering | [docs/architecture.md](architecture.md) |
| Frontend design / theme vars | [frontend/DESIGN.md](../frontend/DESIGN.md) · `frontend/app/globals.css` |
| Scheduler kernel | `dataweave-master/.../application/` `SchedulerKernel` · `SchedulerMetrics` · `TriggerEngine` · `TimingStrategy` · `MasterRegistry` |
| 节点容错闭环（060） | `SlotManager` 节点可用性单点门（判据 `NodeHealthService.isAvailable`：心跳新鲜+稳定窗+熔断隔离）。**计数三拆（硬）**：`attempt`=下发纪元栅栏 · `business_attempt`=业务重试 · `infra_redispatch_count`=infra 重派；infra 回收永不判终态、超 `infra-redispatch-max`→`SUSPENDED`。节点回收 `FleetService`/`LeaseReaper`；RUNNING 续约 `HeartbeatReporter`；Flink `long_running`+`external_job_handle` reattach；schema `0.15.0`；`specs/060-node-eviction-failover/` |
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
| How to run | [README.md](../README.md) |
| 大数据任务类型真跑验证（061） | 7 类执行器（Sql/Hive/Spark/Python/Flink/DataX/SeaTunnel）逐引擎真跑取证 + 台账 `evidence/{LEDGER.md,ledger.json}`（脱敏，第三者可判真伪）；三态硬门 SUCCESS/FAILURE/SKIPPED；harness `verification/061-task-types/`；Flink `executeLongRunning` 去桩真验（T038）；`specs/061-task-type-verification/` |
| 实时任务运维（062） | OpsView「实时任务」面板把 `long_running` 提为一等运维对象：集中视图/优雅停止(保 checkpoint)/检查点续跑/SUSPENDED 一等化。`task_checkpoint` 表 + `OpsService.stopWithSavepoint`/`resumeFromCheckpoint`（`InstanceStateMachine.casResumeFromCheckpoint` **不动 attempt/business_attempt** 守 060 红线）；创作→下发链路补全（`TaskDoc`/`TaskMapper`/`TaskDef`+push+两 gateway 传播 `long_running`）；schema `0.16.0`；`specs/062-streaming-task-ops/` |
| 血缘抽取器（ML 研究线，`ml/lineage-extractor/`） | 自托管小模型 ETL 血缘抽取：041 训练 · 052-054 蒸馏 · 059 精度超 teacher + grounding serving · **063** 分层复核信封（`serve/app.py` env `LINEAGE_AUTOACCEPT_MIN_PRECISION`/`LINEAGE_TIERING`）· **065** 泄漏科学论文脊椎（`eval/significance.py`+`realeval/`，gold=teacher 共识银标）· **067** 列级血缘重训（`metrics.py` 独立列打分块，表级 8 key 不变；`sft_qlora.py --lora-r/--lora-alpha`）· **068** 三厂商共识 gold（GPT-5.6 第三独立厂商破 067 循环，`llm/clients.py` httpx 裸 POST 绕 WAF；一致率 0.976/0.958）+ 列级 frontier（`data/thin_columns.py` 密度扫描证 3B/LoRA 表列尖锐双区不可兼得，交付列专家+表专家两点；`governance_routing.py` 缓解限制②）+ **US5 表结构 loss 加权**（`sft_qlora.py --table-loss-weight`：答案内表名 token loss 加权 W=3 顶回被列名淹没的表梯度；**严格支配密度稀释**同表召回列 F1 高+0.15，得最佳均衡单模型 `run-tri-3b-lw3` 表F1 0.781/列F1 0.825；严格两门同过仍不可达=两次逃逸双证 3B 容量墙，7B 出路）。语料/权重/preds/gold gitignored 走 HF；`specs/{063,065,067,068}-*/` |
| **Authoritative schema** | `backend/dataweave-api/src/main/resources/schema.sql` — single authoritative DDL; no migration scripts. `schema_version` single-row table, strict SemVer (baseline `0.0.1`); any table change bumps the version; DB row / file header / project version must stay equal |
