## Why

DataWeave 的运维能力目前是「散装」的:后端 `OpsController` 已有实例 pause/resume/kill/rerun/recover、summary、eta-summary 等丰富端点,但前端把这些能力拆散铺在 cockpit / freshness / fleet / diagnosis 等视图里,而真正的「周期实例运维」面板(`components/ops/instance-table.tsx` 等)是**没有任何视图引用的孤儿组件**。对照 DataWorks 运维中心,我们缺三块硬能力:**统一的运维中心入口、补数据(backfill)、批量操作/置成功**。同时,作为 Agent-native 平台,运维不应是「人肉盯盘的控制台」,而应是「Agent 巡检发现异常 → 举手提修复建议 → 走 PolicyEngine 闸门 → 人一键批准 / Agent 自动执行」的闭环(B 路线)。

## What Changes

- 新增统一运维中心视图 `ops`(Workspace 新 view),顶条今日大盘 + 主舞台 Tab(周期实例 / 补数据实例 / 手动·测试实例 / 周期任务)+ 右栏 **Agent 运维举手台**。
- 救活并接入孤儿组件,做成「周期实例」面板:多维筛选(runMode/state/taskId/bizDate)+ 分页 + **批量操作**(rerun/kill/**set-success 置成功**)。
- 新增**补数据(backfill)**:新增 runMode `BACKFILL` + `backfill_run` 父记录;按 任务/工作流 × 日期区间 生成实例,组内同 bizDate 尊重上下游依赖,可配并发度;补数据 run 列表 + 进度下钻。
- 新增**冻结/解冻**周期任务:`TaskDef.frozen`,调度器 claim 时跳过(冻结=不生成/不认领,等待不占资源)。
- 新增 **Agent 运维闭环**:后端巡检失败/超时实例 → 通过 AG-UI `CUSTOM(dataweave.ops.alert)` 事件推送举手台卡片 → 前端「批准」回调批量端点 → 经 `GatedActionService` 闸门(L0/L1 直接执行,L2/L3 待批,L4 拒)。新增 MCP ops 工具,让 workhorse 真脑也能驱动同一套运维动作。
- **范围切分**:本变更聚焦 **M1**(实例运维 + 补数据 + 冻结 + 举手台 + 顶条大盘)。**甘特图、智能监控基线/达成率、独立告警事件中心推迟到 M2**。
- **并行交付设计**:本变更被切成 3 个互不相交的 Stream(目录树零重叠 + 3 份冻结契约),供 3 个外部 Agent 并行实现。详见 design.md。

## Capabilities

### New Capabilities
- `ops-instance-management`: 周期实例的多维筛选、分页、批量操作(rerun/kill)、置成功(set-success,CAS 推进并唤醒下游),后端纯逻辑 + 写操作经闸门。
- `data-backfill`: 补数据能力 —— `BACKFILL` runMode、`backfill_run` 父子记录、按日期区间生成实例、组内依赖编排、并发度控制、进度查询。
- `task-freeze`: 周期任务冻结/解冻 —— `TaskDef.frozen` 状态、调度器 claim 跳过冻结任务、冻结不破坏死锁不变量。
- `ops-center-view`: 统一运维中心前端视图 `ops` —— 顶条今日大盘、主舞台 Tab、实例表(救活孤儿)、补数据弹窗、批量 UI、举手台渲染、i18n 双语。
- `agent-ops-loop`: Agent 运维闭环 —— 后端巡检 → AG-UI `dataweave.ops.alert` 事件 → 举手台卡片 → 闸门执行;MCP ops 工具(查询走 domain service,写操作走 `GatedActionService`);IntentRouter mock 分支。

### Modified Capabilities
- `instance-lifecycle`: 新增 `set-success` 状态转移(终态/运行态 → SUCCESS 的 CAS 规则 + 唤醒下游 WAITING)与批量操作语义。
- `scheduler-core`: claim 阶段跳过 `frozen` 任务;`BACKFILL` 实例的生成与认领。

## Impact

- **后端 `dataweave-master`**(Stream A):新增 `BackfillService`/`backfill_run` 持久化、`OpsService` 批量+置成功+冻结、`SchedulerKernel` claim 跳过冻结、状态机 set-success 转移;`schema.sql` 新增 `backfill_run`、`task_def.frozen` 列、(M2 预留)告警表。
- **后端 `dataweave-worker`**(Stream A):BACKFILL 实例执行无差异(复用现有执行路径,仅 runMode 标识)。
- **前端 `frontend/`**(Stream B):新增 `ops` view + 注册、救活 `components/ops/*`、补数据弹窗、批量 UI、举手台、`messages/{zh-CN,en-US}.json` 新键。
- **后端 `dataweave-api`**(Stream C):`OpsController` 新增 batch/backfill/freeze/筛选端点 + DTO + 闸门 wiring;MCP ops 工具注册;AG-UI `dataweave.ops.alert` 事件出口;`IntentRouter` mock 分支。
- **后端 `dataweave-alert`**(Stream C):M1 仅打通巡检 → 举手台事件链路;基线/规则中心留 M2。
- **契约**:统一写端点返回 `{code, data, outcome}`,`outcome ∈ EXECUTED|PENDING_APPROVAL|REJECTED`,前端按 `outcome` 分流(不可只看 `code===0`)。
- **不变量**:补数据/置成功/冻结全部遵守调度器四条死锁不变量(SKIP LOCKED 认领、乐观 CAS 状态推进、固定 task→workflow 锁序、事务内只落状态 HTTP 下发在事务外)。
