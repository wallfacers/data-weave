# Contract: 推理 sidecar `/extract`（serve/app.py，含 dir_fix 改造）

后端 `ModelExtractor.java` 调 `POST http://<sidecar>/extract`，2s 超时即弃（沿用）。本特性在既有契约上**新增 dir_fix 后处理**，请求/响应外形保持兼容——后端零改动。

## 请求

```json
POST /extract
{ "task_type": "PYTHON", "content": "<脚本全文>" }
```

## 响应（兼容既有 + 方向已修正）

```json
{
  "reads":  [{"table": "ods.orders", "columns": null}],
  "writes": [{"table": "dwd.orders_clean", "columns": null}],
  "model_version": "wallfacers/weft-lineage-extractor-3b@v3",
  "dir_fixed": true
}
```

| 字段 | 约束 |
|---|---|
| `reads`/`writes` | 表集由**模型**定；方向经 sidecar 内 sqlglot **dir_fix** 校正（非 override） |
| `model_version` | 反映当前 `MODEL_DIR` 加载的权重（swap 后更新） |
| `dir_fixed` | 该次是否发生 AST 方向修正（观测用；无可解析 SQL 时 false） |

## 行为契约（单测 `test_dir_fix_serve.py` + 集成覆盖）

1. **确定性解码**：`do_sample=False`，同版本模型同输入必同输出（可缓存/可复现）。
2. **dir_fix 策略**：表集取模型输出；对 content 内 sqlglot 可识别的表，方向以 AST 为准覆盖模型方向；模型有、AST 无的表保留模型方向；**不**用 SQL 整条 override 表集。
3. **健壮性（移植 050 补丁）**：畸形/超大片段（远处分号大块、Jinja `{%- %}`/`$CONDITIONS` 模板）MUST NOT 触发 sqlglot 灾难性回溯/爆内存——片段窗封顶 800、跳模板标记、`update\s+\w+\s+set` 排除 MERGE 内裸 UPDATE、逐片段 SIGALRM 限时兜底；超时片段跳过不阻塞。
4. **弃权**：无字面表 → `{"reads": [], "writes": []}`（诚实空）。
5. **纯自托管**：处理路径 MUST NOT 调用任何外部大模型 API（SC-007）。
6. **预算**：单请求应在 MODEL 通道预算（默认 2s，可调优后固定）内返回；超时由后端弃用该通道结果（既有行为）。

## 与后端接缝

- Java 侧 `ModelExtractor` / `ScriptLineageService` 三通道路由**不变**；仅运维切换 sidecar 的 `MODEL_DIR`→蒸馏 3B。
- 血缘写入沿用 `ScriptLineageCorrectionGate`（写门审计不旁路）。
