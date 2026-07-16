# Phase 1 Data Model: 管家会话列表模式 + Markdown 回复

本特性无新增/变更后端实体或 DB schema。以下为**前端状态模型**（zustand `useCompanionStore` 扩展）与既有类型的复用关系。

## 复用的既有类型（`lib/companion/types.ts`，无改）

- **MessageView**: `{ id, reportId?, role: "USER"|"AGENT"|"SYSTEM", actorName, content, createdAt }` —— 会话线程一条消息。`content` 为 Markdown 文本；`reportId` 存在表示该消息锚定到某问题。
- **ReportView**: `{ id, domain, severity, title, summary, detail?, aggregateCount, status, closedBy?, createdAt }` —— 问题列表一行的数据源。
- **Briefing** / **CompanionState** / **ReportSeverity** / **PatrolDomain** —— 顶部概况与状态色，沿用。

## Store 扩展（`lib/companion/store.ts`）

### 新增/变更字段与动作

| 成员 | 类型 | 说明 | 变更 |
|---|---|---|---|
| `messages` | `MessageView[]` | 会话线程消息（全局 + 各问题），时间正序 | 复用 |
| `setMessages(list)` | `(MessageView[]) => void` | **新增**：整表写入并按 `id` 去重（历史加载用）| 新增 |
| `addMessage(m)` | `(MessageView) => void` | **改为幂等**：已存在 `id` 则覆盖，不存在则按 `createdAt` 有序插入（SSE 实时用）| 改 |
| `appendDelta(id, chunk)` | 既有 | 流式追加；placeholder 以真实 messageId 建，与历史/message 天然对齐 | 复用 |
| `endMessage(id, interrupted)` | 既有 | 流式结束标记 | 复用 |
| `anchorReportId` | `string \| null` | **新增**：当前锚定问题；`null`=全局对话 | 新增 |
| `setAnchor(reportId \| null)` | `(string\|null) => void` | **新增**：锚定/取消锚定/切换 | 新增 |
| `reports` / `addReport` / `removeReport` / `setReports` | 既有 | 问题列表数据源（项目级共享关闭）| 复用 |

### 不变量

- **消息主键唯一**：`messages` 内 `id` 唯一；`setMessages` 与 `addMessage` 均以 `id` 去重（合并 SSE 实时 + `GET /messages` 历史，重连/刷新不产生重复）。
- **时间正序**：线程渲染按 `createdAt` 升序（历史在前、最新在尾）；合并后排序稳定。
- **锚定回落**：`removeReport(id)` 若 `id === anchorReportId` → `setAnchor(null)`（当前锚定问题被他人关闭时回落全局）。
- **锚定驱动发送**：`sendChat` 携带 `reportId = anchorReportId ?? undefined`。

## 派生视图模型（组件内 useMemo，不入 store）

- **ThreadItem**（`conversation-thread.tsx`）：由 `messages` 时间正序映射；每条按 `role` 决定头像/对齐/样式，`content` 交 `ChatMarkdown`（流式中传 `streaming`）。
- **ProblemRow**（`problem-row.tsx`）：由单个 `ReportView` 映射为一行；`title || domainName`、`aggregateCount>1` 显 `×N`、severity→色点、`createdAt`→`useFormatDateTime`。
- **AnchorHeader**（`conversation-thread.tsx`）：`anchorReportId` 非空时由 `reports.find(id)` 取标题渲染锚定条 + 取消锚定；问题已不在 reports（被关闭）→ 提示「该问题已处置」并回落。

## 历史分页游标（可选，US1 边界）

- `fetchMessages({ before, limit })` 的 `before` 为游标（既有端点参数）；线程上滚到顶触发加载更早消息，`setMessages` 合并去重。默认首屏一次 `limit` 拉取即可满足 SC-001/SC-003，分页为超长历史的增强。
