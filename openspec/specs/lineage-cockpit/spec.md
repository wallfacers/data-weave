# lineage-cockpit Specification

## Purpose

定义 DataWeave 态势驾驶舱能力：以跨系统活血缘图为主舞台、顶条展示全局健康聚合、右栏「Agent 举手台」复用自诊断根因与修复建议，使非开发人员无需理解 cron/DAG 即可一眼掌握「数据流到哪、同步多少、还要多久、哪里出问题」。

## Requirements

### Requirement: 跨系统活血缘图主舞台

驾驶舱第一屏 SHALL 以一张跨系统活血缘图作为主舞台（占据屏幕中心约 60%），节点按 `layer`（ODS/DWD/DWS/ADS）从左到右分层布局，呈现 `数据源 → ODS → DWD → DWS → ADS → 看板` 的整体数据流向。图拓扑来自设计态血缘（`table-lineage`），无需任务先运行即可呈现。

#### Scenario: 打开即见全局数据流向

- **WHEN** 非开发人员首次打开驾驶舱
- **THEN** 主舞台呈现按层排布的跨系统血缘图，清晰展示数据从源到看板的流向，而非一堆统计卡

#### Scenario: 血缘缺失时降级

- **WHEN** 系统尚无表级血缘（旧任务未声明 io）
- **THEN** 主舞台降级以数据源级粗粒度节点呈现流向，仍可用而非空白

### Requirement: 节点状态实时变色

血缘图节点 SHALL 通过 SSE（复用 `realtime-streams` 的工作流实例事件流）实时反映运行态：运行中脉冲、成功、失败/卡住发红，无需手动刷新。前端 MUST 直连后端 SSE（不经 Next rewrite 代理，避免流被缓冲）。

#### Scenario: 跑批中节点实时变色

- **WHEN** 某任务进入 RUNNING 并随后 FAILED
- **THEN** 对应节点先脉冲、后变红，无需用户刷新页面

### Requirement: 节点 ETA 与根因标注

血缘图节点 SHALL 标注 ETA（「还要多久」）与异常根因一句话。ETA MUST 基于历史成功运行时长中位数（复用 `SlaService` 基线）减去当前已耗时；无历史样本时显示「估算中」而非编造数字。异常节点旁直接展示 `self-diagnosis` 的根因摘要。

#### Scenario: 运行中节点显示 ETA

- **WHEN** 一个有历史样本的任务正在运行、已耗时 2 分钟、历史中位 8 分钟
- **THEN** 节点标注 ETA 约 6 分钟

#### Scenario: 冷启动无样本不编造

- **WHEN** 任务首次运行、无历史时长样本
- **THEN** 节点显示「估算中」，不显示虚假 ETA 数字

#### Scenario: 异常节点贴根因

- **WHEN** 某节点对应实例被 `self-diagnosis` 判定根因为资源争用
- **THEN** 节点旁展示根因一句话（如「疑似资源争用 CPU 94%」）

### Requirement: 顶条全局健康聚合

驾驶舱顶条 SHALL 展示全局聚合数：健康度、运行中/排队中/异常实例数、今日同步数据量（行数）、最迟看板 ETA。这些聚合数面向非开发人员，使其无需理解 cron/DAG 即可一眼掌握系统状态。今日同步量来自运行态血缘（`table-lineage`）聚合。

#### Scenario: 顶条一眼健康

- **WHEN** 用户查看驾驶舱顶条
- **THEN** 呈现健康度、运行/排队/异常计数、今日同步行数、最迟看板 ETA 等聚合指标

### Requirement: Agent 举手台

驾驶舱 SHALL 设右栏「Agent 举手台」，复用 `self-diagnosis` 的根因与修复建议，列出待处理的异常（慢任务、卡住、血缘 CONFLICT 待复核等），每条提供「让它处理 / 我看看 / 忽略」操作。处理写操作 MUST 按 PolicyEngine 裁决的 `outcome` 分流（不能仅看 `code===0`，L2/L3 返回 PENDING_APPROVAL 需走审批）。

#### Scenario: 慢任务举手

- **WHEN** 某任务比历史均值慢 3 倍且根因判为资源问题
- **THEN** 举手台出现一条「慢 3×，根因→资源，建议降并发重跑」并附操作按钮

#### Scenario: 处理动作按审批结果分流

- **WHEN** 用户点「让它处理」触发一个被 PolicyEngine 判为 L2 的写操作
- **THEN** 前端按返回 `outcome=PENDING_APPROVAL` 展示「已提交审批」，而非误报「已执行成功」

### Requirement: 节点下钻到详情视图

血缘图节点 SHALL 支持点击下钻：通过 `dataweave.ui.open` 打开（或激活）对应的现有详情视图（工作流实例详情 / 实例日志 / 指标），使现有视图从「平级 tab」降格为「血缘节点的详情」。

#### Scenario: 点节点看日志

- **WHEN** 用户点击某运行中节点的「查看日志」
- **THEN** Workspace 打开该实例的 `instance-log` tab，实时滚动日志

#### Scenario: 点节点看实例详情

- **WHEN** 用户点击某节点
- **THEN** Workspace 打开（或激活）该工作流实例详情视图，左栏 Agent 上下文同步到该节点
