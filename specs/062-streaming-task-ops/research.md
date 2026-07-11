# Research: 实时任务运维（062）

> Phase 0 输出。基于 spec + 4 个 clarify 决策 + 代码深读，记录架构决策、理由、备选与关键研究发现。所有 NEEDS CLARIFICATION 已在 brainstorming 中解决，无遗留。

## 背景与既定约束

- **spec**：`spec.md`（5 US / 12 FR / 6 SC）。在 OpsView 追加独立"实时任务"面板，把 long_running 流式任务提为一等运维对象。
- **4 个 clarify 决策**（见 spec `## Clarifications`）：①续跑硬依赖 061 真实 checkpoint、不兜底全量重跑；②checkpoint 保留最近 N=3（可配置）、续跑可选回滚点；③SUSPENDED 恢复优先续跑、无有效 checkpoint 降级全量重跑；④停止/续跑/终止全 L1 直执、仅审计不审批。
- **060 已合 main**：`task_def.long_running` + `task_instance.external_job_handle` + detached/reattach `FlinkTaskExecutor` + SUSPENDED 非终态保护。062 在其上建可观测/可控面。
- **061 未合 main**：分支确认**零 checkpoint/savepoint 代码**、schema 仍 0.15.0。续跑（US4/FR-007/SC-003）的引擎侧能力硬依赖 061。

## 架构决策

### 决策 1：checkpoint 实体建模 → **新表 `task_checkpoint`**

- **Decision**：独立新表，一对多（一个 task_instance → N 个 checkpoint），按 `task_instance_id` 保留最近 N 条（默认 3，可配置）滚动淘汰。
- **Rationale**：Clarification② 定了"保留 N=3 + 续跑选回滚点"= 天然多对一关系，独立表是唯一能干净表达"每个检查点各有 path / completed_at / status / ordinal"的模型；与 task_instance 解耦（一次停止可产多个 ckpt），面板"检查点列表"直接查此表。
- **Alternatives 否决**：
  - **B. task_instance 扩列**（last_checkpoint_path / _at / count）：只存最近 1 个，**直接违背 Clarification② 的 N=3 + 选回滚点**；多 ckpt 塞 JSON 进现有列是反模式。否决。
  - **C. 复用 external_job_handle JSON 塞 checkpoints 数组**：handle 语义是"当前运行句柄"非"历史快照列表"，污染语义；VARCHAR(512) 装不下 N 个 ckpt 元数据；淘汰/过期/查询全靠 JSON 解析、无法走索引。否决。
- **落地影响**：schema 0.15.0 → 0.16.0（DROP+CREATE 幂等，存量零影响）；061 未合时空表存在无害。

### 决策 2：状态机改动 → **复用 STOPPED + 元数据，不新增状态枚举**

- **Decision**：不新增 `STOPPED_WITH_CHECKPOINT` / `RECOVERING` 等枚举。"保留进度的停止" = STOPPED 终态 + `task_checkpoint` 有有效行；"恢复中" = 复用 060 已有的 reattach → RUNNING 路径。
- **Rationale**：状态枚举（InstanceStates.java:10-29，11 态）被状态机、claim SKIP LOCKED、gauge、前端 badge、`SET_SUCCESS_FROM` 等 ~10 处硬编码集合引用（OpsService.java:91/640/1271；InstanceStates.java:37-40），每加一个态都要同步改全部守卫，扩散风险高。"恢复中"本质就是 RUNNING（FlinkTaskExecutor reattach :268-296 已实现）；"保留进度的停止"是数据建模问题（task_checkpoint 表）不是状态枚举问题。
- **Alternatives 否决**：
  - **A. 新增状态枚举**：语义显式但状态机扩散（isTerminal/claim/gauge/badge/SET_SUCCESS_FROM 全改），高风险低收益。否决。
- **SUSPENDED → 续跑缺口**：060 现状 SUSPENDED 只能 `kill→STOPPED` 或 `rerun→WAITING`（全量重跑清 handle），无续跑 CAS。062 通过决策 4 的 `resumeFromCheckpoint` 新方法 + 一个 `SUSPENDED→WAITING` CAS 解决（见 data-model.md 状态迁移）。

### 决策 3：前端面板形态 → **第 5 tab + fork DataTable 面板**

