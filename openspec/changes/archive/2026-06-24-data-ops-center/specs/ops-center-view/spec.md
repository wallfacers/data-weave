## ADDED Requirements

### Requirement: 统一运维中心视图

系统 SHALL 在 Workspace 注册新视图 `ops`,可经 `dataweave.ui.open`(payload `{view:"ops", params?}`)召唤或经「+」启动器打开,布局为「顶条今日大盘 + 主舞台 Tab + 右栏 Agent 运维举手台」。

#### Scenario: 经 ui.open 召唤运维中心
- **WHEN** 后端发出 `CUSTOM(dataweave.ui.open)` payload `{ view: "ops", params: { tab: "instances", filter: { state: "FAILED" } } }`
- **THEN** Workspace 打开/激活 `ops` view,主舞台切到「周期实例」Tab 且预置失败筛选

#### Scenario: 顶条今日大盘
- **WHEN** ops view 打开
- **THEN** 顶条展示今日 总/运行中/成功/失败 实例数与 SLA 风险数(取 summary + eta-summary),数据为空时显示空态而非报错

### Requirement: 周期实例运维面板

系统 SHALL 在「周期实例」Tab 提供筛选栏(runMode/state/taskId/bizDate)、分页表格(复用既有 `components/ops` 组件)与批量操作栏(rerun/kill/set-success),下钻复用 `workflow-instance-detail`/`instance-log`。

#### Scenario: 多选后批量重跑
- **WHEN** 用户勾选多行点「重跑」
- **THEN** 调 `POST /api/ops/instances/batch`,并按返回每项 `outcome` 分流:EXECUTED 显示成功、PENDING_APPROVAL 显示待批、REJECTED 显示拒绝

#### Scenario: 不可只看 code 判定成功
- **WHEN** 批量操作返回 outcome=PENDING_APPROVAL
- **THEN** 前端显示「待审批」态,不得因 `code===0` 误判为已执行成功

### Requirement: 补数据弹窗与进度面板

系统 SHALL 提供补数据发起弹窗(目标 + 日期区间 + includeDownstream + parallelism)与「补数据实例」Tab(run 列表 + 进度 + 下钻子实例)。

#### Scenario: 发起补数据
- **WHEN** 用户在弹窗选定任务、日期区间并提交
- **THEN** 调 `POST /api/ops/backfill`,按 outcome 分流,成功后「补数据实例」Tab 出现新 run 及其进度

#### Scenario: 进度实时反映
- **WHEN** 补数据 run 的子实例陆续完成
- **THEN** run 行的 total/success/failed/running 随刷新更新,可下钻查看子实例与日志

### Requirement: Agent 运维举手台渲染

系统 SHALL 在 ops view 右栏渲染 `dataweave.ops.alert` 事件卡片(severity 着色、标题/详情、关联实例、建议动作按钮),复用 cockpit 举手台视觉模式;按钮回调对应 batch/backfill 端点。

#### Scenario: 渲染失败实例告警卡片
- **WHEN** 收到 `CUSTOM(dataweave.ops.alert)` payload `{ kind: "INSTANCE_FAILED", severity: "warning", instanceIds, suggestedAction: { op: "rerun" } }`
- **THEN** 右栏出现告警卡片,「重跑这些」按钮点击后调 batch 端点并按 outcome 分流

#### Scenario: i18n 双语齐全
- **WHEN** 切换 UI locale 到 en-US
- **THEN** ops view 所有静态 copy 经 next-intl 解析为英文,zh-CN/en-US 键集一致,无运行时缺键
