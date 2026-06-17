## ADDED Requirements

### Requirement: managed 开关与默认关闭
系统 SHALL 提供 `agent.workhorse.managed`（默认 `false`）控制是否托管 sidecar 进程。`managed=false` 时 supervisor MUST NOT 拉起任何进程，行为与未引入本能力时完全一致（仅连接外部 `agent.workhorse.base-url` 指向的进程）。

#### Scenario: 默认关闭不改变现状
- **WHEN** 未配置 `agent.workhorse.managed` 或其为 `false`
- **THEN** DataWeave 启动不拉起 sidecar 进程，agent.mode=workhorse 仍按既有逻辑连接外部 8300，无任何新进程产生

#### Scenario: 开启后随后端就绪
- **WHEN** `agent.workhorse.managed=true` 且 DataWeave 后端启动
- **THEN** supervisor MUST 在后端就绪过程中拉起或 adopt 一个 sidecar，并在其 `/health` 通过后标记为 Healthy

### Requirement: adopt-or-spawn 与身份校验回收
supervisor SHALL 在拉起前探测目标端口的 `/health`：已被健康 sidecar 占用且非本 supervisor 所起者 MUST 被 **adopt**（复用，绝不回收）；无人占用时 MAY spawn 新进程并记录其句柄。回收（reap）MUST 仅作用于本 supervisor 持有句柄的子进程，MUST NOT 终止任何 adopt 的或外部的进程。

#### Scenario: 复用已健康的外部 sidecar
- **WHEN** 启动时 8300 已有一个健康且非本进程所起的 sidecar
- **THEN** supervisor 标记其为 Adopted 并复用，**不**spawn 新进程，进程退出时**不**终止它

#### Scenario: 退出只回收自己拉起的
- **WHEN** supervisor 自行 spawn 了 sidecar，随后 DataWeave 进程关闭
- **THEN** supervisor MUST 终止该自起 sidecar；若当前为 Adopted 状态，则 MUST NOT 终止任何进程

### Requirement: 端口单实例互斥与崩溃退避重启
同一端口同一时刻 MUST 至多一个 sidecar。supervisor 自起的 sidecar 异常退出时 SHALL 以指数退避重启；连续失败超过上限 MUST 进入 `Failed` 状态并暴露原因，停止无限重启。切换运行时 MUST 先回收当前自起进程再启动目标。

#### Scenario: 崩溃后退避重启
- **WHEN** 自起 sidecar 进程崩溃退出
- **THEN** supervisor 按退避间隔重启它，并在重新 `/health` 通过后回到 Healthy

#### Scenario: 连续失败进入 Failed
- **WHEN** 重启连续失败达到上限
- **THEN** supervisor 进入 `Failed`，健康快照暴露失败原因，不再无限重启

### Requirement: 跨平台二进制选型
supervisor SHALL 按宿主 `os.name`/`os.arch` 解析 sidecar 二进制名 `workhorse-agent-{goos}-{goarch}[.exe]`（linux/darwin/windows × amd64/arm64），可经 `agent.workhorse.binary`/`binary-dir` 覆盖。二进制缺失或不可执行 MUST 使 supervisor 进入 `Failed` 并给出明确报错（指向构建方式），MUST NOT 静默改变 agent.mode。

#### Scenario: 按平台选对二进制
- **WHEN** 宿主为 macOS arm64 且 managed=true
- **THEN** supervisor 选用 `workhorse-agent-darwin-arm64` 拉起

#### Scenario: 二进制缺失明确失败
- **WHEN** 目标平台二进制不存在于 binary-dir
- **THEN** supervisor 进入 `Failed` 并报「二进制缺失，参见 workhorse-agent scripts/build.sh」，不静默回退

### Requirement: 运行时模式 native/wsl
supervisor SHALL 支持 `agent.workhorse.runtime=native|wsl`（默认 `native`）。`wsl` MUST 仅在宿主为 Windows 且检测到可用 WSL 发行版时可选，否则 MUST 兜底 `native`。`wsl` 模式下以 `wsl.exe -d <distro>` 在 WSL 命名空间内拉起 linux 二进制。

#### Scenario: 非 Windows 强制 native
- **WHEN** 宿主为 Linux 或 macOS 且配置了 `runtime=wsl`
- **THEN** supervisor 兜底为 `native` 并记录提示，不尝试 wsl

### Requirement: supervisor 状态可观测
系统 SHALL 经运维健康端点暴露 supervisor 状态机当前态（Disabled/Probing/Adopted/Starting/Healthy/Restarting/Failed）、当前 sidecar 是否为 adopt、以及失败原因（若有）。`managed=false` 时该段 MUST 标识为「外部托管」。

#### Scenario: 健康快照含 supervisor 段
- **WHEN** 查询运维健康快照
- **THEN** 返回中包含 supervisor 状态与 adopt 标记；managed=false 时标「外部托管」
