## Why

DataWeave 当前是**纯中文、零 i18n 基础设施**：前端无国际化库、后端无 `MessageSource`，所有界面文案、Agent 对话回复、错误信息均硬编码中文。全盘扫描显示前端约 **480 条**硬编码 UI 文案（覆盖 64 个文件）、后端约 **340 行**用户可见中文（覆盖 50 个文件），且前端约 20 处 `toast.error(j.message || "中文兜底")` 暴露出一个核心矛盾——同一错误有「后端 message」与「前端兜底」两条来源，文案归属不清。作为面向 Agent 的数据中台，需要支持中/英双语以服务国际化场景，并在此过程中把文案归属、locale 协商、错误码体系沉淀为一项正式规范。

## What Changes

- **前端引入 next-intl（cookie-based locale，无路由前缀）**：新建 `messages/zh-CN.json` + `messages/en-US.json`、`middleware.ts` 读 `NEXT_LOCALE`、`NextIntlClientProvider` 包裹。UI 静态文案（按钮 / tab / 表单 / 空状态 / tooltip）改为 key 引用。
- **后端引入 Spring `MessageSource` + WebFlux `LocaleContextResolver`**：按 `Accept-Language` 协商 locale，新建 `messages_zh_CN.properties` + `messages_en_US.properties`，并提供统一 `Messages.get(code, locale, args)` 包装。
- **设置面板新增「语言」区**（位于主题选择下方）：界面语言 + Agent 语言两个独立选择，分别存 cookie `NEXT_LOCALE` 与 `DW_AGENT_LOCALE`。
- **三类文案归属规则**（规范核心）：① UI 静态文案 → 前端 key 表；② 后端动态生成文案（AG-UI markdown 回复、MCP 工具描述、诊断建议、审批理由）→ 后端 `MessageSource` 按 **agent locale**；③ 错误/异常 → 后端返回 `code + 本地化 message`，按 **UI locale**。
- **异常改为携带稳定 code**：`throw new BizException("workflow.not_online", args)`，`GlobalExceptionHandler` 经 `MessageSource` 把 code 翻成本地化 message；前端约定「后端 message 已本地化，直接展示」，`|| 中文兜底` 模式下线。
- **Agent 语种双语化（mock + workhorse 双模式）**：mock `IntentRouter` 的 NLU 关键词词典双语 + markdown 走 `MessageSource`；workhorse `WorkhorseBridge` system prompt 提供 zh/en 两版；经 `x-dw-agent-locale` 请求头传输 agent locale。
- **日期 / 数字格式化 locale 化**：`lib/types.ts:formatDateTime()` 改用 `Intl.DateTimeFormat(locale)`（单点改造覆盖 5 个复用点），`toLocaleString()` 显式传 locale。
- **英文文案产出**：高质量英文翻译由本变更产出，统一标注「待母语校」。
- **种子数据策略**（豁免条款）：`data.sql` 中的业务数据（任务名 / 角色名 / 指标中文名）保留中文不双语，仅 i18n 代码路径；mock 演示用的 `error_message` / 诊断 JSON 维持中文。

## Capabilities

### New Capabilities

- `internationalization`: 国际化能力规范——文案归属三规则、locale 协商链路、key 命名规范、错误码本地化体系、Agent 语种、日期 / 数字格式化。

### Modified Capabilities

- `agent-ui-events`: AG-UI 事件序列（`RUN_STARTED…RUN_FINISHED`）不变，但 `TEXT_MESSAGE_CONTENT` 的 markdown 内容改为按 agent locale 本地化，并新增 `x-dw-agent-locale` 请求头约定。

## Impact

- **前端依赖**：新增 `next-intl`；新增 `middleware.ts`、`messages/`、`NextIntlClientProvider`。影响 `catalog-tree.tsx`、`task-editor-pane.tsx`、`workflow-canvas-view.tsx`、`instance-table.tsx`、`settings-sheet.tsx`、`views/*.tsx` 等约 64 个文件、约 480 条文案。
- **后端**：新增 `MessageSource` 配置、`messages_*.properties`、`LocaleContextResolver`、`Messages` 包装。影响 `IntentRouter.java`、`McpToolRegistry.java`、`WorkhorseBridge.java`、`GlobalExceptionHandler.java`、`WorkflowService.java`、`ScheduleParamResolver.java`、`CatalogTreeService.java`、`ApprovalService.java`、`PolicyEngine.java`、`DefaultPlatformActionExecutor.java`、各 Controller、Worker 执行器等约 50 个文件、约 340 行用户可见文案。后端无新增第三方依赖（Spring 自带 `MessageSource`）。
- **API 契约**：异常 / 错误响应从 `{code, message}` 演进为 `{code, message(localized)}`——`message` 仍是字符串、字段不变，故前端兼容；请求头新增 `Accept-Language`（UI locale）与 `x-dw-agent-locale`（Agent locale）。
- **测试**：约 50 处中文断言（20 个后端测试文件）需对齐 zh_CN bundle，并为 en_US bundle 增补断言；前端需在 Browser Verification Gate 下做 locale 切换实跑。
- **范围外**：约 1160 行中文注释、36 行 logger 日志、`data.sql` 业务数据中文——均不在本次 i18n 范围。
