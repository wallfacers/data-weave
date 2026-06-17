## Context

DataWeave 当前是纯中文、零 i18n 基础设施的单语系统：

- **前端**（Next 16 App Router + CopilotKit v2 + React 19）：无国际化库、无 `middleware.ts`、无 `messages/`、无 `app/[locale]/`。约 **480 条**硬编码 UI 文案覆盖 64 个文件，热点集中在 `workspace/`（数据开发 IDE，占 ~60%）与 `ops/`（运维表，~12%）。日期统一走 `lib/types.ts:formatDateTime()`，写死 `yyyy-MM-dd HH:mm:ss`。
- **后端**（Spring Boot 4 / WebFlux / Jackson 3 / Java 25，四模块 DDD）：无 `MessageSource`、无 `messages*.properties`、无 `LocaleResolver`、无 `Accept-Language` 处理。约 **340 行**用户可见中文覆盖 50 个文件，热点是 `IntentRouter`（AG-UI markdown 回复，46 处）、`McpToolRegistry`（MCP 工具描述，67 处）、各异常 `throw new`（~65 处）、`ApiResponse.err/ok`（~31 处）。
- **核心矛盾**：前端约 20 处 `toast.error(j.message || "中文兜底")` 表明同一错误有「后端 message」与「前端兜底」两条来源，文案归属未定。

技术约束：前端必须遵守 CopilotKit v2 + AG-UI 直连栈；后端 WebFlux 非阻塞、Jackson 3、`spring-boot:run` 需先 `install`。AG-UI 是 SSE 长连接（`/agui`），locale 经请求头一次传入而非每帧携带。

利益相关：内部数据中台用户（中 / 英）；Agent 对话两模式（mock 规则引擎 / workhorse 真 LLM）。

## Goals / Non-Goals

**Goals:**

- 前端落地 next-intl（cookie-based locale），后端落地 `MessageSource` + `LocaleContextResolver`。
- 支持 zh-CN 与 en-US 双语，英文文案随变更产出（标注待母语校）。
- 建立三类文案归属规则 + 异常错误码本地化体系，消除「后端 message vs 前端兜底」矛盾。
- 设置面板提供「界面语言」+「Agent 语言」双独立切换。
- 日期 / 数字格式化按 UI locale。
- 沉淀 `internationalization` base spec 作为长期规范。

**Non-Goals:**

- 第三语种（日 / 繁中 / RTL 等）——首期仅 zh-CN + en-US。
- 路由前缀 `app/[locale]/`——采用 cookie 模式，不改路由结构与现有 `redirect("/?open=")` 深链。
- `data.sql` 业务种子数据双语（角色名 / 任务名 / 指标中文名保留中文）。
- 后端 logger 日志、代码注释的 i18n。
- 用户 locale 持久化到后端用户表（首期 cookie 够用）。

## Decisions

### D1 文案归属：混合策略（三类各走各的）

文案按**产生方式**分三类，i18n 归属不同：

| 类别 | 例子 | 归属 | 格式化 |
|---|---|---|---|
| ① UI 静态文案 | 按钮 / tab / 空状态 / 表单 label / tooltip | 前端 key 表（next-intl） | ICU `{name}` |
| ② 后端动态生成 | AG-UI markdown、MCP 工具描述、诊断建议、审批理由 | 后端 `MessageSource`（按 **agent locale**） | `{0}` MessageFormat |
| ③ 错误 / 异常 | `throw new`、`ApiResponse.err` | 后端 `code + 本地化 message`（按 **UI locale**） | `{0}` |

**为什么混合而非纯一端**：
- **纯前端（B）**：②类 AG-UI markdown、MCP 工具描述是后端运行时动态生成的长文本，无法用前端 key 替换 → 否决。
- **纯后端（C）**：①类 UI 控件文案（数百条按钮 / label）走后端接口生成极啰嗦，且前端仍需 locale 上下文 → 否决。
- **混合（A）**：两端各干擅长的，动态文本在后端本地化、静态文本在前端本地化 → 采纳。

### D2 前端 locale：cookie-based，无路由前缀

locale 存 cookie（`NEXT_LOCALE`），URL 不变（`/` 仍是 `/`）。next-intl 的 non-routed 模式：`i18n/request.ts` 的 `getRequestConfig` 读 cookie / `Accept-Language`，`NextIntlClientProvider` 注入。

