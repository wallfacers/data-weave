## 0. 契约先行(开工前,主 Agent 完成 → 冻结)

- [x] 0.1 在 design.md 冻结契约①REST(端点 + DTO 形状)、契约②Java service 签名、契约③AG-UI 事件 schema
- [x] 0.2 三个 Stream 各持一份 brief(目录边界 + 自己消费/暴露的契约),确认开工后不改契约
- [x] 0.3 约定集成时序:A install → C 编译真接口 → 起后端(setsid 脱离)→ B browser gate

---

## 1. Stream A — 调度内核(dataweave-master / dataweave-worker / schema.sql 独占)

### 1.1 数据模型(schema.sql 独占,H2+PG 兼容,IF NOT EXISTS / 幂等 ALTER)

- [x] 1.1.1 `ALTER TABLE task_def ADD COLUMN frozen boolean DEFAULT false`(幂等)
- [x] 1.1.2 新建 `backfill_run` 表(id/targetType/targetId/targetName/dateStart/dateEnd/parallelism/state/createdAt)
- [x] 1.1.3 task_instance 关联 backfill_run 的外键列(`backfill_run_id` 可空)
- [x] 1.1.4 (M2 预留)告警相关表注释占位,本期不建实体
- [x] 1.1.5 H2 与 PG 各跑一遍 DDL,验证方言(拼接用 CONCAT,无 `||`)

### 1.2 置成功 + 冻结(application/domain)

- [x] 1.2.1 状态机新增 `setSuccess`:FAILED/STOPPED/RUNNING/PREEMPTED → SUCCESS 乐观 CAS(`WHERE state=?`),NOT_RUN/WAITING 拒绝
- [x] 1.2.2 setSuccess 后唤醒下游 WAITING(复用就绪重算 + `dw:wake`),事务内只落状态、唤醒在事务外
- [x] 1.2.3 `OpsService.setFrozen(taskDefId, frozen)` 置位 frozen 列
- [x] 1.2.4 `SchedulerKernel` claim/生成阶段加 `WHERE frozen=false` 过滤

### 1.3 批量操作(application)

- [x] 1.3.1 `OpsService.batchOp(ids, op)`:op∈RERUN|KILL|SET_SUCCESS,逐项调既有/新转移,返回 `BatchResult`(逐项结果,部分失败不阻塞其余)

### 1.4 补数据(application/domain)

- [x] 1.4.1 `BackfillService.submitBackfill(req)`:校验区间/目标 → 落 `backfill_run` → 每 bizDate×目标 INSERT BACKFILL 实例,`$bizdate` 按日期注入
- [x] 1.4.2 includeDownstream=true 时同 bizDate 下游依赖同日期上游(复用就绪判定);parallelism 控制跨 bizDate 并发
- [x] 1.4.3 `backfillRun(runId)` / `backfillRuns(page,size)`:进度由子实例状态聚合(查询时 count)
- [x] 1.4.4 worker 执行路径确认对 BACKFILL 无差异(仅 runMode 标识)

### 1.5 查询服务(application)

- [x] 1.5.1 `queryInstances(InstanceQuery)`:runMode/state/taskId/bizDate 多维筛选 + 分页,返回 `Page<InstanceRow>`

### 1.6 Stream A 自验(JUnit5 + AssertJ)

- [x] 1.6.1 setSuccess 各起始态 + CAS 竞争 + 下游唤醒 测试
- [x] 1.6.2 freeze claim 跳过 + 解冻恢复 测试
- [x] 1.6.3 backfill 生成数量/依赖编排/$bizdate 注入 测试
- [x] 1.6.4 batchOp 部分待批/部分执行 逐项结果 测试
- [x] 1.6.5 持久化层 H2 + PG 各跑一遍(`./mvnw -q -pl dataweave-master test`)

---

## 2. Stream B — 运维前端(frontend/** 独占)

### 2.1 视图注册与壳

- [x] 2.1.1 `lib/workspace/views.ts` 注册 `ops`(不 defaultPinned)+ `registry.tsx` 组件映射 + `views.*` tab 标题键
- [x] 2.1.2 `ops-view.tsx`:顶条今日大盘(summary+eta)+ 主舞台 Tab + 右栏举手台 三段布局
- [x] 2.1.3 `dataweave.ui.open` 召唤 `{view:"ops", params:{tab,filter}}` 去重激活并预置筛选

### 2.2 周期实例面板(救活孤儿)

