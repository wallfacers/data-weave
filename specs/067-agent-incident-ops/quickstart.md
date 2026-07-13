# Quickstart: 067 任务失败智能运维——端到端验证指南

前置：Docker（PG+Redis）或 H2 profile；一个可用的 Anthropic/OpenAI 兼容端点（无端点走「降级链路」段）。契约细节见 [contracts/](contracts/)，实体见 [data-model.md](data-model.md)。

## 启动

```bash
cd backend && docker compose up -d && ./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run          # :8000
cd frontend && pnpm dev                            # :4000
```

配置 Agent：设置页（053 血缘 Agent 配置）填协议/端点/模型/密钥并启用 → `PUT /api/incidents/agent-config {"opsEnabled": true}`（或 UI 开关）。

## 场景 1 · 诊断闭环（US1）

1. 建一个必然失败的 SQL 任务（语法错误），上线并等 cron 触发（或手动 run）。
2. 期待：实例 FAILED 后 ≤1 个 sweep 周期（默认 30s）出现事故：`GET /api/incidents?openOnly=true` 有单，state 走 OPEN→ANALYZING；≤5 分钟 `classification=CODE`（或对应分型）+ 证据引用 + 建议（SC-001）。
3. 打开 `http://localhost:4000`：**首屏即监督席指挥中心**（SC-010），feed 可见该事故的思考态/工具 chips/流式诊断（SSE `thinking`/`chip`/`delta` 事件，SC-009 步骤 ≤2s 可见）。

## 场景 2 · 瞬态自愈（US2）

1. 制造一次性失败（如脚本 `exit 1` 后改回、或杀 worker 触发后恢复——注意 infra 类不烧 business_attempt，选业务失败型）。
2. 期待：分型 TRANSIENT → 自动重跑（`agent_action` 有 `incident_rerun` L1 记录）→ 重跑成功 → 事故 RESOLVED(close_kind=AUTO)，全程零人工（SC-002）；线程完整可还原（SC-005）。

## 场景 3 · 资源自愈（US2）

1. 建内存不足失败的引擎任务（如 Spark 小内存跑大数据集，日志含 OOM）。
2. 期待：分型 RESOURCE → `task_def.resources_json` 被调高（≤2 倍、≤16GB 护栏）+ 新版本快照 + 重跑；成功后收口。验证 `dw pull` 取回的 `*.task.yaml` 含新 `resources` 节（FR-007 回流）。

## 场景 4 · 代码修复提案（US2）

1. 用场景 1 的 CODE 分型事故。
2. 期待：事故 AWAITING_APPROVAL + `incident_proposal`(PENDING) + 线程 PROPOSAL 卡（变更内容+证据包）。
3. UI 线程内批准（或 `POST /api/incidents/{id}/proposals/{pid}/approve`）→ 发布新版本 + 自动重跑验证 → VERIFIED + 事故收口。审批单在 `agent_action`（L3, APPROVED）。
4. 反向验证：批准前手工改该任务再批 → 期待 `incident.proposal_stale`，提案 STALE。

## 场景 5 · 不可自愈升级（US3）

1. 数据源填错密码，任务失败。
2. 期待：确定性指纹直接 `CONFIG_CREDENTIAL`，**无任何自动重跑**（`agent_action` 无 rerun 记录），事故 NEEDS_HUMAN + suggestion 指明数据源与操作建议（SC-003）。
3. 修正密码 → 线程点「已处理，请复验」→ Agent 重跑验证 → RESOLVED(HUMAN_ASSISTED)。

## 场景 6 · 对话与战况（US4）

1. 任一事故线程发问「为什么判定是这个原因？」→ 期待 ≤5s 开始流式回复（SC-011），完整轮落 AGENT_SAY。
2. 顶部战况横幅：数字与 `GET /api/incidents/briefing` 的 SQL 实时值一致（SC-010）；触发新事故后 ≤ 防抖窗（60s）综述更新；展开见接班报告。

## 场景 7 · 防循环与降级红线

1. 建重跑必再失败的任务（持续性业务失败）：期待自动处置至 `max-auto-actions`(3) 上限后强制 NEEDS_HUMAN，无第 4 次自动动作（SC-007）。
2. 关闭 `ops_enabled`（或撤掉端点）再触发失败：事故仍创建、state=DIAG_UNAVAILABLE、上下文保留；调度/日志链路行为不变（SC-008 零侵入）。
3. 失败风暴：批量触发 10+ 任务失败，验证并发处理 ≤ `storm-max-inflight`，排队事故如实标记；同任务重复失败归并单事故。

## 回归门

```bash
cd backend && setsid bash -c './mvnw -pl dataweave-master,dataweave-api test >build.log 2>&1; echo $? >build.exit' </dev/null >/dev/null 2>&1 & disown
# H2 与 PG 双 profile 各过一遍；调度红线：每分钟 cron 真跑 started_at−created_at≈0、根节点 attempt=1、零 stragglers
cd frontend && pnpm typecheck && pnpm test
# 浏览器门：Playwright 验首屏=监督席、feed 实时、线程对话、审批按钮
```
