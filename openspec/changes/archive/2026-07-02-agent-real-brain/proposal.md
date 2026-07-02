## Why

DataWeave Agent 默认跑在 mock 规则引擎（`IntentRouter` + `MockDiagnosisAnalyzer`）上：对话是固定话术，诊断结论是套模板，举手台里的失败（OOM@node-3 等）是 `data.sql` 预填的演示假数据。用户要的是真实大脑——Agent 真正理解对话、用真模型推理诊断，且日常启动默认就是真实的，而非每次手动切换。真实遥测采集链路（节点指标、近 7 天同类失败次数）已就绪，缺的是把大脑从规则换成真 LLM、并去掉演示假数据。

## What Changes

- **默认大脑切真实**：`agent.mode` 默认由 `mock` 改为 `workhorse`；接 DeepSeek 的 Anthropic 兼容端点（`deepseek-v4-pro`）作为真实大脑。**BREAKING**（默认运行形态改变，无 sidecar/key 的环境依赖下方降级保活）。
- **优雅降级**：新增 workhorse 健康探测，sidecar/key 不可用时自动降级到 `IntentRouter`/`MockDiagnosisAnalyzer`（带一次性 WARN + 对话内提示），CI / fresh clone / 无 key 环境零依赖照常跑、测试照绿。
- **诊断走 workhorse（SPI）**：诊断从规则模板换真 LLM。`DiagnosisAnalyzer` 接口留在 master（内层），新增 `WorkhorseDiagnosisAnalyzer`（api 层 `@Primary`）把真实遥测打包成结构化 prompt，经 workhorse headless 会话推理出根因/建议，落 `task_diagnosis`/`finding`；不可用回落 `MockDiagnosisAnalyzer`。
- **删除进程内 LLM 占位**：诊断改走 workhorse 后，进程内 `MockLlmClient` / `LlmClient` 成为死代码，**移除**（真实 LLM 能力由 workhorse 统一提供）。
- **演示数据移 demo profile**：`data.sql` 中 OOM@node-3 演示种子（诊断/finding/节点高水位/agent_action）抽到 `demo-data.sql`，仅 `-Dspring.profiles=demo` 加载；默认空库走真实链路。
- **凭据安全**：DeepSeek token 只进 gitignore 的 `config.local.yaml` 或环境变量；修复上游隐患——被 git 跟踪且未 gitignore 的 `frontend/deploy/workhorse/config.local.yaml`。

## Capabilities

### New Capabilities
<!-- 无新增 capability：复用现有 agent-bridge / self-diagnosis 能力，行为按 delta 修改 -->

### Modified Capabilities
- `agent-bridge`: 双模式默认值由 `mock` 改为 `workhorse`，新增 workhorse 健康探测与不可用时的优雅降级要求。
- `self-diagnosis`: Agent 根因分析默认走 workhorse 真 LLM（`WorkhorseDiagnosisAnalyzer` SPI `@Primary`），mock 退居降级兜底；演示诊断/finding 种子由默认加载改为仅 demo profile 加载。

## Impact

- **后端 api 模块**：新增 `WorkhorseDiagnosisAnalyzer`（`@Primary`，实现 master 的 `DiagnosisAnalyzer` SPI）、`WorkhorseHealthProbe`；`WorkhorseBridge` 增 headless/oneshot 调用；`AguiOrchestrator` 降级分支；删除 `MockLlmClient`。
- **后端 master 模块**：`DiagnosisService`/`DiagnosisAnalyzer` 编排骨架不变（SPI 已预留）；移除对 `LlmClient` 的引用（若有）。
- **配置**：`application.yml` `agent.mode` 默认改 `workhorse`、`data-locations` 去演示种子；新增 `application-demo.yml`；`deploy/workhorse/config.local.yaml` 切 DeepSeek 端点。
- **数据**：`data.sql` 拆出 `demo-data.sql`（OOM 演示场景）。
- **测试**：依赖演示种子的测试加 `@ActiveProfiles("demo")` 或改 `fault-injection.sql` 注入；新增 `WorkhorseDiagnosisAnalyzer`、健康探测/降级、降级仍产出合法 AG-UI 序列的测试。
- **git 卫生**：`git rm --cached frontend/deploy/workhorse/config.local.yaml` + `.gitignore` 增 `**/config.local.yaml`。
- **验收**：默认真实大脑流式作答 + 真 LLM 诊断卡片；无 key 自动降级测试全绿；浏览器验证门通过。
