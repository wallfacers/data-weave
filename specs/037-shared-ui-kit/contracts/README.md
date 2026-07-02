# Contracts: 037-shared-ui-kit

本特性**无 HTTP / MCP / SSE 接口**（纯前端设计系统治理，不改后端）。因此此目录不含 OpenAPI/JSON-RPC schema。

这里的"契约"是**面向实现者（开发者 + AI agent）的行为契约**——它们是本特性可验收的接口面：

| 契约文件 | 约束什么 | 对应 FR |
|---|---|---|
| [catalog-entry.schema.md](./catalog-entry.schema.md) | 公共组件目录里每个条目的必备字段结构（新增/回填组件时必须满足） | FR-001 / FR-003 / FR-004 |
| [reuse-first-checklist.md](./reuse-first-checklist.md) | "复用优先"工作流的必过检查项（实现任一界面原语前后自查/评审） | FR-002 / FR-005 / FR-013 |

验收方式：这两个契约由**实现后自查/评审**执行（clarify：文档+评审为主，非自动化 lint）；覆盖度由 [`../adoption-inventory.md`](../adoption-inventory.md) 核对。
