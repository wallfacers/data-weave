## Why

`agent.mode=workhorse` 的真大脑（workhorse-agent，Go 单二进制）目前要**用户手动起进程**：本机直跑 `workhorse-agent serve`，或走 `docker compose --profile workhorse`。两条路都不跨端：

- docker 路线 `network_mode: host` 在 macOS / Windows 的 Docker Desktop 下根本不成立（host 网络不通到宿主 127.0.0.1），且仓库里**并无 `dataweave/workhorse-agent` 镜像或 Dockerfile**——compose 那条 `image:` 指向一个不存在的镜像。
- 手跑二进制要求用户自己按 OS/arch 选对二进制、填 config、对端口，跑偏一步 agent 就连不上，体验割裂。

目标是「起 DataWeave 后端 → agent 自动就绪」，在 **Windows / macOS / Linux（含 WSL）** 上一致。这要求 DataWeave 后端**拥有 sidecar 进程的生命周期**：按平台选对预编译二进制、拉起、健康收敛、崩溃重启、退出回收、端口单实例互斥。

workhorse-assistant 已经用 `runtime-mode`（Native | WSL）+ supervisor 模型把这套在桌面端验证过（adopt-or-spawn、三道闸、端口自然互斥、身份校验真 reap、退避重启），但那是 Tauri/Rust。DataWeave 是 Spring Boot Web 服务，需在 **Java 侧重建等价能力**。本变更是 DataWeave 侧工程，**workhorse-agent 零改动**（保持 OpenCode 式通用 headless 大脑，不接受任何宿主特定改动）。

## What Changes

- **Java sidecar supervisor**（`dataweave-api` infrastructure 层，基于 `ProcessBuilder`）：按 `os.name`/`os.arch` 解析 `workhorse-agent-{goos}-{goarch}[.exe]`，以 `serve --config <deploy/workhorse/config.yaml> --host 127.0.0.1 --port 8787` 拉起；探 `/health` 收敛；状态机 `Disabled → Probing → {Adopted | Starting → Healthy → Restarting} | Failed`。
- **adopt-or-spawn + 真 reap**：端口已被一个健康 sidecar 占用 → **adopt（复用，绝不杀）**；自己拉起的进程在 JVM 退出 / 切换时回收，且**只杀自己拉起的**（身份校验，杜绝误杀外部进程）。同一端口同一时刻只一个 sidecar（端口自然互斥）。
- **崩溃退避重启**：自己拉起的进程崩溃 → 指数退避重启；连续失败进入 `Failed` 并暴露原因，不无限刷。
- **配置开关**：`agent.workhorse.managed`（默认 **false**，保持现状/连外部进程），`agent.workhorse.runtime=native|wsl`（仅 Windows host 且 `wsl` 可用时 `wsl` 可选，默认 `native`），`agent.workhorse.binary-dir`/`binary` 覆盖，端口/config 路径复用既有 `agent.workhorse.*`。
- **二进制分发**：六平台二进制（linux/darwin/windows × amd64/arm64）从 workhorse-agent 的 `scripts/build.sh all` 产出，作为 DataWeave 运行期资源分发（放 `deploy/workhorse/bin/` 或构建期拉取），**不入 git**。
- **健康可观测**：supervisor 状态 + 当前 sidecar PID/adopt 标记经 `/api/ops` 健康快照与日志暴露；`managed=false` 时该段标记为「外部托管」。
- **docker 路线收口**：补一个最小 Dockerfile（多阶段：copy 预编译 linux 二进制）让 compose 的 `image:` 可真正构建，作为 Linux 容器化部署的可选路径；但**默认推荐 managed 进程托管**，compose 路线降级为可选。

## Capabilities

### New Capabilities

- `workhorse-supervisor`: DataWeave 后端对 workhorse-agent sidecar 的跨平台生命周期托管——二进制按平台选型、spawn/adopt/reap 与身份校验、端口单实例互斥、崩溃退避重启、运行时模式（native/wsl）、状态机与健康暴露、`managed` 开关默认关（关时行为零变化）。

### Modified Capabilities

<!-- agent-bridge（agent-fabric-m1，未归档）描述 agent.mode 双模式与 workhorse 连接；本变更只新增「谁拉起 sidecar」，不改桥接/事件契约。待 agent-fabric-m1 归档后，supervisor 与 agent-bridge 的关系在 base specs 体现。 -->

## Impact

- **后端**：`dataweave-api` 新增 supervisor 模块（infrastructure：`ProcessBuilder` spawn/probe/reap；application：状态机 + 配置）+ 启动钩子（`ApplicationRunner`/`SmartLifecycle`，managed 时拉起、关闭时 reap）+ `/api/ops` 健康段。
- **配置**：`application.yml` 新增 `agent.workhorse.managed/runtime/binary-dir/binary`；默认全关，老路径不变。
- **分发/打包**：`deploy/workhorse/bin/`（六平台二进制，gitignore）+ 构建/获取脚本；可选 `deploy/workhorse/Dockerfile`（修复 compose `image:` 悬空）。
- **运行形态**：managed=true 时 DataWeave 进程成为 sidecar 的父；macOS/Windows 走 native，WSL host 可选 wsl 运行时。
- **workhorse-agent**：**0 改动**（约束：保持通用 OpenCode 式大脑）。
- **Gate**：跨平台进程管理 → 至少 Linux/WSL 本机真机验证 adopt/restart/reap/端口互斥；不误杀外部进程的拒绝路径必测。**不触及 AG-UI/右舷**（supervisor 是后端运维能力），无需 Browser Verification Gate，但 agent.mode=workhorse 端到端仍依赖 agent-fabric-m1 的浏览器验证。

## Non-Goals

- 改 workhorse-agent 任何代码（其超时/协议/turn 模型保持通用）。
- workhorse 集群 / 多 sidecar 负载分发（单宿主单 sidecar）。
- 远程 sidecar（WSL 之外的跨主机），及 sidecar 自身的 LLM key 管理（仍由 config.yaml/env 提供）。
- agent.mode=workhorse 的端到端对话验证（属 `agent-fabric-m1` 9.2/9.3）。
