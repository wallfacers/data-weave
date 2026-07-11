# 召回回收 · 置信度分层复核信封接进 serving — 设计

**日期**：2026-07-11 · **承接**：059（北极星达成，Run C ALL-p 0.684 / 召回 0.703；③ 语义 grounding 已接 serving）
**worktree**：`dw-059-lineage-corpus-expansion` · **改动范围**：仅 `ml/lineage-extractor/`（不碰 backend/frontend）

## 背景与目标

059 收口后自托管 3B 血缘抽取精度已超两个前沿 teacher（ALL-p 0.684 > deepseek-pro 0.593 > qwen-max 0.413）。**唯一实质弱项是召回**：3B 语义 grounding 后召回 0.703，teacher 带 0.77–0.81。召回缺口是我们换精度付出的代价（Run C 训得保守）。

本方向 = **召回回收**，约束「无需新 GPU / 无付费 teacher / 无人工标注」。

### 可达性（探针实测，先定界，防目标虚设）

gold C（61 非空脚本 / 148 真表，canon 口径）：

| 召回来源 | 召回 | 说明 |
|---|---|---|
| 模型（语义 grounding 后） | 0.703 | 当前部署管线 |
| SQL-AST 通道单独 | 0.196 | 只在 21/61 脚本开火 |
| **模型 ∪ SQL 并集（免费天花板）** | **0.764** | SQL 只补回 +9 个模型漏抽的真表 |
| teacher（deepseek/qwen） | 0.77–0.81 | 动态名/框架/配置驱动表，免费通道够不着 |

**硬结论**：免费确定性手段的召回天花板 ≈ 0.764，落在 teacher 带下沿甚至以下。teacher 的额外召回来自动态名/框架调用/配置驱动的表——模型被训保守、SQL-AST 解析器也看不见，**不付费/不重训够不着 0.81**。且裸并集会赔精度（flat 精度掉到 0.685，SQL 裸名/CTE 碎片污染）。

**故本方向可兑现的是**：把召回从 0.703 往 0.764 天花板推（+6pt），**同时用置信分层守住自动入库精度**，产出为**分层复核信封**——这正是 052/054 那套置信度校准（`confidence_calibration.py` / `tiered_envelope.py`）从未接进 059 serving 的东西。追平 teacher 0.81 明确列为非目标并诚实定界。

## gold C 真实校准（148 真表，样本内，须 CV 去偏后固化）

| 置信级 | n | 经验 precision |
|---|---|---|
| sql_qual（SQL·限定名） | 7 | 1.000 |
| model_bare（模型·裸名） | 44 | 0.909 |
| agree（SQL∩模型） | 23 | 0.870 |
| model_qual（模型·限定名） | 54 | 0.815 |
| sql_bare（SQL·裸名） | 11 | 0.182 |

累计自动采纳前沿（校准序 sql_qual > model_bare > agree > model_qual > sql_bare）：

| 精度门 | 采纳至 | 自动层 precision | 自动层召回 | 复核负载 |
|---|---|---|---|---|
| **≥0.95** | sql_qual | 1.000 | 0.047 | 2.16 候选/脚本 |
| ≥0.90 | +agree | 0.905 | 0.453 | 1.07 候选/脚本 |
| ≥0.684 | 全并集 | 0.813 | 0.764 | 0 |

**⚠️ 治理严格 ≥0.95 的代价**：自动采纳层只有 7 个表、召回 0.047（几乎全进复核），且 7 样本的 P=1.0 统计上很抖（054 CV 已记「0.95 边界 held-out 抖到 0.930/0.291」）。**≥0.90 是统计稳定的膝点**（agree 层，召回 0.453，复核 1.07/脚本）。

**决定**：自动采纳阈做成 **env 可配**，默认 0.95（遵治理严格），文档标注 0.90 为统计稳定膝点。复核层无论阈多少都 surface 全并集（召回 0.764）。含义：默认下现有 `reads`/`writes` 消费者的自动入库召回从 0.703 降到自动层水平（治理严格代价，如实披露）。

## 设计

### 架构

纯确定性后处理扩展，`serve/app.py` 的 postprocess 链再加一环，GPU 只跑模型（不变），新增全 CPU：

```
模型 greedy 解码
  → 语义 grounding（剔非表 FP，059 ③，不变）
  → dir_fix（AST 修方向，不变）
  → 【新】置信分层：model 表集 ∪ SQL-AST 通道表集
       → 每候选边打 tier（sql_qual/model_bare/agree/model_qual/sql_bare）
       → 校准 precision ≥ 门（默认 0.95） → 自动采纳层
       → 其余 → 复核候选层（按校准 confidence 降序）
```

### 组件（全在 `ml/lineage-extractor/`）

