## 1. 凭据与配置（DeepSeek 接入）

- [x] 1.1 `deploy/workhorse/config.local.yaml`（gitignore）切 DeepSeek：`base_url=https://api.deepseek.com/anthropic`、模型 `deepseek-v4-pro`/`deepseek-v4-flash`、api_key 字面量（不入 git）
- [x] 1.2 验证 DeepSeek `<base_url>/v1/messages` 路径连通 — curl 返回 **HTTP 200**
- [x] 1.3 git 卫生：`git rm --cached frontend/deploy/workhorse/config.local.yaml`，`.gitignore` 增 `**/config.local.yaml` 兜底；暂存区扫 `sk-` 无真 token

## 2. 默认模式切换 + 优雅降级（agent-bridge）

- [x] 2.1 `application.yml` `agent.mode` 默认由 `mock` 改为 `workhorse`
- [x] 2.2 新增 `WorkhorseHealthProbe`（+ `WorkhorseHealth` 接口）：启动就绪探测 + 周期复探，状态缓存于请求路径外
- [x] 2.3 `AguiOrchestrator` 增降级分支：workhorse 不健康→走 `IntentRouter`，一次性 WARN + 对话内前置降级提示（i18n `agent.degraded.notice`）
- [x] 2.4 两模式（含降级）AG-UI 事件序列同构（`AguiDegradeTest` 验证降级仍产出 RUN_STARTED…RUN_FINISHED + 结构化结果）

## 3. 诊断走 workhorse（self-diagnosis，SPI）

- [x] 3.1 `WorkhorseBridge.runHeadless(instructions, message)`：起临时会话、聚合 text 增量为最终文本（Mono）
- [x] 3.2 api 层 `WorkhorseDiagnosisAnalyzer`（`@Primary` 实现 master `DiagnosisAnalyzer`）：真实遥测→JSON prompt→runHeadless→容错解析→`Analysis`
- [x] 3.3 workhorse 不健康 / 解析失败回落 `MockDiagnosisAnalyzer`（`WorkhorseDiagnosisAnalyzerTest` 三场景验证）
- [x] 3.4 `DiagnosisService`/SPI 编排骨架不变；依赖方向不破（接口在 master，实现在 api `@Primary`）

## 4. 删除进程内 LLM 占位

- [x] 4.1 删除 `MockLlmClient` 与 `LlmClient` 接口
- [x] 4.2 清理引用：`IntentRouter` 去 llmClient 字段/构造参数 + 注释；两个 IntentRouter 测试去 `@Mock LlmClient`

## 5. 演示数据移 demo profile

- [x] 5.1 新建 `demo-data.sql`，迁入 `task_diagnosis`/`finding`(+RESTART)/`audit_log` DIAGNOSE OOM 演示种子；FAILED 实例素材与节点注册保留在 `data.sql`
- [x] 5.2 默认 `data-locations` 仅 `data.sql`；新增 `application-demo.yml` 追加 `demo-data.sql`
- [x] 5.3 默认启动空库无演示假数据（data.sql 已去种子）；`demo` profile 加载演示场景（FindingEndpointTest 用 h2+demo 验证）

## 6. 测试

- [x] 6.1 `FindingEndpointTest` 加 `@ActiveProfiles({"h2","demo"})` 引入 OOM 种子素材
- [x] 6.2 `WorkhorseDiagnosisAnalyzerTest`：健康→JSON→Analysis；不健康/不可解析→回落 mock
- [x] 6.3 降级路径：`AguiDegradeTest`（默认 workhorse + 不健康 probe → 走 IntentRouter）
- [x] 6.4 `AguiDegradeTest` 断言降级仍产出合法 AG-UI 序列 + metric 结构化结果
- [x] 6.5 `dataweave-api` 完整测试 BUILD SUCCESS（含修复 `DataWeaveApiApplication` 的 `@TestConfiguration` 跨测试泄漏）

## 7. 活体验证（验收门）— 已通过（DeepSeek + 真实 workhorse）

- [x] 7.1 `scripts/build.sh` 构建 host 二进制 → 起 workhorse(DeepSeek，MCP 注册 26 工具) → 起 dataweave-api(probe 探到 healthy「启用真实大脑」)
- [x] 7.2 浏览器验证门：前端切 real provider，发消息 → workhorse **真实流式**作答「2 个失败实例…」(非固定话术)，agent_run #100 FINISHED + workhorse 新会话确认到达后端；console **0 error**
- [x] 7.3 真 LLM 诊断：Inspector 巡检 FAILED 实例 → `task_diagnosis` 标题/根因为真模型生成(「Shuffle 数据过大导致 Executor OOM…shuffle read 4.2GB…9.4GB 超 8GB」非模板)，UI「失败诊断」视图呈现
- [x] 7.4 截图存 `tmp/live-real-brain.png`，`.playwright-mcp` 已清理
- [x] 7.5 降级路径活体：后端默认 workhorse、workhorse 未起时 `WorkhorseHealthProbe` WARN「降级到规则引擎 mock」+ 探到后「启用真实大脑」自动恢复

## 8. 活体验证发现并修复的 bug

- [x] 8.1 `WorkhorseBridge.runHeadless` 的 session metadata 值须为**字符串**：传 `{"headless": true}`(boolean) 致 workhorse `/v1/sessions` 返回 400、诊断回落 mock；改为 `"true"` 后真 LLM 诊断通
- [x] 8.2 前端 `.env.local` `NEXT_PUBLIC_AGENT_URL` 错配 `8080`(后端在 8000) + 缺 `NEXT_PUBLIC_CHAT_MOCK=0`——前端一直 mock 从未连后端；改为 8000 + CHAT_MOCK=0 后前端 real provider 端到端打通
