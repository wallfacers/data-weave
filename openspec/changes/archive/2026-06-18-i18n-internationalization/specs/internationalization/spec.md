## ADDED Requirements

### Requirement: 文案归属三类规则

系统 SHALL 按文案产生方式将其国际化归属到三处之一，且不得跨类混用：

- **① UI 静态文案**（按钮、tab、表单 label、空状态、tooltip、占位符等）MUST 经前端 i18n key 表（`messages/<locale>.json`）引用，禁止在 JSX 中硬编码面向用户的字面量。
- **② 后端动态生成文案**（AG-UI markdown 回复、MCP 工具描述、诊断建议、审批理由、裁决理由等）MUST 经后端 `MessageSource`（`messages_<locale>.properties`）按 **agent locale** 本地化产出。
- **③ 错误 / 异常文案** MUST 以「稳定 code + 本地化 message」形式返回，message 按 **UI locale** 本地化。

#### Scenario: UI 静态文案走前端 key

- **WHEN** 用户查看类目树右键菜单
- **THEN** 「重命名」「删除」等菜单项文案来自 `messages/<uiLocale>.json` 的 key（如 `workspace.catalogTree.rename`），源码中无硬编码中文 JSX 文本节点

#### Scenario: 动态生成文案走后端 MessageSource

- **WHEN** Agent 产生一段诊断 markdown 回复
- **THEN** 「根因」「修复建议」等标题与正文来自后端 `messages_<agentLocale>.properties`，按当前 agent locale 本地化

#### Scenario: 错误返回 code 与本地化 message

- **WHEN** 用户触发的操作命中「工作流未上线」异常
- **THEN** 响应体为 `{ code: "workflow.not_online", message: "<按 UI locale 本地化的字符串>" }`，`message` 既非硬编码中文、也非技术堆栈

### Requirement: Locale 双通道分离

系统 SHALL 维护两个相互独立的 locale：**UI locale**（管界面壳文案）与 **Agent locale**（管 Agent 对话回复语种）。二者 MUST 可独立设置、独立持久化、独立传输。UI locale 经 `Accept-Language` 请求头传输，Agent locale 经 `x-dw-agent-locale` 请求头传输。

#### Scenario: 界面语言与 Agent 语言各自独立

- **WHEN** 用户将界面语言设为中文、Agent 语言设为 English
- **THEN** 界面按钮 / 表单显示中文，而 Agent 对话 markdown 回复以英文产出

#### Scenario: 请求头分别携带双 locale

- **WHEN** 前端发起任一请求
- **THEN** 请求头同时包含 `Accept-Language: <uiLocale>` 与 `x-dw-agent-locale: <agentLocale>`

### Requirement: 设置面板语言切换入口

系统 SHALL 在设置面板「主题」选择区下方提供「语言」区，包含「界面语言」与「Agent 语言」两个选择项。「界面语言」选项 SHALL 为「中文 / English」；「Agent 语言」选项 SHALL 为「跟随界面 / 中文 / English」。所选 MUST 持久化到 cookie（`NEXT_LOCALE` 与 `DW_AGENT_LOCALE`）。

#### Scenario: 用户切换界面语言

- **WHEN** 用户在设置面板将界面语言从「中文」切到「English」
- **THEN** 界面立即切换为英文文案，cookie `NEXT_LOCALE` 写入 `en-US`，刷新后保持

#### Scenario: Agent 语言跟随界面

- **WHEN** 用户将 Agent 语言设为「跟随界面」，且界面语言为中文
- **THEN** Agent 对话回复以中文产出；切换界面语言为英文后，Agent 回复随之切英文

### Requirement: 前端 locale 协商（cookie-based）

前端 SHALL 采用 cookie-based locale（无路由前缀），URL 不随 locale 变化。locale 解析顺序 SHALL 为：cookie `NEXT_LOCALE` → `Accept-Language` → 默认 `zh-CN`。缺失或不支持的 locale MUST fallback 到 `zh-CN`。

#### Scenario: cookie 决定界面 locale

- **WHEN** cookie `NEXT_LOCALE=en-US` 的用户访问 `/`
- **THEN** 界面渲染英文文案，URL 仍为 `/`（无 `/en` 前缀）

#### Scenario: 缺失 cookie 时 fallback 中文

- **WHEN** 无 `NEXT_LOCALE` cookie 且无 `Accept-Language` 头
- **THEN** 界面渲染中文文案（等价现状）

### Requirement: 后端 locale 协商