1. **`realeval/tier_classify.py`（新，纯函数、无 torch）**
   核心 `classify_tiers(model_pred: dict, content: str, thr: float) -> dict`，返回 `{auto:{reads,writes}, review:{reads,writes}}`，每项含 `table/columns/tier/confidence`。
   - 复用 `channel_router.extract_sql_lineage`（SQL-AST 通道，exec_gated=True，CPU/确定性）取 SQL 表集 S；
   - 复用 `confidence_calibration._canonical_edges(S, M)` 把模型表集 M 与 S 在 canon 下合并成互斥候选边并打 tier（避免 `t` 与 `db.t` 重复计数）；
   - 方向：SQL 通道 write 由 AST target 锚定（沿用 dir_fix 已产出的方向），model 通道方向沿用模型/AST 修正后结果；
   - 校准 precision 表 = **离线固化常量**（下述组件 3 产出），据 `thr` 切分自动/复核；`confidence` = 该 tier 的固化 held-out precision。

2. **`serve/app.py` 扩展**
   - postprocess 末端调 `classify_tiers`；
   - `TableIo` 加 `tier: str`、`confidence: float`；
   - `ExtractResponse` 加 `reviewReads`、`reviewWrites`、`tiered: bool`；`reads`/`writes` = 自动采纳层；
   - env `LINEAGE_AUTOACCEPT_MIN_PRECISION`（默认 `"0.95"`）→ `thr`；分层默认开，可回滚（阈设 0 → 全并集进 `reads`/`writes`，`reviewReads/writes` 空，等价旧行为的并集口径；另留 `LINEAGE_TIERING=0` 完全关分层退回纯模型输出）。

3. **`realeval/calibrate_tiers.py`（新）**
   gold C 上跑 `confidence_calibration.calibrate` + `conf_calibration_cv.py`（留一/k折去偏，054 已有）→ 产出 held-out 稳健的每级 precision 常量表 + 报告 `out/calibrate-tiers.md`，把常量写进 `tier_classify.py`。**保证 serving 用的阈来自 held-out 校准，非样本内乐观值。**

### 契约（两显式列表）

```python
class TableIo:
    table: str
    columns: list[str] | None = None
    tier: str = ""            # sql_qual/model_bare/agree/model_qual/sql_bare
    confidence: float = 0.0   # 该 tier 的固化 held-out precision

class ExtractResponse:
    modelVersion: str
    reads: list[TableIo]          # 自动采纳层（≥门，治理安全，可直接入库）
    writes: list[TableIo]
    reviewReads: list[TableIo]    # 复核候选层（并集剩余，进人工队列，按 confidence 降序）
    reviewWrites: list[TableIo]
    dirFixed: bool = False
    grounded: bool = False
    tiered: bool = False          # 是否发生分层（有 ≥1 表被分到复核层）
```

向后兼容：旧字段 `reads/writes/dirFixed/grounded` 全保留；新字段有默认值。

### 数据流验证（离线）

`realeval/rescore_tiered.py`：gold C 上量
- 自动层 precision（须 ≥ 门）、召回；
- 复核层召回（自动 ∪ 复核 应逼近并集 0.764）；
- 复核负载（候选/脚本）；
- 三方（3B / qwen-max / deepseek-pro）同施对照。
报告 `out/rescore-tiered.md`。

## 成功判据（诚实报告）

- **主判据**：复核层把并集召回 **0.764** surface 给人工（vs 模型独抽 0.703，召回回收 **+6pt** 进复核队列），且自动层 **held-out（CV）precision ≥ 门**。
- **自动层**：默认 ≥0.95 下 precision 达标；若召回过低（0.047）**记为治理严格代价如实披露**，并附 0.90 稳定膝点数据（召回 0.453）供选。
- **不回归**：全量 ml 套件绿；旧响应字段语义可由 env 完全回滚。
- **诚实边界**：不为达标放松 CV 护栏、不谎报自动层 precision；样本小（148 表），CV 去偏后仍披露边界抖动。

## 非目标（YAGNI）

- 不重训模型；不接付费 teacher 做召回；不追 teacher 的 0.81（免费手段够不着，已定界）。
- 不碰 backend `ModelExtractor` / frontend——**serving 契约扩展了分层产出，但平台侧消费分层（复核队列 UI、自动层入库）是下一个特性**，本轮只做 serving 侧产出 + 离线证明。
- 不做 AST/sqlglot 重解析新逻辑（复用 channel_router 既有健壮性补丁）。

## 测试与验证

- `tests/test_tier_classify.py`：真实夹具——sql_qual 进自动、sql_bare 进复核、model-only 漏抽经 SQL 通道补回进复核、阈可调、canon 去重边界、空输入。
- `tests/test_dir_fix_serve.py` 扩：分层响应结构、`reads/writes`=自动层、`reviewReads/writes` 非空、env 阈生效、`LINEAGE_TIERING=0` 回滚、向后兼容（旧字段仍在且语义不变）。
- `cd ml/lineage-extractor && PYTHONPATH=. python3 -m pytest -q` 全量绿，无回归。
- harness 真跑三方，报告落盘，数据可复核。
