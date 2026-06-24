## Context

DataWeave 已有较完整的运维后端端点(`OpsController`:summary / instances / failed / eta-summary / pause·resume·kill·rerun·recover / log / SSE 流),但前端能力散落在 cockpit(已被血缘态势占用)、freshness、fleet、diagnosis;`components/ops/instance-table.tsx` 等「周期实例运维」零件是无视图引用的**孤儿**。对照 DataWorks 运维中心,缺统一入口、补数据、批量/置成功。

平台立身之本是 **Agent-native**(weave data with Agents)。因此运维中心走 **B 路线**:不是人肉盯盘的控制台,而是「Agent 巡检 → 举手提建议 → PolicyEngine 闸门 → 人批准/Agent 自动执行」的闭环;控制台(实例表/批量)是 Agent 的「手」和人工兜底,不是主角。

本变更要被 **3 个外部 Agent(claude code CLI)并行实现**。因此设计的首要约束不是工作量均匀,而是**消除共享可变状态**:三棵不相交目录树 + 三份开工前冻结的契约。

## Goals / Non-Goals

**Goals:**
- M1 交付:统一 `ops` view、周期实例运维(筛选/分页/批量/置成功)、补数据 backfill、冻结/解冻、Agent 运维举手台、顶条今日大盘。
- 三个 Stream 文件零重叠,可真并行;契约先行,各自可独立自验(JUnit / WebTestClient / browser gate-mock)。
- 所有写操作无旁路,必经 `GatedActionService` 闸门 + `agent_action` 审计。
- 严守调度器四条死锁不变量。

**Non-Goals(推迟 M2):**
- 甘特图 / 运行时长趋势。
- 智能监控基线、SLA 达成率、独立告警规则中心 / 事件中心 / 值班表(M1 仅打通「巡检→举手台」一条最小链路,不做规则配置 UI)。
- 实时任务运维、引擎运维。
- 既有 cockpit 血缘态势视图的改造(运维中心是**新** view,不动 cockpit)。

## Decisions

### D1. 切分维度:按 DDD 层 + 前后端分离切成 3 棵不相交目录树

候选方案:(a) 按垂直功能切(每人一个 feature 全栈)——会让三人都改 OpsController/前端,冲突严重;(b) 按 DDD 层 + 模块切——目录天然不相交。选 **(b)**。

```
Stream A 调度内核     dataweave-master/**  dataweave-worker/**  backend schema.sql(独占)
Stream B 运维前端     frontend/**
Stream C 接口+Agent   dataweave-api/**     dataweave-alert/**
```

- `schema.sql` 由 **Stream A 独占**:一次性建齐本模块所有表/列(含 M2 预留),杜绝多人改同一 DDL 文件 + 规避「schema.sql 追加被 linter 剥掉」的历史坑。
- 前端 `messages/{zh-CN,en-US}.json` 由 **Stream B 独占**。
- 后端 `Messages`(AG-UI markdown / 审批理由)各模块用各自资源,Stream C 负责其新增键。

### D2. 三份冻结契约(开工前写死,开工后只读不改)

**契约① REST(Stream C 实现 / Stream B 消费)** —— 统一写端点返回 `{code, data, outcome}`,`outcome ∈ EXECUTED | PENDING_APPROVAL | REJECTED`。

```
GET  /api/ops/instances?runMode=&state=&taskId=&bizDate=&page=&size=   周期实例分页筛选
        → { code, data: { items: InstanceRow[], total, page, size } }
POST /api/ops/instances/batch       body { ids: UUID[], op: "rerun"|"kill"|"set-success" }
        → { code, data: BatchResult, outcome }
POST /api/ops/backfill              body BackfillRequest
        → { code, data: BackfillRun, outcome }
GET  /api/ops/backfill?page=&size=  → { code, data: { items: BackfillRun[], total } }
GET  /api/ops/backfill/{runId}      → { code, data: { run: BackfillRun, instances: InstanceRow[] } }
POST /api/ops/tasks/{taskId}/freeze body { frozen: boolean }
        → { code, data: TaskDef, outcome }

InstanceRow      = { id, taskDefId, taskDefName, workflowId?, runMode, state, bizDate, startedAt?, finishedAt?, durationMs? }
BackfillRequest  = { targetType: "task"|"workflow", targetId, dateStart, dateEnd,
                     includeDownstream: boolean, parallelism: number(1..N) }
BackfillRun      = { id, targetType, targetId, targetName, dateStart, dateEnd, parallelism,
                     state: "RUNNING"|"SUCCESS"|"FAILED"|"PARTIAL", total, success, failed, running, createdAt }
BatchResult      = { requested, accepted, results: [{ id, outcome, approvalId? }] }
```

