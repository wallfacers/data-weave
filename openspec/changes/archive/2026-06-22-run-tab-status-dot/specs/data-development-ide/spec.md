## ADDED Requirements

### Requirement: 运行 Tab 状态圆点反映实例终态

编辑子 Tab 内日志 Tabs 容器的每个运行 tab 左侧 SHALL 显示一个状态圆点，反映对应实例的**执行状态**（非 SSE 连接状态）：实例 RUNNING 时圆点为绿色脉冲，SUCCESS 时为绿色稳态（远程完成），FAILED 时为红色（运行错误），STOPPED 时为灰色（已终止）。圆点状态 SHALL 由日志流的连接活性与终态 outcome 合成——SSE 仍连接且未收到终态 outcome 时为「运行中」（绿色脉冲）；收到 `end` 事件携带的终态 outcome 后以终态颜色覆盖（绿色稳态/红色/灰色），且不再回退为「运行中」。圆点配色 MUST 使用 DESIGN.md 语义 token（`bg-success`/`bg-destructive`/`bg-muted-foreground`/`bg-warning`），不得使用硬编码调色板色值。圆点 SHALL 提供 tooltip（复用既有 i18n state 文案）表明其当前含义。

#### Scenario: 运行中绿色脉冲

- **WHEN** 某运行 tab 对应实例处于 RUNNING 且其日志 SSE 仍连接
- **THEN** 该 tab 左侧圆点呈现绿色脉冲

#### Scenario: 成功绿色稳态

- **WHEN** 某运行 tab 对应实例终态为 SUCCESS
- **THEN** 该 tab 左侧圆点呈现绿色稳态（不脉冲）

#### Scenario: 失败红色

- **WHEN** 某运行 tab 对应实例终态为 FAILED
- **THEN** 该 tab 左侧圆点呈现红色

#### Scenario: 已终止灰色

- **WHEN** 某运行 tab 对应实例终态为 STOPPED
- **THEN** 该 tab 左侧圆点呈现灰色

#### Scenario: 终态覆盖连接态不回退

- **WHEN** 某运行 tab 的日志流已收到 SUCCESS 终态 outcome，但 SSE 连接尚未物理关闭
- **THEN** 圆点为绿色稳态（终态覆盖连接态），不回退为「运行中」脉冲

#### Scenario: 多运行 tab 状态各自独立

- **WHEN** 日志 Tabs 容器并存两个运行 tab，其一终态 SUCCESS、另一终态 FAILED
- **THEN** 两个 tab 的圆点分别为绿色稳态与红色，各自独立、互不影响
