## 1. supervisor 决策核心（纯函数，平台无关）

- [x] 1.1 状态机与决策函数 `decide(state, healthProbe, ownership) → action`（Disabled/Probing/Adopted/Starting/Healthy/Restarting/Failed；action ∈ Probe/Adopt/Spawn/Restart/Reap/Fail）— `SupervisorCore.decide`
- [x] 1.2 退避策略（指数 + 上限 → Failed）与端口单实例互斥规则 — `SupervisorCore.backoffMillis`（逐步左移防溢出）+ EXTERNAL→adopt 不 spawn 体现互斥
- [x] 1.3 单测全分支：adopt 外部健康实例不 spawn、自起崩溃退避重启、连续失败进 Failed、reap 只杀自起、Adopted 不 reap、binary 缺失 Fail、非 Windows 强制 native — `SupervisorCoreTest`（22 例全绿）

## 2. IO 壳 + native 运行时

- [x] 2.1 `ProcessBuilder` spawn（`serve --config <path> --host 127.0.0.1 --port <port>`）、子进程句柄持有、kill/reap（跨平台：destroy→宽限→destroyForcibly）— `SidecarLauncher.spawn/reap`，命令构造提为纯函数 `buildCommand` 并单测
- [x] 2.2 `/health` 探测客户端（复用既有 `WebClient.Builder`），2xx→UP，超时/拒绝/非 2xx→DOWN，绝不抛 — `SidecarHealthProbe`；收敛等待交 supervisor 控制循环（design D7）
- [x] 2.3 二进制按平台选型（os.name/os.arch → `workhorse-agent-{goos}-{goarch}[.exe]`，`binary`/`binary-dir` 覆盖，存在性+可执行校验）— 选型纯函数 `SupervisorCore.platformBinary`；`binary`/`binary-dir` 覆盖 `SidecarLauncher.resolveBinary`（绝对路径直通）+ `binaryRunnable` 存在性/可执行校验，`SidecarLauncherTest` 覆盖
- [x] 2.4 本机真机验证（Linux native，真 workhorse-agent 二进制）：① 冷起 spawn→/health 收敛→HEALTHY；② adopt 外部健康实例 `adopted=true`/`pid=null` 不重复 spawn；③ 外部 kill 子进程→退避重启→新 PID 回 HEALTHY；④ stop() reap 自起进程 `dead=true`；⑤ 外部进程不被误杀（stop 后存活）— `WorkhorseSupervisorRealProcessTest`（3 例全绿，端口 8399；缺二进制时 `assumeTrue` 跳过不红）。**WSL 真机仍待**（见 4.2/4.3，本机为 Linux 无 Windows host）

## 3. 配置 + 生命周期接线 + 可观测

- [x] 3.1 `application.yml`：`agent.workhorse.managed`（默认 false）、`runtime=native|wsl`（默认 native）、`binary-dir`/`binary` + `port`/`config-path`/`health-path`/退避与超时参数；复用既有 `base-url`
- [x] 3.2 启动钩子（`SmartLifecycle`，phase=MAX 晚起早停）：managed=true 时后台控制线程拉起/adopt，`stop()` reap 自起进程；managed=false 完全旁路 — `WorkhorseSupervisor`
- [x] 3.3 `/api/ops/supervisor` 健康段（状态机态 + adopt 标记 + 自起 PID + 失败原因；managed=false `custody=external`）— `OpsController.supervisor()` + `WorkhorseSupervisor.Status`

## 4. wsl 运行时（Windows host 门控）

- [x] 4.1 host 检测（os.name=Windows 且 `wsl -l -q` 可用）+ distro 选择（UTF-16LE 解码取首个发行版）；非 Windows 兜底 native — `SidecarLauncher.detectWslDistro` + `SupervisorCore.resolveRuntime` + `WorkhorseSupervisor.wslAvailable`；WSL interop 真机实证 `detectWslDistro()`→`Ubuntu`（`SidecarLauncherWslTest`）
- [x] 4.2 `wsl.exe -d <distro> -- <serve cmd>` spawn/reap（二进制 linux/amd64）— `SidecarLauncher.buildCommand(WSL,...)`；**WSL interop 真机已验证**：`wsl.exe -d Ubuntu -- … serve`→`/health` UP→`reap` 端口释放无孤儿（`SidecarLauncherWslTest`，2 例真跑全绿）。纯 Windows-host→WSL 跨命名空间托管由 supervisor 在 Windows 承载（resolveRuntime 在 Linux 强制 native）
- [x] 4.3 决策核心单测覆盖 wsl 分支（`resolveRuntime` 四例：非 Win 强制 native / Win+wsl 可用 / Win+wsl 不可用兜底 / native 请求）+ 命令形状 `buildCommand` wsl/native（`SidecarLauncherTest`）+ WSL interop spawn/reap 真机（`SidecarLauncherWslTest`）

## 5. 二进制分发 + docker 路线收口

- [x] 5.1 分发脚本 `deploy/workhorse/fetch-bin.sh`：定位 workhorse-agent 仓库 → `scripts/build.sh all` → 拷六平台二进制入 `deploy/workhorse/bin/`；`.gitignore` 已忽略 `deploy/workhorse/bin/`（二进制不入 git）
- [x] 5.2 `deploy/workhorse/Dockerfile`（多阶段 copy 预编译 linux 二进制，distroless 运行时），docker-compose `workhorse` 服务改 `build:` 修复悬空 `image:`；注释明确 managed 与 compose 二选一
- [x] 5.3 README「Agent 大脑模式（mock / workhorse）」：A managed 跨端（Win native/wsl、Mac/Linux native）+ B docker（仅 Linux）二选一，managed 开关/runtime/`fetch-bin.sh`/`/api/ops/supervisor` 说明

## 6. 约束守门

- [x] 6.1 确认 workhorse-agent **零改动**：workhorse-agent 不在本仓（独立仓独立构建），本变更全部文件落 DataWeave 侧（supervisor/IO 壳/config/ops/部署脚本/文档），无任何 workhorse-agent 源码改动
- [x] 6.2 `agent.workhorse.managed=false` 路径回归：supervisor `start()` 旁路（不起线程/不 spawn）；H2 profile 全量 api 测试中 agent-mode 既有测试全绿（IntentRouter/WorkhorseBridge/AguiWorkhorseModeTest/McpEndpoint/CliEndpoint）+ OpsController 接线后上下文照常加载；2 处 red（SchedulingParameterIntegrationTest/SchedulerPreemptionTest）属无关的既有 i18n WIP（`TaskController` 错误响应重构）与抢占时序，不涉本变更任何文件
