## 1. 后端 i18n 基础设施

- [x] 1.1 配置 `MessageSource` bean（`spring.messages.basename=messages`，UTF-8，Spring Boot 4 默认 UTF-8 显式声明）— `I18nConfig.messageSource()`
- [x] 1.2 配置 `LocaleContextResolver`（`AcceptHeaderLocaleContextResolver`，按 `Accept-Language` 解析 UI locale）— 改用 `Locales.uiLocale(headers)` 直接读 Accept-Language（design D5 允许的等价方案，更易测、不依赖 Spring bean）
- [x] 1.3 创建 `Messages` 包装（`Messages.get(code, locale, args...)`），业务代码统一经此调用，不直接 `@Autowired MessageSource` — `master/i18n/Messages.java`
- [x] 1.4 创建 `messages_zh_CN.properties`（先把现有中文文案以 `<domain>.<semantic>` code 形式迁入）— 用 `messages.properties`（中文 base + fallback）+ `messages_en_US.properties`，先收录 common/auth/approval/workflow 基础 key，其余随 §2/§3 迁入
- [x] 1.5 创建 `BizException(code, args)` 基类（替代裸 `throw new XxxException("中文")`）— `master/i18n/BizException.java`（含 `withHttpStatus` 链式）
- [x] 1.6 改造 `GlobalExceptionHandler`：捕获 `BizException` → `Messages.get(code, uiLocale, args)` → `ApiResponse.err(code, localized message)`；底层异常 `e.getMessage()` 不再直接透传 — 兜底文案（internal_error/request_failed）已本地化；CatalogException/IllegalArgument 等暂留待 §2 迁移
- [x] 1.7 解析 `x-dw-agent-locale` 请求头，提供 agent locale 取用入口（缺失 fallback `Accept-Language` → `zh-CN`）— `Locales.agentLocale(headers)`
- [x] 1.8 `cd backend && ./mvnw -q compile` 零编译错误

## 2. 后端异常错误码迁移（~65 处 `throw new`）

- [ ] 2.1 `WorkflowService`（9 处）→ `BizException` + `workflow.*` code（`workflow.not_online` / `workflow.node.key.duplicate` 等）
- [ ] 2.2 `ScheduleParamResolver`（9 处 `UnresolvedPlaceholderException`）→ `schedule.*` code
- [ ] 2.3 `CatalogTreeService`（8 处 `CatalogException`）→ `catalog.*` code（保留原错误码语义）
- [ ] 2.4 `S3LogArchiveStorage` / `FileLogArchiveStorage`（8 处）→ `archive.*` code
- [ ] 2.5 `WorkflowGraphValidator`（3 处）/ `TagService`（4 处）→ code
- [ ] 2.6 `AuthService`（2 处：账号禁用 / 凭证错误）→ `auth.*` code
- [ ] 2.7 `ApprovalService`（7 处）→ `approval.*` code
- [ ] 2.8 `PolicyEngine` 裁决理由（10 处 `reasons.add`）→ `Messages.get` 按 UI locale
- [ ] 2.9 编译验证 + 现有异常断言测试对齐 zh_CN

## 3. 后端 REST API message 本地化（~31 处，11 Controllers）

- [ ] 3.1 `ProjectController`（5）/ `UserController`（4）/ `TaskController`（4）/ `RoleController`（4）
- [ ] 3.2 `WorkflowController`（3）/ `ClusterController`（3）/ `TenantController`（2）/ `OpsController`（2）
- [ ] 3.3 `AuthController`（2）/ `WorkspaceController`（1）/ `CatalogController`
- [ ] 3.4 各 `ApiResponse.err/ok("中文")` → `code + Messages` 本地化
- [ ] 3.5 编译验证

## 4. 后端 Agent 动态文案本地化（按 agent locale）

- [ ] 4.1 `IntentRouter`：markdown 回复（46 处）→ `Messages.get(..., agentLocale)`（根因 / 修复建议 / 指标溯源 / Text-to-SQL / 建任务 / 血缘 / 帮助兜底段）
- [ ] 4.2 `IntentRouter`：NLU 关键词词典双语化（诊断 / 计数 / cron / 建任务 等意图扩展英文关键词）
- [ ] 4.3 `McpToolRegistry`：工具描述 + 参数描述（67 处）→ `Messages` 按 agent locale（约 16 个工具）
- [ ] 4.4 `McpToolRegistry`：参数校验异常（5 处）+ `ToolResult.error`（3 处）+ 审批单 summary（12 处）→ code / `Messages`
- [ ] 4.5 `WorkhorseBridge`：system prompt 提供 zh / en 两版，按 agent locale 选取
- [ ] 4.6 `MockDiagnosisAnalyzer`：诊断 `title` / `rootCause` / `suggestions.label`（16 处）→ `Messages`（注意手工拼接 JSON 的转义）
- [ ] 4.7 `DefaultPlatformActionExecutor`：`ExecOutcome.message`（22 处）→ `Messages`
- [ ] 4.8 `PageContext.toString`（6 处 LLM 上下文文案）→ `Messages`
- [ ] 4.9 编译验证 + `IntentRouterIntentTest` 增补英文意图用例

## 5. 后端 Worker 执行结果本地化

- [ ] 5.1 `WorkerExecService`（3 处）→ `Messages`
- [ ] 5.2 `ControlledCommandExecutor`（8 处：白名单 / 超时 / 退出码 / 中断）→ `Messages`
- [ ] 5.3 `ShellTaskExecutor`（5 处 shell 执行状态）→ `Messages`
- [ ] 5.4 `WorkerNodeExecGateway`（4 处：未指定节点 / 节点不存在 / 离线 / 截断）→ `Messages`
- [ ] 5.5 编译验证

## 6. 前端 i18n 基础设施