- **Decision**：ops-view.tsx 的 `TAB_ORDER` 追加 `streamingTasks`；新建 `StreamingTasksPanel`（fork `PeriodicInstancesPanel` 模式：DataTable + server fetcher + 刷新 + 单行/批量操作）。
- **Rationale**：US1 明确要求"与任务实例并列、紧随其后、独立"——tab 是"并列"的精确表达；现有 4 tab 全 DataTable（PeriodicInstancesPanel 是已验证标杆），DataTable 的筛选/分页/批量/刷新全可复用；运维心智一致。
- **Alternatives 否决**：
  - **B. 卡片网格**：与现有运维中心全 DataTable 风格割裂；不利排序/筛选/批量；实时任务多了滚动差。否决。（用户 specify 说的"卡片"理解为"独立面板区域"，落地为 tab 而非字面卡片网格。）
  - **C. 任务实例面板内分区/filter**：违背 US1/FR-001/FR-002"独立入口、与一次性批明确区分"，long_running 混在批实例里正是现状痛点。否决。
- **落地影响**：ops-view.tsx 改 3 处（TabId 联合类型 + TAB_ORDER 数组 + 渲染分支 :64-77）+ 新建 streaming-tasks-panel.tsx；后端新查询端点（见 contracts）。

### 决策 4：OpsService 方法 → **新方法 stopWithSavepoint / resumeFromCheckpoint，保留 killTask/rerunInstance 不变**

- **Decision**：
  - 新增 `stopWithSavepoint(UUID)`：调引擎 savepoint（061 提供）→ 写 `task_checkpoint` 行 → CAS `RUNNING→STOPPED`。失败明确提示"无法保留进度，可改强制终止"（US3 AC3）。
  - 新增 `resumeFromCheckpoint(UUID, UUID checkpointId)`：校验 ckpt 有效 → CAS `STOPPED/SUSPENDED→WAITING`（不清 external_job_handle、记录所选 ckpt）→ 唤醒；无有效 ckpt → 仅提供 `rerunInstance` 全量重跑（FR-008）。
  - 现有 `killTask`（强制 CANCEL，无 savepoint）= 强制终止兜底（FR-006）；`rerunInstance`（清 handle 全量重跑）= 全量重跑。**两者语义不变**（060 已合 main，存量依赖）。
- **Rationale**：US3/US4 的核心是"停止 ≠ 强制终止""续跑 ≠ 全量重跑"，必须在 API 层显式区分；改 kill/rerun 签名会破坏 060 存量且语义双重化违背需求。三者全 L1 直执 + `audit()`（FR-011）——与现状范式一致（OpsService 所有单实例 kill/rerun 本就直调不经 GatedActionService，仅 audit :75 留痕）。
- **Alternatives 否决**：
  - **B. 扩展现有方法 + 参数**（killTask(id, savepoint) / rerunInstance(id, ckptId)）：破坏签名、所有调用方改、语义双重化。否决。

### 决策 5（衍生）：`task_instance.long_running` 快照列 → **加**

- **Decision**：在 task_instance 加 `long_running BOOLEAN` 快照列（与 task_def 一致，实例创建时快照）。
- **Rationale**：实时任务面板查询要按 long_running 过滤；无快照列则每次 JOIN task_def。0.14.0 已有 `task_type` 快照列先例（schema.sql:585）"免 JOIN"。**注意**：`rerunInstance` 的原生 SQL UPDATE（OpsService.java:645-649）显式列出所有重置字段，新列须加入该 SQL，否则 rerun 后残留旧值。
- **落地影响**：schema 同升 0.16.0；rerunInstance SQL 同步。

## 关键研究点（plan 必须处理，非决策）

