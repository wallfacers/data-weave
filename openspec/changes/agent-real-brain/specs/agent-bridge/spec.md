## MODIFIED Requirements

### Requirement: 双模式开关
系统 SHALL 支持 `agent.mode=mock|workhorse`，**默认 `workhorse`**（真实大脑）。workhorse 模式把对话转发至 workhorse 会话；mock 模式走既有 IntentRouter 路径且零外部依赖。系统 SHALL 在启动时及周期性探测 workhorse 健康（经 `WorkhorseHealthProbe`），探测结果缓存于请求路径之外；当 workhorse 不可用（未配置/sidecar 未起/探测失败）时 MUST 自动降级到 mock 路径，打一次性 WARN，并在对话中温和提示已降级。两模式（含降级）MUST 产出同构的 AG-UI 事件序列（RUN_STARTED…RUN_FINISHED，经同一 AguiOrchestrator 出口）。

#### Scenario: 默认 workhorse 真实作答
- **WHEN** 配置了可用的 workhorse（DeepSeek 大脑）且采用默认 mode
- **THEN** 用户消息经桥接层转发至 workhorse 会话，模型流式文本经 AG-UI TEXT_MESSAGE_CONTENT 增量送达，回答非 IntentRouter 固定话术

#### Scenario: workhorse 不可用时优雅降级
- **WHEN** mode 为默认 workhorse 但 sidecar 未起 / 无 key / 健康探测失败
- **THEN** 系统自动走 IntentRouter mock 路径作答，记录一次性 WARN，对话内提示「真实大脑暂不可用，已降级规则引擎」，AG-UI 事件序列仍合法完整

#### Scenario: 无依赖环境（CI/fresh clone）零依赖照旧
- **WHEN** 环境无 workhorse、无 LLM key（如 CI）
- **THEN** 健康探测失败触发降级，现有全部意图与测试行为不变、测试全绿

#### Scenario: 显式 mock 模式
- **WHEN** 显式设置 `agent.mode=mock`
- **THEN** 直接走 IntentRouter，不进行 workhorse 探测，行为与既有 mock 一致