**为什么不用 `app/[locale]/`**：DataWeave 是内部数据中台，SEO 无关；路由方案要重构所有路由并处理 CLAUDE.md 里 `redirect("/?open=<view>")` 深链兜底，改动大且收益为零。cookie 模式改动最小、对深链透明。

### D3 前端 i18n 库：next-intl

**为什么不是 react-i18next**：next-intl 原生适配 Next App Router（Server / Client Component 皆可、`getRequestConfig`、ICU 内建），与 React 19 + Next 16 对齐；react-i18next 生态更大但 App Router 集成需额外配 provider 与 hydration 处理。

### D4 双 locale 分离：UI locale vs Agent locale

两个独立 locale，各自 cookie + 请求头：

- **UI locale**（`NEXT_LOCALE` cookie / `Accept-Language` 头）：管界面壳——按钮、tab、表单、空状态、错误 toast。
- **Agent locale**（`DW_AGENT_LOCALE` cookie / `x-dw-agent-locale` 头）：管 Agent 对话——AG-UI markdown 回复、MCP 工具描述、workhorse system prompt。

**为什么分离**：用户明确要求设置面板「界面语言」与「Agent 语言」各自可选。分离后「中文界面 + 英文 Agent 对话」也成立。前端 `settings` 默认把 Agent locale 设为「跟随界面」，保持默认一致体验。

### D5 后端 locale 协商：Accept-Language + LocaleContextResolver

WebFlux 下配置 `LocaleContextResolver`（默认 `AcceptHeaderLocaleContextResolver` 读 `Accept-Language`），`MessageSource` bean（`spring.messages.basename=messages`，UTF-8，Spring Boot 4 默认 UTF-8）。提供统一 `Messages` 包装：

```
Messages.get(code, locale, args...) → 本地化字符串
Messages.get(code, LocaleContextHolder, args...) → 按 UI locale
```

业务代码不直接 `@Autowired MessageSource`，统一走 `Messages` 包装，locale 由调用方明确传入（UI 场景从 exchange 取，Agent 场景从请求头 `x-dw-agent-locale` 取）。

### D6 异常错误码：BizException(code, args) + GlobalExceptionHandler 本地化

现状 `throw new XxxException("中文")` 改为 `throw new BizException("workflow.not_online", args)`。`GlobalExceptionHandler` 捕获后：

```
message = Messages.get(exception.code, uiLocale, exception.args)
→ ApiResponse.err(code, message)   // message 已是按 UI locale 的本地化字符串
```

**为什么不是「后端只回 code，前端查表」**：回流场景（toast、审批卡片、实例 error_message）后端本就握有运行时上下文，由后端本地化 message 更自然；且前端 20 处 `|| 中文兜底` 正是因后端 message 不可靠才加的兜底——后端 message 本地化后兜底下线。前端据 `code` 做特殊 UI（如 `WORKFLOW_NOT_PUBLISHED` → 「去发布」按钮），message 仅作展示。

错误码命名：`<domain>.<semantic>`，kebab/snake 一致（如 `workflow.not_online`、`catalog.folder.not_empty`、`approval.not_found`、`auth.invalid_credentials`）。code 必须稳定、永不复用。

### D7 Agent locale 传输：x-dw-agent-locale 请求头

AG-UI `/agui` 是 SSE 长连接，locale 在建连时经请求头 `x-dw-agent-locale` 一次传入，后端整条 run 内沿用。`IntentRouter` / `McpToolRegistry` / `WorkhorseBridge` 从请求上下文取 agent locale 生成文案。缺失时 fallback UI locale，再 fallback zh-CN。

### D8 mock IntentRouter 双语 + workhorse system prompt 双语

- **mock**：`IntentRouter` 的 NLU 关键词词典双语化（`containsAny(msg, "诊断","为什么失败","diagnose","why failed")`，`COUNT_PATTERN` / `HOUR_PATTERN` 扩展英文关键词）；markdown 回复全部走 `Messages.get(..., agentLocale)`。
- **workhorse**：`WorkhorseBridge` 的 system prompt 提供 zh / en 两版，按 agent locale 选取；MCP 工具描述按 agent locale 生成。

### D9 日期 / 数字：Intl 格式化器按 UI locale

