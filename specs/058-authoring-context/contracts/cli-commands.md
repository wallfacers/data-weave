# Contract: CLI 子命令（dw）

薄封装 REST；`--json` 输出供 AI agent 读。工作副本草稿由 CLI 就地收集（当前目录/指定路径），随请求发出。凭据/端点复用既有 `DW_API`/`DW_TOKEN`。

## dw context <task|path> [--depth N] [--json]

装配创作上下文包（读写表→上下游 + 列血缘 + 数据源 schema）。
- `<task>` 为已 push 任务名 → `GET /{taskDefId}`；`<path>` 为本地草稿/目录 → 收集工作副本草稿 → `POST /analyze`。
- `--depth` 透传遍历深度（缺省默认多跳）。
- 退出码：0 成功（含 `partial`/`truncated` 仍算成功）；非 0 仅用于网络/鉴权/参数错误。

## dw deps <task> [--json]

返回任务依赖视图（声明 DAG + 推导血缘合并，带 origin）。

## dw reuse <task|path> [--json]（P2）

返回写表目标重叠的复用候选（按确定性分排序）；无重叠输出空数组。

## dw check <task|path> [--json]（P3）

返回一致性诊断（悬空上游/列契约破坏/重复定义/依赖背离）。**建议性**——命令退出码不因诊断非空而非 0（不阻断），诊断内容在输出体里。

## 输出契约（--json）

顶层与 REST `data` 同构：`{context?,deps?,reuse?,diagnostics?}`。人读模式（无 `--json`）打印精简摘要 + 关键条目。

## main.go 分发

新增 `case "context" / "deps" / "reuse" / "check"`；实现在 `cli/context/`，复用 `cli/client` HTTP。
