# Phase 0 现状勘察 / 决策记录（014）

四项松散端均已实地勘察（grep/ls/git 核实），两项决策性裁定由项目主拍板。无 NEEDS CLARIFICATION 残留。

## D1：Agent 举手台（ops alert rail）处置

- **Decision**：**移除**（删除全部前端代码 + 引用 + mock 注入器）。
- **勘察证据**：
  - `frontend/lib/workspace/ops-alerts-store.ts` 自身注释写明："当前告警来源为 mock 注入（ops-view `useMockAlertInjector` 经 `window.__MOCK_OPS_ALERT__`）。真实告警推送链路属服务端 AI 时代概念，Weft 掉头后…失效"。
  - store 唯一写入路径 = `ops-view.tsx` 的 mock 注入（`useOpsAlertsStore.getState().push({...})`），由 `window.__MOCK_OPS_ALERT__` 触发；正常运行态永远为空。
  - 后端无任何真告警源端点（grep `alert` 仅命中 api 主类，属偶然匹配）。
  - 触点文件：`ops-alert-card.tsx`、`ops-alerts-store.ts`、`ops-view.tsx`（右栏 + mock 注入器）、`side-panel.tsx`/`backfill-dialog.tsx`/`workflow-canvas-view.tsx`/`app/layout.tsx`（grep 命中，须逐一核查是否真引用举手台符号，真引用则清理、偶然同词则不动）。
- **Rationale**：服务端-AI 时代残肢，Constitution Principle IV 要求"无 active code / 依赖残留"，移除是正向收口；孤立死肉，移除零功能风险。
- **Alternatives considered**：① 改接 PolicyEngine PENDING_APPROVAL 真审批队列（工作量大、属新能力，超本特性"清理"范围）；② 原样保留（持续误导"看着像有告警实则没有"）。均被否。

## D2：push 路径表级血缘缺口处置

- **Decision**：**defer 到发布期 neo4j 图库重做 + 本期文档化裁定**（不补 JDBC 过渡实现）。
- **勘察证据**：现 JDBC 二部图血缘仅设计时 `createAndOnline` 路径建立（`recordDesignTimeIo`）；`ProjectSyncService.push` 落库不调血缘建立。血缘体系发布期改 neo4j 的方向此前已确立。
- **Rationale**：现补 JDBC 过渡实现会在 neo4j 重做时被推倒重写，净浪费；把缺口明确文档化即闭合"功能不闭环"的认知风险（让缺口可见、可追溯、有定论），符合最小必要。
- **Alternatives considered**：现期补 JDBC `recordDesignTimeIo` on push（被否：注定推倒重写）。

## F3：dataweave-alert 死目录

- **Decision**：删除 `backend/dataweave-alert/`。
- **勘察证据**：`git ls-files backend/dataweave-alert/` = 0；`git check-ignore` 确认 `target/` 被 gitignore；目录仅含 `target/`（编译 class + jar + maven-status），无 `src/`、无 `pom.xml` 的有效 artifactId；根 `backend/pom.xml` `<modules>` 仅 master/worker/api，不含 alert。
- **Rationale**：服务端-AI 时代 alert 模块删源后未清的磁盘残骸，纯磁盘操作（不产生版本库变更，因其本就未被跟踪），消除"幽灵模块"认知误导。
- **Alternatives considered**：保留（持续误导）；重建 alert 模块（超范围、无需求）。均被否。

## F4：ops 硬编码中文 i18n 漏迁

- **Decision**：完整 grep sweep + 迁入 next-intl 双语言 bundle（ops 命名空间，ICU 动态插值，键平价）。
- **勘察证据（已定位 5 处）**：
  - `workflow-instances-panel.tsx`：`最多选中 100 个实例`、`批量重跑`、`批量置成功`、`批量停止`（4 处）。
  - `periodic-instances-panel.tsx`：`最多选中 100 个实例（当前 {ids.length} 个）`（1 处，含动态数量 → ICU `{count}`）。
  - 这些面板其余文案已走 `t()`，属 origin 合并遗留漏迁。
- **Rationale**：i18n 三规则①——静态 UI 文案归 next-intl，按 UI locale；中英混杂是用户可见破绽。
- **关键执行注意**：① 不止改这 5 处——须对 ops 区（`components/workspace/views/ops/` + `ops-view.tsx`）做全量 sweep，覆盖 `title`/`aria-label`/`placeholder` 等非正文文本属性，防同类漏网；② 数据术语（cron/DAG/SLA/lineage/OOM）保持英文不译；③ 两 bundle 同键集（CI 键平价校验）；④ 动态数量用 ICU `{count}` 而非字符串拼接，英文语序自然。
- **Alternatives considered**：仅改已知 5 处（被否：易漏同类）；新建独立 i18n 基础设施（被否：沿用既有 ops 命名空间即可）。