**契约② Java Service 签名(Stream A 暴露 / Stream C 调用)** —— 在 `dataweave-master/application`:

```java
BackfillRun submitBackfill(BackfillRequest req);          // 校验 + 落 backfill_run + 生成子实例 + 触发调度
BatchResult batchOp(List<UUID> instanceIds, BatchOp op);  // op ∈ RERUN|KILL|SET_SUCCESS,逐个走闸门
TaskInstance setSuccess(UUID instanceId);                  // CAS 推进 SUCCESS + 唤醒下游 WAITING
TaskDef setFrozen(Long taskDefId, boolean frozen);        // 置位 frozen 列
Page<InstanceRow> queryInstances(InstanceQuery q);        // 多维筛选 + 分页
BackfillRun backfillRun(UUID runId);                      // run + 子实例
List<BackfillRun> backfillRuns(int page, int size);
```
注:`batchOp`/`submitBackfill`/`setFrozen` 的闸门提交由 **Stream C 在 interfaces 层**构造 `ActionRequest → GatedActionService.submit`;Stream A 的 service 方法本身只做领域动作。若某动作在闸门外不安全(如直接改状态),Stream A 提供「已鉴权」内部方法,Stream C 负责闸门前置。**接缝细节:Stream A 的 service 不依赖闸门;闸门在 C 的 controller 调 A 之前完成裁决。**

**契约③ AG-UI 事件 schema(Stream C 发 / Stream B 渲染)**

```
CUSTOM name="dataweave.ops.alert"
  payload = { id, kind: "INSTANCE_FAILED"|"SLA_RISK"|"BACKFILL_DONE",
              severity: "info"|"warning"|"error",
              title, detail, instanceIds: UUID[],
              suggestedAction?: { op: "rerun"|"kill"|"set-success"|"backfill", params } }
CUSTOM name="dataweave.ui.open"   payload = { view: "ops", params?: { tab?, filter? } }
```
举手台卡片由 C 后端巡检发 `ops.alert`;B 渲染卡片 +「批准」按钮回调契约①的 batch/backfill 端点;前端按 `outcome` 分流(EXECUTED→成功提示,PENDING_APPROVAL→待批态,REJECTED→拒绝态)。

### D3. 补数据(backfill)死锁安全设计

- 生成:对 [dateStart, dateEnd] 每个 bizDate × 目标任务,INSERT 一条 `NOT_RUN`/`WAITING` 实例,`run_mode='BACKFILL'`,`$bizdate` 参数按日期注入(规避「bizDate 缺省致无日志」坑)。父记录 `backfill_run` 跟踪聚合进度。
- 依赖编排:`includeDownstream=true` 时,同一 bizDate 内的下游实例依赖同日期上游(复用既有就绪判定);跨 bizDate 不强制串行,由 `parallelism` 控制并发提交。
- 认领与执行:复用 `SchedulerKernel` 既有 SKIP LOCKED 认领 + worker 执行路径,BACKFILL 仅作 runMode 标识,**不新增认领逻辑**(降低碰死锁不变量风险)。
- 进度:`backfill_run` 的 total/success/failed/running 由子实例状态聚合(查询时 count,不维护可变计数器,避免一致性问题)。

### D4. 置成功(set-success)的 CAS 与下游唤醒