1. **061 硬阻塞边界（最高风险）**：续跑的 savepoint 实际触发依赖 061（Flink REST `/jobs/{jobId}/stop?targetDirectory=…` savepoint 模式 vs 现有 `PATCH /jobs/{jobId}` CANCEL 模式，端点不同）。062 先交付方法骨架 + `task_checkpoint` 表 + 前端面板 + 日志复用（US1/US2/US5 + US3/US4 骨架），savepoint 实际触发 + ckpt 写入路径待 061。
2. **SSE 日志对 long_running 的适配**：现有 `GET /api/ops/instances/{id}/logs/stream`（OpsController.java:651-700）`Flux.interval(200ms)` 轮询 LogBus + 每 2s 查 DB 状态，对数周级长期连接有资源/漂移风险；非运行态门控（:666-671）对长期 RUNNING 的 long_runing 不理想。US2 AC2"连续 7 天日志不退化"是验收线。前端 `useEventSource` + `InstanceLogView` 可直接复用。
3. **优雅停止异步反馈**：savepoint 完成可能耗时数十秒，`stopWithSavepoint` 须异步（发起 → 轮询 savepoint 状态 → 完成后 CAS STOPPED），前端显示"停止中（保存检查点…）"，避免误判无响应（Edge Case ⑧）。
4. **并发批量限流**：多实例批量停止/恢复参考现有 batch 端点 100 上限（OpsController.java:430）；单实例 CAS 单赢已防护并发（Edge Case ④）。
5. **状态漂移呈现**：worker 失联期间平台侧 state 停留 RUNNING，但引擎侧任务可能仍活（detached）；面板需体现"断连但可能仍在引擎侧运行"，不误判为已停止（Edge Case ⑥/⑦）。

## 代码事实锚点（file:line）

- **OpsService.java**（master application）：`killTask` :521-540、`cancelExternalJobIfNeeded` :546-574（读 task_def.long_running，无 task_instance 快照列）、`cancelFlinkJob` :577-603（PATCH CANCEL 无 savepoint）、`rerunInstance` :635-663（原生 SQL 清 handle 全量重跑，:645-649）、`pauseTask/resumeTask` :494-517（仅 NOT_RUN↔PAUSED）、`audit` :75-88（policyLevel=L0、actor=ops）。
- **InstanceStates.java** :10-29（11 态：NOT_RUN/WAITING/DISPATCHED/RUNNING/SUCCESS/FAILED/STOPPED/PREEMPTED/PAUSED/SKIPPED/SUSPENDED）；`isTerminal` :37-40（SUCCESS/FAILED/STOPPED/SKIPPED，SUSPENDED 非终态）。
- **InstanceStateMachine.java**：`casRequeueInfra` :274-282、`reclaimInfra` :329-338（超 infraMax → casSuspend）、`casSuspend` :285-292（SUSPENDED，failure_reason=INFRA_SUSPENDED）、`casTaskTerminal` :190-222。
- **schema.sql**：schema_version 0.15.0（:2/:139）；`task_instance` :571-614（external_job_handle :597、business_attempt/infra_redispatch_count :593-594、log TEXT :601）；`task_def.long_running` :342、`task_def_version.long_running` :374；`task_type` 快照列先例 :585。
- **ops-view.tsx**：TabId 联合类型 :30、TAB_ORDER :32-37、渲染 :64-77、OpsTabBar :92-113、OpsTopStrip :57。
- **PeriodicInstancesPanel.tsx**（标杆）：DataTable server fetcher :521-554、单行 submitAction :188-206、列定义 :262-516、useRefreshSchedule :173-176、批量 :556-579。
- **FlinkTaskExecutor.java**：detached 提交 :204-231、JobID 正则解析 :446-452、handle 回写 :359-371、reattach :268-296、pollUntilTerminal :303-345、buildCommand :407-433（无 savepoint）、external_job_handle JSON `{jobId, restEndpoint}` :247-249。
- **SchedulerMetrics.java**：`scheduler.instance.suspended` :268-270、`scheduler.node.quarantined` :265-267、`scheduler.infra.redispatch.total` :271-273、sampleGauges @Scheduled 5s :524-530（指标不可变约定 :264）。
- **OpsController.java**：logs/stream :651-700（Last-Event-ID :653/:675、非运行态门控 :666-671）、batch 100 上限 :430、backfill/freeze 走 gatedActionService :492/:590（其余单实例操作直执）。

## 总体推荐（brainstorming 已获用户批准）

决策 1→A（新表）、2→B（复用 STOPPED+元数据）、3→A（第 5 tab）、4→A（新方法）、5→加 long_running 快照列。交付节奏：US1/US2/US5 + US3/US4 骨架先于 061 交付；savepoint 实际触发 + 续跑实际生效待 061 合入。