`lib/types.ts:formatDateTime()` 改用 `Intl.DateTimeFormat(locale, {...})`，一处改覆盖 5 个复用点（cockpit-view / instance-table / task-def-list / freshness-view / log-viewer-panel）。`log-viewer-panel.tsx:75` 的 `toLocaleString()` 显式传 locale，并修复 `bytes` 单位硬编码英文。

### D10 种子数据豁免

`data.sql` 业务数据（角色名 `'管理员'`、任务名 `'GMV 统计'`、指标中文名）保留中文，仅 i18n 代码路径。mock 演示用的 `error_message` / 诊断 JSON 维持中文。理由：种子是演示数据，双语化收益低、维护成本高；规范里以豁免条款明确标注。

## Risks / Trade-offs

- **[文案遗漏]** 全盘覆盖 ~480 前端 + ~340 后端条目，逐条迁移易漏 → Mitigation: tasks 按热点文件分组 + 可选 lint 脚本（grep 残留中文 JSX 文本节点）兜底。
- **[前后端 locale 不一致]** 前端漏带请求头导致后端 fallback 中文 → Mitigation: 统一在 fetch 层 / AG-UI client 注入 `Accept-Language` 与 `x-dw-agent-locale`，单点维护。
- **[mock 关键词遗漏英文]** 英文用户消息匹配不上意图 → Mitigation: 双语关键词测试覆盖（`IntentRouterIntentTest` 增补英文用例）。
- **[错误码爆炸 / 命名漂移]** code 无规范会失控 → Mitigation: `<domain>.<semantic>` 命名规范 + 集中 code 清单（design 附件或单独 md）。
- **[占位符格式不一致]** 前端 ICU `{name}` vs 后端 MessageFormat `{0}` → Mitigation: 两端 key 体系独立、语义对齐，design 明确各自格式约定，不追求 key 字符串统一。
- **[英文翻译质量]** 非母语产出可能有瑕疵 → Mitigation: 全量标注「待母语校」，不阻塞功能；data 中台术语（cron / DAG / 血缘）保留英文原词。
- **[workhorse LLM 语种漂移]** system prompt 指定英文但 LLM 偶尔回中文 → Mitigation: system prompt 明确「始终以 {locale} 回复」+ Browser Verification 抽检。
- **[Spring Boot 4 MessageSource 配置坑]** 默认 encoding / basename 行为 → Mitigation: 显式配置 basename 与 UTF-8，Post-Edit Verification 编译验证。

## Migration Plan

渐进式四阶段，每阶段独立可验证、可回滚（cookie 默认 zh-CN，未带请求头时行为等价现状）：

1. **后端基础设施**：`MessageSource` + `messages_zh_CN.properties`（先把现有中文迁入）+ `LocaleContextResolver` + `Messages` 包装 + `BizException` + `GlobalExceptionHandler` 本地化。此阶段 en_US properties 可为空 / 占位，默认 zh-CN 行为不变。
2. **前端基础设施**：next-intl 安装 + `messages/zh-CN.json`（迁入现有中文）+ `middleware.ts` + `NextIntlClientProvider` + 设置面板语言区。抽 key 按热点文件分批。
3. **Agent 本地化**：`IntentRouter` 关键词双语 + markdown 走 `Messages`；`McpToolRegistry` 工具描述走 `Messages`；`WorkhorseBridge` system prompt 双语版；`x-dw-agent-locale` 传输。
4. **en-US 产出 + 日期/数字 + 测试**：填充 `messages/en-US.json` / `messages_en_US.properties`；`formatDateTime` 改 Intl；en_US bundle 断言；前端 Browser Verification Gate 切 locale 实跑。

**回滚**：删除 cookie `NEXT_LOCALE` / `DW_AGENT_LOCALE`，或前端强制 `locale='zh-CN'`，即回到单语现状；后端 code 体系保留（不影响 zh-CN 默认路径）。

## Open Questions

- 英文文案最终母语校对的负责人 / 时间窗？（不阻塞，首期标注待校）
- MCP 工具描述本地化为英文后，是否影响 workhorse LLM 的工具选择准确率？（需 workhorse 模式实测，Tasks 列验证项）
- 是否在 `metrics` / `audit` 审计记录里留存「用户请求时的 locale」便于分析？（首期不做，留作后续）