- [ ] 6.1 安装 `next-intl`，确认与 CopilotKit v2 + `@ag-ui/client@0.0.53` + Next 16 兼容（参考 frontend-stack 约束）
- [ ] 6.2 创建 `i18n/request.ts`（`getRequestConfig` 读 cookie `NEXT_LOCALE` → `Accept-Language` → `zh-CN`）
- [ ] 6.3 创建 `middleware.ts`（locale 协商，URL 不变，不动 `redirect("/?open=")` 深链）
- [ ] 6.4 `NextIntlClientProvider` 包裹 app（与 `CopilotKitProvider` 同层）
- [ ] 6.5 创建 `messages/zh-CN.json` 骨架（namespace 按 `workspace.*` / `ops.*` / `common.*` / `agent.*` 分层）
- [ ] 6.6 fetch / `HttpAgent` 层统一注入 `Accept-Language`（UI locale）+ `x-dw-agent-locale`（Agent locale）请求头
- [ ] 6.7 `cd frontend && pnpm typecheck` 零类型错误

## 7. 前端 UI 静态文案抽 key（按热点文件）

- [ ] 7.1 `lib/workspace/views.ts`（视图标题字典 15 处）— 集中式起点，示范 key 结构
- [ ] 7.2 `components/workspace/catalog-tree.tsx`（54 处：右键菜单 / 对话框 / toast / 空状态）
- [ ] 7.3 `components/workspace/task-editor-pane.tsx`（50 处：表单 label / placeholder / toast）
- [ ] 7.4 `components/workspace/views/workflow-canvas-view.tsx`（48 处：toast / 节点右键菜单）
- [ ] 7.5 `components/workspace/views/settings-view.tsx`（47 处）+ `components/settings-sheet.tsx`
- [ ] 7.6 `components/ops/instance-table.tsx`（35 处：状态徽章映射 + toast）
- [ ] 7.7 `components/workspace/views/metrics-view.tsx`（28 处）+ `cockpit-view.tsx`（23 处）
- [ ] 7.8 `task-def-list.tsx`（19）/ `freshness-view.tsx`（16）/ `diagnosis-card.tsx`（8）
- [ ] 7.9 `components/agent/approval-card.tsx` + `components/cockpit/fix-actions.tsx`（后端 message 渲染点）
- [ ] 7.10 其余：`log-panel` / `instance-log-view` / `task-search-bar` / `registry` / `login` / `agent-rail` / `tab-strip`
- [ ] 7.11 处理 ~12 处插值文案（ICU 参数化：catalog-tree 重命名/删除三元、approval verb、view-status、log-panel 节点拼接等）
- [ ] 7.12 下线 ~20 处 `toast.error(j.message || "中文兜底")` → `toast.error(j.message)`（后端已本地化）
- [ ] 7.13 typecheck 验证

## 8. 前端设置面板语言区

- [ ] 8.1 settings 面板「主题」区下方新增「语言」区
- [ ] 8.2 「界面语言」选择（中文 / English）→ 写 cookie `NEXT_LOCALE`
- [ ] 8.3 「Agent 语言」选择（跟随界面 / 中文 / English）→ 写 cookie `DW_AGENT_LOCALE`
- [ ] 8.4 切换实时生效（界面语言切换即时重渲染；刷新后 cookie 保持）
- [ ] 8.5 Browser Verification：切 locale 实跑，CopilotChat 渲染正常

## 9. 前端日期 / 数字 locale 化

- [ ] 9.1 `lib/types.ts:formatDateTime()` → `Intl.DateTimeFormat(locale, {...})`（单点覆盖 5 处复用）
- [ ] 9.2 `components/ops/log-viewer-panel.tsx:75` `toLocaleString()` 显式传 locale + 修 `bytes` 单位硬编码英文
- [ ] 9.3 typecheck + 视觉验证（zh / en 日期格式差异）

## 10. 英文 bundle 产出

- [ ] 10.1 前端 `messages/en-US.json` 完整产出（key 与 zh-CN 对齐），全量标注「待母语校」
- [ ] 10.2 后端 `messages_en_US.properties` 完整产出
- [ ] 10.3 数据中台术语保留英文原词（cron / DAG / 血缘→lineage / SLA / OOM 等）
- [ ] 10.4 缺失 key fallback 到 zh-CN 行为验证

## 11. 测试对齐

- [ ] 11.1 后端 ~50 处中文断言（20 个测试文件）对齐 zh_CN bundle（默认 locale）
- [ ] 11.2 后端 en_US bundle 断言增补（至少覆盖 `IntentRouter` / `ApprovalService` / `WorkflowService` / `CatalogTreeService`）
- [ ] 11.3 `IntentRouterIntentTest` 增补英文意图用例（why failed / count / cron 等英文关键词命中）
- [ ] 11.4 前端 Browser Verification Gate：zh / en 双 locale 实跑（CopilotChat 渲染输入框、console 无 error、消息能发能收流式回复）
- [ ] 11.5 `cd backend && ./mvnw test` + `cd frontend && pnpm typecheck && pnpm build` 全绿

## 12. 规范与收尾

- [ ] 12.1 产出错误码集中清单（code → zh / en 对照表，单独 md 或 design 附件）
- [ ] 12.2 i18n key 命名规范 + 文案归属三规则补入 `CLAUDE.md` Key Conventions（约定层）
- [ ] 12.3 可选：lint 脚本 grep 残留中文 JSX 硬编码（CI 兜底）
- [ ] 12.4 `data.sql` 种子数据加注释标注「i18n 豁免，业务数据保留中文」
- [ ] 12.5 更新 `docs/architecture.md` 增加 i18n 段落（架构真相源）
- [ ] 12.6 `openspec validate i18n-internationalization` 通过
