## Context

DataWeave 后端进程内现以 mock 规则引擎驱动 Agent：`AguiOrchestrator` 默认 `agent.mode=mock` 走 `IntentRouter`（关键词路由 + 固定话术）；诊断由 master 的 `MockDiagnosisAnalyzer` 套模板生成；举手台的失败由 `data.sql` 第 155–308 行预填的 OOM@node-3 演示种子（`task_diagnosis`/`finding`/`worker_node` 高水位/`agent_action`）撑起。真实遥测采集（`HeartbeatReporter` 真实 CPU/内存、`NodeTelemetryService` 并发与近 7 天失败次数的真实 SQL 统计）已就绪。真实大脑入口 `agent.mode=workhorse` + `WorkhorseBridge` 已接线（`agent-bridge` capability），workhorse-agent 是 OpenCode 式通用大脑，吃 Anthropic 环境变量 / `config.yaml` 的 `providers.anthropic`。

用户提供 DeepSeek 的 Anthropic 兼容端点（`base_url=https://api.deepseek.com/anthropic`，模型 `deepseek-v4-pro`/`deepseek-v4-flash`）作为真实大脑，本次可活体验证。

约束：依赖方向 domain ← application ← infrastructure ← interfaces；master 不可反向依赖 api。CLAUDE.md 要求 mock 仍是 CI / fresh clone 的零依赖兜底。任何 `/agui` 改动须过浏览器验证门（自建 chat 台，非 CopilotKit）。

## Goals / Non-Goals

**Goals:**
- 默认启动即真实大脑：`agent.mode` 默认 `workhorse`，对话与诊断都走真模型。
- 诊断从规则模板换真 LLM，复用同一个 workhorse 大脑（不在后端进程内另接 LLM）。
- workhorse 不可用时优雅降级 mock，CI/无 key 环境零依赖照常、测试全绿。
- 去掉演示假数据：默认空库走真实链路，演示场景收进 demo profile。
- 真凭据不入 git，修复上游被跟踪的 `frontend/deploy/workhorse/config.local.yaml` 隐患。

**Non-Goals:**
- 不真实化 `IntentRouter` 内的硬编码（GMV/orders/hour=8）——它们仅在降级兜底路径才走，保持原样。
- 不改 AG-UI 事件结构本身（类型/序列不变），只改默认走哪个大脑。
- 不引入后端进程内 LLM HTTP 客户端（诊断走 workhorse，不需要）。
- 不改 workhorse-agent 自身（保持通用，零特定改动）。

## Decisions

### D1：默认 `agent.mode=workhorse` + 启动健康探测降级
`application.yml` 默认值由 `mock` 改 `workhorse`。新增 `WorkhorseHealthProbe`：启动时探测 `workhorse.base-url` 健康（HTTP 探活），结果缓存 + 周期复探。`AguiOrchestrator` 与诊断 SPI 在每次使用前查探测状态：可用走 workhorse，不可用走 mock 兜底并打一次性 WARN，对话内温和提示「真实大脑暂不可用，已降级规则引擎」。
- **为何**：满足「默认真实」又不破坏 CI/无 key 零依赖（探测失败自动 mock）。
- **备选**：保持默认 mock + 启动参数手动切（用户否决，要默认真实）；彻底删 mock（破坏 CI，用户否决）。
- **取舍**：探测须快、不可每请求阻塞等超时——用缓存状态 + 后台周期探活，请求路径只读缓存。

### D2：诊断走 workhorse 用 SPI（接口在 master，实现在 api）
`DiagnosisAnalyzer` 接口留 master（内层，已存在）；`MockDiagnosisAnalyzer`（master）保留作降级兜底；新增 `WorkhorseDiagnosisAnalyzer`（api 层，`@Primary`）实现该接口：把失败实例的真实遥测（节点内存、近 7 天同类失败次数、OOM 日志、调度争抢）渲染成**结构化诊断 prompt**，调 `WorkhorseBridge` 跑一个 **headless 会话**，要求 LLM 输出 JSON `{title, rootCause, suggestions[]}`，映射为 `Analysis` 落 `task_diagnosis`/`finding`。
- **为何**：复用同一大脑（用户选择），且不破依赖方向——api 依赖 master，实现在外层注入，master 单独编译仍只见 mock。
- **备选**：master 进程内直连 DeepSeek（用户否决，倾向统一一个大脑）。
- **取舍**：LLM 自由文本 → 结构化需 prompt 约束 JSON + 容错解析；解析失败/超时回落 mock，保证诊断永不开天窗。