后端 SHALL 配置 `MessageSource` 与 `LocaleContextResolver`，按请求头解析 locale 并本地化文案。UI 文案（错误 message、REST 响应）按 `Accept-Language` 本地化；Agent 文案（AG-UI markdown、MCP 工具描述、system prompt）按 `x-dw-agent-locale` 本地化。`x-dw-agent-locale` 缺失时 SHALL fallback 到 `Accept-Language`，再 fallback 到 `zh-CN`。

#### Scenario: Accept-Language 决定错误本地化

- **WHEN** 请求头 `Accept-Language: en-US` 触发 `workflow.not_online` 异常
- **THEN** 响应 `message` 为该 code 的英文本地化字符串

#### Scenario: x-dw-agent-locale 决定 Agent 回复语种

- **WHEN** AG-UI `/agui` 请求头 `x-dw-agent-locale: en-US`
- **THEN** 该 run 内所有 `TEXT_MESSAGE_CONTENT` 的 markdown 与 MCP 工具描述以英文产出

### Requirement: 异常错误码本地化体系

后端所有面向用户的异常 MUST 携带稳定 code 与可插值参数（`BizException(code, args)`）。`GlobalExceptionHandler` SHALL 经 `MessageSource` 将 code 翻译为按 UI locale 的本地化 message，再写入 `ApiResponse`。前端 SHALL 直接展示后端 message（已本地化），不得再附加硬编码中文兜底；前端 MAY 据 code 触发特殊 UI（如跳转、二次确认）。

#### Scenario: 异常返回本地化 message

- **WHEN** 后端抛出 `BizException("approval.not_found", [approvalId="#42"])`
- **THEN** 经 handler 处理后响应 message 为按 UI locale 插值后的本地化字符串（如「未找到审批单 #42」/「Approval #42 not found」）

#### Scenario: 前端据 code 触发特殊 UI

- **WHEN** 前端收到 `{ code: "workflow.not_published" }` 错误
- **THEN** 前端 MAY 渲染「去发布」按钮引导用户，而非仅展示 message 文本

### Requirement: 错误码命名规范

所有错误 code MUST 采用 `<domain>.<semantic>` 形式（如 `workflow.not_online`、`catalog.folder.not_empty`、`auth.invalid_credentials`）。code MUST 稳定、永不复用、永不改语义。同一业务错误在 code 体系内唯一。

#### Scenario: 错误码命名遵循规范

- **WHEN** 新增一个「占位符未闭合」错误
- **THEN** 其 code 形如 `schedule.placeholder.unclosed`（域 + 语义），而非自增数字或自由文本

### Requirement: 日期与数字按 locale 格式化

系统 SHALL 使用 `Intl.DateTimeFormat` / `Intl.NumberFormat` 按 UI locale 格式化面向用户的日期与数字，禁止硬编码固定格式串（如 `yyyy-MM-dd HH:mm:ss`）或无 locale 参数的 `toLocaleString()`。

#### Scenario: 日期按 UI locale 格式化

- **WHEN** UI locale 为 `en-US` 的用户查看实例列表的时间列
- **THEN** 时间按英文区域习惯格式化，而非固定的 `yyyy-MM-dd HH:mm:ss`

### Requirement: 双语覆盖与 fallback

系统 SHALL 为 zh-CN 与 en-US 提供完整的 messages 文件（前端 `messages/zh-CN.json` / `messages/en-US.json`，后端 `messages_zh_CN.properties` / `messages_en_US.properties`）。两套文件的 key 集合 SHALL 一致。某 locale 缺失某 key 时 MUST fallback 到 zh-CN，并不得抛出运行时错误。

#### Scenario: 英文 bundle 完整

- **WHEN** 以 en-US 运行全量界面
- **THEN** 所有可见文案均有英文值，无 key 原文裸露（无 `workspace.catalogTree.rename` 字符串显示给用户）

#### Scenario: 缺失 key fallback 中文

- **WHEN** en-US bundle 缺失某 key
- **THEN** 该文案 fallback 显示 zh-CN 的值，不报错、不中断渲染

### Requirement: 种子数据 i18n 豁免

`data.sql` 中的业务种子数据（角色名、任务名、工作流名、指标中文名等）SHALL 保留中文，不纳入双语化范围。代码路径（异常 message、动态文案）的 i18n 不受此豁免影响。

#### Scenario: 业务种子数据保留中文

- **WHEN** 以 en-US locale 查看由种子数据填充的任务名（如「GMV 统计」）
- **THEN** 任务名仍显示中文（属业务数据，非 i18n 范畴）
