## Context

任务 `content` 当前的完整流动链路(经代码确认):

```
CronScheduler.tick() / 手动触发
   │  算 due(定时触发点)、biz_date = due - 1天 (yyyy-MM-dd, T-1)
   ▼
WorkflowTriggerService.trigger(wf, "CRON", bizDate, priority)
   │  for each DAG node:
   │     TaskDef task = taskDefRepository.findById(node.getTaskId())   ← 取 content + paramsJson
   │     new TaskInstance  → ti.setBizDate(bizDate)                    ← biz_date 存进实例(孤儿)
   │     ↓ (调度内核认领后)
   ▼
SchedulerKernel.runRound()  →  txTemplate.execute( claimAndMark() )     ← 事务内认领
   │     assign()  →  CAS 置 DISPATCHED
   │        out.add(new DispatchCommand(r.id, attempt, code, r.taskId,
   │            r.taskVersionNo, r.runMode, r.bizDate, contentOf(r), ...))   ★ 替换就插这（第 194 行）
   ▼
gateway.dispatch(cmd)   ── InProcessTaskExecutionGateway (all-in-one)
   │                      └─ DistributedTaskExecutionGateway (WebClient → worker)
   ▼
ExecutionContext(cmd.content(), cmd.bizDate(), attempt, timeout)   ← content 透传,bizDate 透传但未用
   ▼
ShellTaskExecutor:  bash -c ctx.content()                          ← ${xxx} 原样进 bash,不会替换
```

现状的四个关键事实:

1. **`TaskInstance` 不存 `content`**,只存 `taskId` + `taskVersionNo` 引用;`content` 模板永远在 `TaskDef`(版本冻结)。
2. **`DispatchCommand` 含 `content` 字段**,唯一构造点是 `SchedulerKernel.assign()`(认领事务内);in-process / distributed 两条执行路径的共同上游。
3. **`paramsJson` 全库无执行消费点**:前端 `frontend/src` grep 零结果,`data.sql` 三个任务的 `params_json` 均为 `NULL`,IntentRouter 也不构造——字段空着,结构由本变更定义。
4. 因此 `${xxx}` 会原样进入 `bash -c`,不替换。

结论:`biz_date`、`DispatchCommand.content`、`paramsJson` 都已就位,替换是纯增量。

## Goals / Non-Goals

**Goals:**

- 实现 `${...}` 业务日期语法(格式化 + 整数偏移)、系统内置参数、自定义参数递归展开。
- 替换在 master 下发执行前完成,进程内 / 分布式 / worker 全链路透明复用。
- 满足 DataWorks「实例生成即冻结」语义。
- 零 schema 改动、零 worker 改动、零新依赖。

**Non-Goals:**

- `$[...]` 定时时间秒级语法、`add_months`、补数据 `$[...]` 基准偏移(二期)。
- 改动 cron 引擎或 cron 表达式格式(Spring 6 字段已够用)。
- 把替换后的 `content` 文本持久化进 `task_instance`(本期冻结输入而非文本;日志已存 stdout 尾部可供排查)。
- 跨节点参数传递 / 赋值节点(DataWorks 的下游引用上游出参)。
- **字面占位符嵌套 `${${...}}`**(用展开结果当参数名)—— DataWorks 不支持、无实际用例、语义歧义,明确不实现。

## Decisions

### D1 — 替换点:`SchedulerKernel.assign()` 构造 `DispatchCommand` 处(master 下发前,事务内),非实例创建、非 worker

精确位置:`SchedulerKernel.assign()` 第 194 行 `new DispatchCommand(..., contentOf(r), ...)`。这是 in-process(`InProcessTaskExecutionGateway`)与 distributed(`DistributedTaskExecutionGateway`)两条执行路径的**唯一共同上游**;`r`(认领查询 `selectRunnable` 返回的 Row)同时携带 `bizDate`、`content`(来自 task_def join)、`paramsJson`,替换所需输入齐全,一处替换两路复用、worker 与执行器无感。

考虑的替代:

- (a) 实例创建时快照替换并存 `task_instance.resolved_content` —— 需加 schema 字段;且 content 模板本就不存实例,引入冗余。
- (b) worker 侧替换 —— 需让 worker 读 `paramsJson` 并理解替换语义,破坏 worker 的「无状态执行器」边界,且与进程内模式重复实现两遍。

### D2 — 冻结语义靠「冻结替换输入」,而非「冻结 content 文本」

替换输入 = `biz_date`(存于 `task_instance`,实例生成时定死)+ `taskVersionNo` 指向的 `content` / `paramsJson` 快照。这三者都不随实例实际启动时刻(排队、重试延迟)变化,故替换结果天然冻结——等价于 DataWorks 语义,且无需持久化替换文本。