### D3：`WorkhorseBridge` 增 headless/oneshot 调用
现有 `WorkhorseBridge.run()` 面向 AG-UI SSE 流。新增 `runHeadless(prompt): Mono<String>`（或等价）——发起一个临时 workhorse 会话、消费到 `RUN_FINISHED`、聚合最终 assistant 文本返回，不经前端 SSE。诊断 SPI 复用它。
- **为何**：后台诊断不是对话流，需要阻塞式拿最终结果。
- **取舍**：headless 会话需设独立超时与会话清理，避免泄漏。

### D4：删除进程内 `MockLlmClient` / `LlmClient`
诊断改走 workhorse 后，进程内 LLM 客户端无任何调用方，作为死代码移除（接口与唯一 mock 实现一并删）。
- **为何**：YAGNI；真实 LLM 能力由 workhorse 统一提供，避免「两个大脑入口」的误导。
- **取舍**：`self-diagnosis` 原 spec 里「预留 LlmClient 接口替换真模型」的措辞需随之改为「经 workhorse SPI 替换」。

### D5：演示数据移 demo profile
`data.sql` 的 OOM@node-3 演示种子（诊断结论 / finding / node-3 高水位 / 对应 agent_action）抽到新文件 `demo-data.sql`；节点注册行（node-1..4 存在）保留在 `data.sql`（真实环境也需节点，运行时水位由 `HeartbeatReporter` 覆盖）。`application.yml` 默认 `data-locations` 只 `data.sql`；新增 `application-demo.yml` 追加 `demo-data.sql`，`-Dspring.profiles=demo` 才加载。
- **为何**：默认空库走真实链路；演示能力保留、可控。
- **取舍**：依赖该种子的测试须 `@ActiveProfiles("demo")` 或改用 `scripts/fault-injection.sql` 注入真实失败素材。

### D6：凭据安全与 git 卫生
DeepSeek token 只进 `deploy/workhorse/config.local.yaml`（已 gitignore）或 env `WORKHORSE_AGENT_PROVIDERS_ANTHROPIC_API_KEY`。修复隐患：`git rm --cached frontend/deploy/workhorse/config.local.yaml`，`.gitignore` 增 `**/config.local.yaml` 兜底。部署前验证 DeepSeek `<base_url>/v1/messages` 连通。

## Risks / Trade-offs

- [默认 workhorse 后本地/CI 起不来] → 健康探测自动降级 mock；探测在请求路径外，失败不阻塞。
- [LLM 输出非合法 JSON 导致诊断解析失败] → 容错解析 + 失败回落 `MockDiagnosisAnalyzer`，诊断永不缺席；prompt 强约束 JSON schema。
- [DeepSeek `/anthropic` 路径与 workhorse 拼接的 `/v1/messages` 不匹配] → 部署前先 curl 验证一次，不通则调 base_url。
- [headless 会话泄漏/超时拖慢巡检] → 独立超时 + 会话即用即清；巡检异步、单实例失败不阻塞批次。
- [真 token 误入 git] → 仅 gitignore 文件/env；修复 frontend 跟踪文件 + `**/config.local.yaml` 兜底；提交前 grep `sk-` 自检。
- [现有依赖演示种子的测试变红] → 同 PR 内迁移到 demo profile / fault-injection，作为验收一部分。

## Migration Plan

1. 配置：`config.local.yaml` 切 DeepSeek 并验证端点；`application.yml` 默认 mode→workhorse、拆 data-locations；加 `application-demo.yml`。
2. 代码：`WorkhorseBridge.runHeadless`；`WorkhorseHealthProbe`；`WorkhorseDiagnosisAnalyzer @Primary`；`AguiOrchestrator` 降级；删 `MockLlmClient`/`LlmClient`。
3. 数据：拆 `demo-data.sql`。
4. git 卫生：移除跟踪的 frontend config.local + gitignore 兜底。
5. 测试：迁移依赖种子的测试 + 新增分析器/降级/AG-UI 序列测试。
6. 验证：`./mvnw install -DskipTests` → 起 workhorse → 起后端 → 浏览器验证门（对话真实流式 + 真 LLM 诊断卡片）；再无 key 跑 `./mvnw test` 确认降级全绿。

**回滚**：`agent.mode=mock`（启动参数）即整体退回规则引擎；demo profile 与 mock 实现均保留，回滚零代码改动。

## Open Questions

- DeepSeek Anthropic 兼容端点是否支持 workhorse 需要的全部参数（如 thinking）——先关 thinking 保连通，确认后再开。
- headless 诊断会话是否复用 `agent_session` 表记录（审计一致性）还是用独立短会话——倾向独立短会话 + 仍写 `agent_action` 审计，实现时定。