- [x] 2.2.1 接入 `components/ops/instance-table.tsx` 等孤儿件,补筛选栏(runMode/state/taskId/bizDate)+ 分页
- [x] 2.2.2 多选 + 批量操作栏(rerun/kill/set-success),调 `POST /api/ops/instances/batch`
- [x] 2.2.3 按 `outcome` 三态分流:EXECUTED/PENDING_APPROVAL/REJECTED(不只看 code===0)
- [x] 2.2.4 下钻复用 `workflow-instance-detail`/`instance-log`

### 2.3 补数据与举手台

- [x] 2.3.1 补数据弹窗(目标 + 日期区间 + includeDownstream + parallelism)→ `POST /api/ops/backfill`,按 outcome 分流
- [x] 2.3.2 「补数据实例」Tab:run 列表 + 进度 + 下钻子实例
- [x] 2.3.3 右栏举手台:渲染 `dataweave.ops.alert` 卡片(severity 着色 + 建议动作按钮回调 batch/backfill),复用 cockpit 举手台模式
- [x] 2.3.4 「周期任务」Tab:冻结/解冻开关 → `POST /api/ops/tasks/{id}/freeze`

### 2.4 i18n + 栈门 + 自验

- [x] 2.4.1 `messages/{zh-CN,en-US}.json` 新增 `ops.*` 命名空间键,双 bundle 键集一致
- [x] 2.4.2 栈门核对:CopilotKit v2 / hugeicons / base-style render prop / 语义 token / 无省略号表进行中
- [x] 2.4.3 `pnpm typecheck` 零错误
- [x] 2.4.4 browser gate(mock fetch + mock SSE):ops view 渲染、批量待批分流、补数据弹窗、举手台卡片;截图入 tmp/ 用后清理

---

## 3. Stream C — 接口层 + Agent 闭环 + 监控(dataweave-api / dataweave-alert 独占)

### 3.1 REST 端点(interfaces,调 Stream A service 签名,可先写桩)

- [x] 3.1.1 `OpsController` 新增 `GET /instances`(筛选分页)、`POST /instances/batch`
- [x] 3.1.2 `POST /backfill`、`GET /backfill`、`GET /backfill/{runId}`
- [x] 3.1.3 `POST /tasks/{id}/freeze`
- [x] 3.1.4 DTO + 统一返回 `{code, data, outcome}`(outcome∈EXECUTED|PENDING_APPROVAL|REJECTED)
- [x] 3.1.5 写端点构造 `ActionRequest → GatedActionService.submit` 闸门前置,裁决后调 A 的 service

### 3.2 Agent 闭环

- [x] 3.2.1 巡检:实例进 FAILED 时(复用 events/stream 状态变更触发)评估并发 `CUSTOM(dataweave.ops.alert)`,按 实例+kind 去重
- [x] 3.2.2 AG-UI 事件出口:`dataweave.ops.alert` + `dataweave.ui.open(view:"ops")` 经同一 `AguiEvents`
- [x] 3.2.3 MCP 运维工具注册:查询工具直查 domain service,写工具经 `GatedActionService`
- [x] 3.2.4 `IntentRouter` mock 分支:查失败实例 / 重跑·补数据建议,与真脑同构事件

### 3.3 监控(M1 最小链路)

- [x] 3.3.1 SlaService 预测接巡检:运行中实例预测超 SLA 时发 `ops.alert{ kind:"SLA_RISK" }`(规则配置 UI 留 M2)
- [x] 3.3.2 dataweave-alert 仅打通「巡检事件 → 通知渠道(LogNotificationChannel)」最小链路

### 3.4 Stream C 自验

- [x] 3.4.1 WebTestClient(带 JWT,JwtTestSupport)覆盖新端点:契约 200 + `$.code`/`$.data`/`$.outcome`
- [x] 3.4.2 闸门分流测试:L0/L1 EXECUTED、L2 PENDING_APPROVAL、L4 REJECTED,留 `agent_action`
- [x] 3.4.3 MCP tools/call 烟测;mock IntentRouter 运维分支烟测

---

## 4. 集成与收尾(契约消费方汇合,串联)

- [x] 4.1 Stream A `./mvnw install -DskipTests` → C 编译真实接口(替换桩)零错误
- [x] 4.2 起后端(setsid 脱离进程组)→ B 跑真 browser gate:ops view 真数据、批量、补数据、举手台端到端
- [x] 4.3 全栈契约核对:三态 outcome 分流、补数据 $bizdate 有日志、set-success 唤醒下游
- [x] 4.4 `openspec validate data-ops-center` 通过;更新 MEMORY.md(如有新坑)
