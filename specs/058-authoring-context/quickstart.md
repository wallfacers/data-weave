# Quickstart: 数据开发 LSP 创作上下文服务

面向「在 CLI 里 vibecoding 数据任务」的开发者与其 AI 编码 agent。展示地基能力（P1）如何让 agent 秒懂意图。

## 场景：新写一个 `dw.user_daily` 加工任务

链路：`ods.user`（源）→ 你正在写的 `T`（写 `dw.user_daily`）→ 下游报表任务 `R`。

### 1. 编辑前取上下文（P1）

```bash
# 对当前工作副本里的草稿分析（未 push 也可）
dw context ./dw_user_daily.sql --json
```
返回（节选）：
```json
{
  "context": {
    "reads":  [{ "table":"ods.user", "neighbors":[{"task":"ods_sync","hop":1}], "groundingState":"PRESENT" }],
    "writes": [{ "table":"dw.user_daily", "neighbors":[{"task":"R","hop":1}], "groundingState":"INFERRED" }],
    "columnLineage": [ ... ],
    "datasourceSchema": { "ods.user": ["id","name","amt","dt"] },
    "depthUsed": 3, "truncated": [], "partial": []
  },
  "deps": { "upstream":[{"peer":"ods_sync","origin":"DERIVED"}],
            "downstream":[{"peer":"R","origin":"DECLARED"}] }
}
```
→ agent 立刻知道：`SELECT *` 展开为 `ods.user` 的真实列、上游是 `ods_sync`、下游 `R` 依赖本表——**无需翻其他任务文件**即可写对。

### 2. （P2）复用检查
```bash
dw reuse ./dw_user_daily.sql --json   # 若已有任务产出重叠目标 → 提示复用，避免重复建设
```

### 3. （P3）改完做一致性检查
```bash
dw check ./dw_user_daily.sql --json
# 例：报出 DEP_DIVERGENCE_MISSING —— 脚本读 ods.user（有数据流）但工作流未声明对 ods_sync 的依赖
```

### 4. 确认无误后走既有黄金路径
```bash
dw diff && dw push && dw run --test    # 创作/提交仍走既有闸门；本特性只做接地与诊断，不参与写
```

## AI agent 集成（Skill）

`weft-task-authoring` Skill 教 agent 回路：**编辑前 `dw context` 取接地事实 → 写 → `dw check` 自检 → `dw diff/push`**。上下文/诊断是确定性事实，喂给 agent 提升意图识别与复用正确率。

## 等价性

`dw context <task>`（CLI）与 MCP `query_authoring_context`（自动化）对同一已 push 任务返回语义一致的结果（同一 `AuthoringContextService`，SC-006）。
