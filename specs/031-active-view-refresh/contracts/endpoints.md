# Contracts: 复用的只读端点（零新增 · 零签名改动）

本特性**不新增、不修改任何后端 API**。自动/手动刷新只是对各统计视图**已有的只读 GET 端点**按节拍重新发起请求。列此清单仅为明确「刷新打的是哪些端点」，并约束：刷新调用方 MUST 复用现有端点与既有响应解包（`ApiResponse<T>`，`code===0`），不得改变其签名、分页或语义。

| 视图 | 端点 | 取数方式 | 备注 |
|---|---|---|---|
| metrics | `GET /api/ops/metrics` | `useApi`→`useLiveData` | 单次快照 |
| reports | `GET /api/metrics` | `useApi`→`useLiveData` | 指标卡列表 |
| freshness | `GET /api/freshness?{page,size,sort,filters}` | `DataTable` server fetcher + `reloadSignal` | 保持分页/筛选原地重取 |
| ops（大盘） | `GET /api/ops/summary`、`GET /api/ops/eta-summary` | `useApi`→`useLiveData` | TopStrip 两端点 |
| ops（周期实例） | 周期实例分页端点（DataTable fetcher 现有） | `DataTable` + `reloadSignal` | 原地重取 |
| ops（流实例） | 流实例分页端点（DataTable fetcher 现有） | `DataTable` + `reloadSignal` | 原地重取 |
| quality | quality 现有 GET（`authFetch` 现状） | 自定义 fetch 接 `useLiveData` 模式 | 保留旧数据 |
| alerts | 现有 4 个 GET（`Promise.all`） | 自定义并行 fetch 接 hook 模式 | 任一失败保留旧数据 |

**不纳入**（明确排除，行为不变）：`instance-log`（已有 SSE 实时流）、workflow-canvas/编辑器、settings、各类详情面板、占位视图。

**约束复述**：
- 仅 GET / 只读；不产生任何写动作 → 无需经 PolicyEngine 闸门。
- 复用现有鉴权（Bearer token）与 401 处理（`handleUnauthorized`）。
- 若后续某页发现需要更廉价的「轻量快照端点」以降低刷新成本——属**后续增强**，不在本特性范围（spec Assumptions 已注明）。