- 仅允许从非成功态(FAILED/STOPPED/RUNNING/PREEMPTED)经**乐观 CAS**(`WHERE state=?`)推进到 SUCCESS;NOT_RUN/WAITING 不允许(无运行事实)。
- 推进后必须**唤醒等待该实例的下游 WAITING**(复用既有就绪重算 + `dw:wake`),否则 DAG 卡死。
- 事务内只落状态,唤醒/下发在事务外(死锁不变量④)。
- set-success 是写操作 → 默认走闸门;未配 `policy_rules` 时按 L2→PENDING_APPROVAL(「回滚等写操作默认 L2」记忆),前端必须按 outcome 分流。

### D5. 冻结(freeze)= claim 跳过,不删不停

`task_def` 新增 `frozen boolean default false`。`SchedulerKernel` 在 claim/生成阶段 `WHERE frozen=false` 过滤。冻结只是「不生成新实例 / 不认领」,在途实例不受影响;解冻后恢复。冻结不持锁、不等待,不违反死锁不变量。

### D6. 前端:救活孤儿组件,`ops` 作为新 Pinned 候选 view

- `lib/workspace/views.ts` 注册 `ops`(不设 defaultPinned,经 `dataweave.ui.open` 召唤或「+」启动器打开)。
- 复用 `components/ops/instance-table.tsx` 等孤儿件,补筛选/分页/批量栏;下钻仍复用 `workflow-instance-detail` / `instance-log`。
- 举手台复用 cockpit 既有「Agent 举手台」视觉模式(右栏卡片流)。
- 严守前端栈门:CopilotKit v2、hugeicons、base-style `render` prop、语义 token;新 copy 走 next-intl 双键齐全;无省略号表「进行中」。

### D7. 集成时序

```
T0  落盘契约①②③(本 design.md)→ 三个 Agent 各持一份 brief 开工
T1  并行:A(JUnit)· B(mock fetch + browser gate-mock)· C(对 A 签名写桩 + WebTestClient)
T2  集成串联:A `./mvnw install -DskipTests` → C 编译真接口 → 起后端(setsid 脱离)→ B 跑真 browser gate
```

## Risks / Trade-offs

- [补数据触碰调度内核,可能违反死锁不变量] → D3 复用既有认领/执行路径,BACKFILL 仅作标识;新增逻辑只在「生成」侧(纯 INSERT),不改认领 CAS。
- [契约开工后变更导致三方返工] → 契约②的 Java 签名 + ①的 DTO 在 design.md 冻结;变更须三方同步,记为正式 change-review,不允许单方改。
- [Stream C 依赖 A 未完成的 service] → C 对冻结签名写桩接口,编译期不阻塞;集成期才需 A 的真实现(T2)。
- [set-success 漏唤醒下游致 DAG 卡死] → D4 强制唤醒 + 用既有 InstanceStateMachine 唤醒路径的测试覆盖。
- [前端只看 code===0 误判待批为成功] → 契约统一 outcome 字段,B 必须三态分流;加 vitest/手测覆盖 PENDING_APPROVAL 分支。
- [schema.sql 追加被 linter 剥] → A 独占且用 `CREATE ... IF NOT EXISTS` / `ALTER ... ADD COLUMN`,H2+PG 各测一遍(方言坑:拼接用 CONCAT)。
- [H2 与 PG 方言差异] → A 的持久化测试两库各跑一遍。

## Migration Plan

- 新增表 `backfill_run` + `task_def.frozen` 列,DDL 用 `IF NOT EXISTS` / 幂等 `ALTER`,H2 与 PG 兼容。
- 无数据回填需求;`frozen` 默认 false,既有任务行为不变。
- 回滚:`ops` view 未注册即不可达;后端新端点未被调用即无副作用;`frozen` 列保留无害。
- 灰度:M1 上线后,举手台巡检默认只对失败实例发 info/warning 卡片,不自动执行(全部走闸门待批),观察一轮再考虑 L0/L1 自动化。

## Open Questions

- 补数据是否需要「暂停整个 backfill run」的总控?(M1 先支持对子实例批量 kill,run 级总控留观察)
- 举手台巡检触发频率与去重策略(轮询 vs 订阅状态流)?M1 拟复用 `workflow-instances events/stream` 的状态变更触发,失败即评估发卡片。
- `parallelism` 上限是否需与 worker fleet 容量联动?M1 取固定上限(如 ≤10),M2 再做容量自适应。
