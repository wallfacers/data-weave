## MODIFIED Requirements

### Requirement: 驾驶舱健康中心首页

驾驶舱（健康中心）SHALL 作为 Workspace 的 Pinned tab 之一（`cockpit` 视图）常驻呈现，且第一屏重心 SHALL 以**跨系统活血缘图为中心**（见 `lineage-cockpit`）：主舞台呈现按层（ODS/DWD/DWS/ADS）排布的整体数据流向图，顶条展示全局健康聚合（健康度/运行·排队·异常计数/今日同步量/最迟看板 ETA），右栏为「Agent 举手台」（复用 `self-diagnosis`）。原「今日运行概况计数」降为顶条聚合，「失败任务列表」「诊断中事项」整合进右栏举手台与节点下钻。首次打开应用（无快照）时 SHALL 默认激活驾驶舱 tab。

#### Scenario: 开屏即见全局态势

- **WHEN** 用户首次访问 `/`
- **THEN** Workspace 默认激活驾驶舱 tab，主舞台呈现跨系统活血缘图，顶条呈现全局健康聚合数，右栏呈现 Agent 举手台

#### Scenario: 从失败项直达诊断

- **WHEN** 用户在驾驶舱右栏举手台或血缘图异常节点点击某条失败事项
- **THEN** Workspace 打开（或激活）该实例的 `diagnosis` tab（见 `self-diagnosis`），且左栏 Agent 上下文同步到该失败实例

#### Scenario: 非开发人员打开即懂

- **WHEN** 不熟悉 cron/DAG 的业务人员打开驾驶舱
- **THEN** 仅凭主舞台血缘流向、顶条聚合数与举手台一句话根因，即可掌握「数据流到哪、同步多少、还要多久、哪里出问题」，无需操作底层模块