### D3 — 本期只实现 `${...}` 业务日语法,不做 `$[...]`

`${...}` 基于 `biz_date`,而 `biz_date` 已在实例中存在,**本期零数据缺口**。`$[...]` 基于定时时间(精确到秒),而 `cyctime`(定时触发点 `due`)目前未持久化到 `task_instance`;做 `$[...]` 须先补字段。`$[...]` 覆盖的是小时级增量场景,占离线 T+1 的少数,留二期。

### D4 — 语法对齐 DataWorks

格式 token 用 `yyyy / mm / dd`(无 hh / mi / ss),偏移用 `${<fmt>±N}`(N 对应最小单位),`$bizdate`≡`${yyyymmdd}`。降低 DataWorks 用户迁移成本。

### D5 — 自定义参数递归展开(任意深度,非仅两级)

代码 `${dt}` → `paramsJson` 定义 `dt=${yyyymmdd-1}` → 递归展开。**展开后的值若仍含 `${...}`,继续递归展开**(如 `biz_pt=dt=${biz_dt}` → `dt=${yyyymmdd-1}` → `dt=20250313`),带访问栈做循环检测。命名参数可组合复用、贴近 DataWorks;`paramsJson` 字段已存在,本变更赋予它执行语义。**字面嵌套 `${${...}}`(展开结果当参数名)不支持**——见 Non-Goals。

### D6 — cron 表达式不改

Spring `CronExpression`(6 字段,秒级)已强于 DataWorks(分钟级)。但 DataWorks 用户习惯 Quartz cron 的 `?` / `L` / `W`,Spring 不支持——以文档 + 前端校验提示,不做引擎级兼容(避免引入 Quartz 依赖)。注:`data.sql` 样例 cron `0 0 2 * * ?` 带 `?`,当前 `CronScheduler` 用 Spring 已 parse 失败(task-core-capabilities 遗留,本变更不修,仅记录)。

### D7 — paramsJson 结构固化为 Map

`task_def.params_json`(`VARCHAR(2000)`)固化为 `{"name":"expr"}` 形式(如 `{"dt":"${yyyymmdd-1}"}`)。查找 O(1)、前端 name→expr 表单直觉一致、对齐 DataWorks 的 key=value 语义;存量值全为 NULL,无迁移。不取 `[{name,value}]` 数组形式(params 无需保序、无额外字段需求)。

## Risks / Trade-offs

- **[content 不存实例 → 重跑 / 审计看不到「实际执行了什么」]** → 治理:`task_instance.log` 已存 stdout 尾部;实例详情可按需用冻结的 `biz_date` + `taskVersionNo` 回放替换结果(只读计算,无持久化成本)。二期如需可加 `resolved_content` 快照字段。
- **[未解析的占位符 → 静默执行错误命令]** → 策略:`content` 里出现 `${x}` / `$bizdate` 但 `x` 既非内置也非 `paramsJson` 定义时,**替换失败 → 实例 `FAILED`**,`errorMessage` 标注未解析占位符,绝不静默进入 `bash -c`。
- **[`$bizmonth` 跨月特判]** → 严格复刻 DataWorks 规则(业务日期月份 == 当前月份时取上月,否则取业务日期月份),配单测覆盖边界。
- **[DataWorks Quartz cron 不兼容]** → `?` / `L` / `W` 会让 Spring parse 失败;前端任务编辑器做 cron 校验 + 提示,文档给出转换示例。
- **[替换在认领事务内 → 失败连坐同批次]** → `SchedulerKernel.assign()` 在 `txTemplate.execute` 内;替换失败若抛穿,整批认领回滚、连坐同批次所有任务。策略:`assign` 内 catch 解析异常 → 仅把该实例 CAS 置 `FAILED` + `errorMessage` 写占位符名,不抛、不回滚,其余任务照常下发。

## Migration Plan

- **纯增量,无数据迁移**:`DispatchCommand` 替换是新增的一次调用;`content` 无 `${}` 时 resolver 原样返回,对存量任务零影响。
- **回滚**:移除 `SchedulerKernel.assign()` 中对 resolver 的调用即可,无 schema、无数据残留。

## Open Questions

均已解决(review 阶段确认):

- ~~`DispatchCommand` 的精确构造点~~ → **已确认**:`SchedulerKernel.assign()` 第 194 行,in-process / distributed 唯一共同上游。
- ~~`paramsJson` 的 JSON 结构~~ → **已确认**:`{"name":"expr"}`(Map);`task-edit-drawer` 尚未实现该字段(前端 grep 零结果),本变更新建。
- `triggerTestRun`(手动测试跑)是否经 `DispatchCommand` 路径:apply 时确认;若经则自动复用替换,若同步直接执行则在该路径也接入 resolver。
