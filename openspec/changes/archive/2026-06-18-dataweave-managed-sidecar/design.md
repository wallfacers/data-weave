## Context

workhorse 模式的真大脑是独立进程 workhorse-agent（127.0.0.1:8300，dataweave-api 为唯一客户端）。`agent-fabric-m1` 已把桥接/MCP/审计/审批全接好，但**谁拉起这个 sidecar** 一直是空白：要么手跑二进制，要么走一条指向不存在镜像的 compose profile。要做到「起后端 → agent 就绪」且跨 Win/Mac/Linux 一致，DataWeave 必须托管 sidecar 生命周期。

约束（用户给定，硬性）：**workhorse-agent 是 OpenCode 式通用后端 Agent，不接受任何 DataWeave 特定改动**。故所有托管逻辑落在 DataWeave 侧；workhorse-agent 仅以「一个可被 `serve` 起来、暴露 `/health` 的通用进程」身份被使用，零改动。

参考：workhorse-assistant 的 `runtime-mode`（Native | WSL）+ supervisor 已在桌面端验证了 adopt-or-spawn / 端口自然互斥 / 身份校验真 reap / 退避重启的决策核心。本变更把该**决策核心移植为 Java**，IO 壳换成 `ProcessBuilder`。

## Goals / Non-Goals

**Goals**
- 「起后端 → agent 自动就绪」，Win/Mac/Linux（含 WSL）一致。
- 绝不误杀非自己拉起的进程；端口已健康占用则 adopt。
- `managed=false`（默认）时行为与今日完全一致（连外部 8300）。

**Non-Goals**
- 改 workhorse-agent；多 sidecar/集群；远程（WSL 外）主机；sidecar 的 LLM key 管理。

## Decisions

### D1: supervisor 决策核心与 IO 壳分离（移植 assistant 模型）
纯函数式决策核心 `decide(state, healthProbe, ownership) → action`（Probing/Adopt/Spawn/Restart/Reap/Fail），与 `ProcessBuilder` 的 spawn/kill、HTTP `/health` 探测的 IO 壳解耦。**Why**：决策核心可纯单测（无进程、无网络），覆盖 adopt/restart/reap/互斥/不误杀全分支；assistant 已验证该形状成立。

### D2: adopt-or-spawn + 身份校验真 reap
启动时先探 8300 `/health`：健康且非本进程所起 → **Adopted**（标记，永不 reap）；无人占用 → Spawn 并记录子进程句柄/PID。reap 只作用于「本 supervisor 持有句柄的子进程」。**Why**：WSL2 localhost 转发会让宿主静默连上 WSL 内残留 sidecar（assistant 实测踩过 26h 僵尸进程占端口）；误杀外部进程是不可接受的破坏。Alternative：按端口杀——会误杀别人的 sidecar，弃。

### D3: 端口单实例互斥，切换先 reap 再起
同一端口同一时刻仅一个 sidecar。切运行时（native↔wsl）或重启：**先 reap 当前自起进程，再起目标**，避免 localhost 转发串台到错误命名空间。Adopted 的外部进程不在 reap 范围——此时若需托管，提示用户而非强杀。**Why**：端口天然互斥是最简可靠的「单实例」保证。

### D4: 二进制按平台选型，预编译分发，不入 git
`workhorse-agent-{goos}-{goarch}[.exe]`，`os.name`→goos（linux/darwin/windows）、`os.arch`→goarch（amd64/arm64）。二进制由 workhorse-agent `scripts/build.sh all` 产出，放 `deploy/workhorse/bin/`（gitignore）或构建期拉取；`agent.workhorse.binary`/`binary-dir` 可覆盖。**Why**：workhorse-agent 是独立仓独立构建（与 cli/ 的 `dw` 同款约定），二进制是交付物不是源码。Alternative：DataWeave 构建期交叉编译 Go——引入 Go 工具链依赖到 Java 构建，弃。

### D5: 运行时模式 native 默认、wsl 仅 Windows 可选
`agent.workhorse.runtime=native|wsl`。`native`：直接 spawn 宿主二进制。`wsl`：仅当 host 为 Windows 且 `wsl -l -q` 可用时可选，spawn `wsl.exe -d <distro> -- <serve cmd>`，二进制为 linux/amd64。默认且兜底 `native`。**Why**：对齐 assistant 的 runtime-mode；Win 用户可能希望 sidecar 跑在 WSL 命名空间（文件/PTY 落 WSL）。Linux/Mac 无 wsl 选项。

### D6: managed 默认 false，托管是可选增强
`agent.workhorse.managed=false`（默认）：supervisor 不拉起任何进程，仅 probe 并在健康段标「外部托管」——与今日零差异（CI/现有部署不受影响）。`true`：启用上述生命周期。**Why**：托管是体验增强不是前置；分布式部署/CI 可能用外部编排。

### D7: 健康收敛复用既有探测，supervisor 只管进程
supervisor 不重写健康等待——spawn 后交给现有连接逻辑探 `/health` 直到 Healthy（带超时与退避）。supervisor 状态机只拥有 spawn/restart/reap/adopt。**Why**：与 assistant「supervisor 保持薄，不接管 health-wait」一致，职责单一。

## Risks / Trade-offs

- [WSL2 localhost 转发串台 / 僵尸 sidecar 误连] → D2 身份校验 + adopt 标记；不误杀外部进程的拒绝路径必测。
- [跨平台进程 API 差异（信号、子进程树、.exe）] → IO 壳按 OS 分支，决策核心平台无关；Windows reap 用进程句柄而非信号。
- [二进制缺失/架构不匹配] → 启动校验二进制存在且可执行，缺失则 `Failed` 并明确报错（指向 build.sh），不静默回退 mock。
- [managed 与 docker compose 并用导致双 sidecar 抢 8300] → 端口互斥 + adopt：先起的赢，后者 adopt 或让位；文档明确二选一。

## Migration Plan

1. supervisor 决策核心（纯函数）+ 单测全分支（adopt/spawn/restart/reap/互斥/不误杀/binary 缺失）。
2. IO 壳（ProcessBuilder spawn/kill + /health probe）+ native 运行时；Linux/WSL 本机真机验证 adopt/restart/reap。
3. 配置项 + 启动/关闭钩子（managed 时拉起、JVM 退出 reap）+ `/api/ops` 健康段。
4. wsl 运行时（Windows host 门控）—— 若无 Windows 真机，决策核心单测覆盖 + 标注待真机。
5. 二进制分发脚本（build.sh all → deploy/workhorse/bin/，gitignore）+ 可选 Dockerfile 修复 compose 悬空 image。
6. 回滚：`agent.workhorse.managed=false` 一键回到外部进程；无 schema/协议变化。

## Open Questions

- 二进制分发：随 release 打包 vs 首启按平台从 workhorse-agent release 拉取？（倾向打包，离线可用）
- Windows 真机验证渠道（WSL 运行时 + native .exe reap）是否具备？无则该分支只能单测 + 标注。
