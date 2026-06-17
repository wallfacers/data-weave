## 1. supervisor 决策核心（纯函数，平台无关）

- [x] 1.1 状态机与决策函数 `decide(state, healthProbe, ownership) → action`（Disabled/Probing/Adopted/Starting/Healthy/Restarting/Failed；action ∈ Probe/Adopt/Spawn/Restart/Reap/Fail）— `SupervisorCore.decide`
- [x] 1.2 退避策略（指数 + 上限 → Failed）与端口单实例互斥规则 — `SupervisorCore.backoffMillis`（逐步左移防溢出）+ EXTERNAL→adopt 不 spawn 体现互斥
- [x] 1.3 单测全分支：adopt 外部健康实例不 spawn、自起崩溃退避重启、连续失败进 Failed、reap 只杀自起、Adopted 不 reap、binary 缺失 Fail、非 Windows 强制 native — `SupervisorCoreTest`（22 例全绿）

## 2. IO 壳 + native 运行时

- [ ] 2.1 `ProcessBuilder` spawn（`serve --config <path> --host 127.0.0.1 --port <port>`）、子进程句柄持有、kill/reap（跨平台：Unix 信号 / Windows 句柄）
- [ ] 2.2 `/health` 探测客户端（复用既有 WebClient 配置），收敛超时与退避交由既有连接逻辑
- [~] 2.3 二进制按平台选型（os.name/os.arch → `workhorse-agent-{goos}-{goarch}[.exe]`，`binary`/`binary-dir` 覆盖，存在性+可执行校验）— 选型纯函数 `SupervisorCore.platformBinary` + 单测已做；`binary`/`binary-dir` 覆盖与存在性/可执行校验待 IO 壳（§2.1）真机
- [ ] 2.4 本机真机验证（Linux/WSL）：冷起 spawn→Healthy；adopt 已起实例不重复拉起；kill 子进程触发退避重启；JVM 退出 reap 自起进程；外部进程不被误杀

## 3. 配置 + 生命周期接线 + 可观测

- [ ] 3.1 `application.yml`：`agent.workhorse.managed`（默认 false）、`runtime=native|wsl`（默认 native）、`binary-dir`/`binary`；复用既有 `base-url`/端口/config 路径
- [ ] 3.2 启动钩子（`SmartLifecycle`/`ApplicationRunner`）：managed=true 时拉起/adopt；关闭钩子 reap 自起进程；managed=false 完全旁路
- [ ] 3.3 `/api/ops` 健康快照新增 supervisor 段（状态 + adopt 标记 + 失败原因；managed=false 标「外部托管」）

## 4. wsl 运行时（Windows host 门控）

- [ ] 4.1 host 检测（os.name=Windows 且 `wsl -l -q` 可用）+ distro 选择；非 Windows 兜底 native
- [ ] 4.2 `wsl.exe -d <distro> -- <serve cmd>` spawn/reap（二进制 linux/amd64）
- [ ] 4.3 决策核心单测覆盖 wsl 分支；**无 Windows 真机则标注「待真机」**（不假装通过）

## 5. 二进制分发 + docker 路线收口

- [ ] 5.1 分发脚本：从 workhorse-agent `scripts/build.sh all` 取六平台二进制 → `deploy/workhorse/bin/`（`.gitignore` 忽略，二进制不入 git）
- [ ] 5.2 （可选）`deploy/workhorse/Dockerfile`（多阶段 copy linux 二进制），修复 docker-compose `image:` 悬空；文档明确 managed 进程托管与 compose 二选一
- [ ] 5.3 README/部署文档：跨端启动说明（Win/Mac/Linux + WSL），managed 开关与 runtime 取值

## 6. 约束守门

- [ ] 6.1 确认 workhorse-agent 仓库**零改动**（grep diff 为空）；本变更全部落 DataWeave 侧
- [ ] 6.2 `agent.workhorse.managed=false` 路径回归：现有 mock/外部 workhorse 行为零变化（既有测试全绿）
