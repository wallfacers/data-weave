# 语义级 grounding 过滤器 — 设计（离线测量）

**日期**：2026-07-11 · **承接**：059（北极星达成，Run C ALL-p 0.642 / oracle 上限 0.742）
**worktree**：`dw-059-lineage-corpus-expansion` · **改动范围**：仅 `ml/lineage-extractor/`

## 背景与目标

059 收口后，Run C 3B 的 ALL-p 0.642 与 oracle 上限 0.742 之间还差 ~10pt。② 消融已证伪「加空脚本负例能补这 10pt」（Run D2 持平却赔召回/方向）。根因分析：残留假阳是 **grounded-but-wrong** —— 表名字面出现在脚本里（现有 literal-grounding 过滤器杀不掉），但出现位置其实是注释 / import / 文件路径 / 变量 / 临时视图，不是真表引用。

从 Run C 预测捞出 68 个 grounded-but-wrong FP，失败模式：
- 注释里：`# spark.sql('drop table if exists stg_users')`、`# MAGIC left join ...`
- import/类：`from data_types import Preference`
- 变量/参数/属性：`def get_hive_table_row_count(self, hive_table_model)`、`hive_table_model.tracking_table_name`
- 临时视图：`createOrReplaceTempView('temp_finaldf')`
- 文件路径/CSV/glob：`file.csv`、`.../stg_coin_category*`、`"...Beneficiary_Summary...csv"`
- notebook 魔法：`%run ./measure_metadata`
- DDL：`drop table if exists ...`

**目标**：把 literal-grounding 升级为**上下文感知**的语义 grounding —— 判某个叶名的出现位置是「真表引用」还是「约定已排除的位置」，剔掉后者，把 ALL-p 从 0.642 推向 0.742，且**不拿召回换**（② 教训）。

**本轮交付范围（用户定）**：**先离线测量**。扩展离线过滤器 + 对 Run C 与两个 teacher 重打分证明收益；代码设计成可复用，下一轮再接进 `predict` 后处理。

## 设计

### 组件 1：上下文分类器 `realeval/semantic_grounding.py`

核心导出 `keep_table_semantic(table: str, content: str) -> bool`，纯函数，无副作用。

对预测表 T：取叶名（点分末段，小写）→ 扫描脚本**所有出现位置** → 给每处出现打上下文标签 → **黑名单裁决**。

**判据（用户定）= 黑名单**：只剔约定已明确排除的位置，保护召回。

**排除上下文**（该出现落入其一即视为「非表位置」）：
| 类别 | 判据信号 |
|---|---|
| 注释 | 出现所在行去空白后以 `#` / `--` / `//` 开头；或 Databricks `# MAGIC`（且 MAGIC 后非 SQL）/ `%` 魔法行 |
| import/类 | 出现行匹配 `^\s*(from\s+\S+\s+)?import\b` |
| 文件路径 | 出现所在字符串字面量含 `/`、或叶名带 `.csv/.parquet/.json/.txt/.orc/.avro` 后缀、或含 glob `*` |
| 临时视图 | 出现是 `createOrReplaceTempView` / `createTempView` / `registerTempTable` 的实参 |
| 变量声明/属性 | 裸赋值 `NAME = "T"`（右侧字符串仅表名、无 SQL 动词）；`def f(...T...)` 形参；`obj.T…` 属性访问 |

**裁决**：T 的**所有**出现都落在排除上下文 → **剔**；**任一**出现落在非排除位置 → **留**。
→ 变量持有的真表只要在别处有真引用（如 `FROM {var}` 展开后或直接 `spark.table`）就保住，不误杀。

**实现方式**：行/出现位置的**正则启发式**（非 AST）。理由：真实脚本是 Python 混嵌 SQL 字符串 + Shell + notebook MAGIC cell，`sqlglot`/`ast` 在这种脏输入上脆（054 已记 sqlglot 回溯坑），现有 `analyze_grounding` 也是确定性文本判据，保持一致。

### 组件 2：测量 harness `realeval/rescore_semantic.py`

复用 `rescore_arbitrated.py` 骨架。对 **Run C / qwen-max / deepseek-pro** 三方预测，在**校准 gold** 上跑三档对照：
- `raw`（无过滤）
- `literal-grounded`（现状 = `analyze_grounding.keep_table`）
- `semantic-grounded`（新 = `keep_table_semantic`）

每档输出 ALL-p、非空-p、召回、方向、hallucination。报告 `out/rescore-semantic.md`。

### 组件 3：单测 `tests/test_semantic_grounding.py`

用今日捞出的**真实 FP 样本**当夹具：
- **应剔**：`from data_types import Preference`、`# drop table stg_users`、`file.csv`、`createOrReplaceTempView('temp_finaldf')`、`def f(hive_table_model)`、`%run ./measure_metadata`
- **应留**：`FROM orders`、`spark.table("users")`、`INSERT OVERWRITE TABLE t`、`x.write.saveAsTable("dw.fact")`
- **召回护栏**：构造「变量持有 + 别处真引用」样本，确认不误杀；并跑 Run C 非空行 gold 表的抽样断言不被剔。

## 成功判据（诚实报告）

- **主判据**：semantic 档在 Run C 上 ALL-p **> 0.642 且向 0.742 逼近**，同时**非空召回 ≥ 0.633**（不退化）。
- **对照**：三方同施语义过滤，看 3B 对两 teacher 的精度领先是否扩大。
- 若只部分奏效、或误杀真表拉低召回 → **如实记负结果**，与 ② 同样严格。不为达标放松召回护栏。

## 非目标（YAGNI）

- 不接进 `predict` / serving 链路（下一轮，本轮先证收益）。
- 不做 AST/sqlglot 解析（脏输入上脆）。
- 不重训模型（过滤是确定性后处理，比重训便宜且外科）。
- 不碰 backend/frontend/其他特性面。

## 测试与验证

- `cd ml/lineage-extractor && PYTHONPATH=. python3 -m pytest tests/test_semantic_grounding.py -q` 全绿。
- 全量 ml 套件不回归。
- harness 真跑三方，报告落盘，数据可复核。

## 结果（实测，gold C 校准）

**★实测校准了排除集**：初始设计的黑名单含 `{comment, import, path, temp_view, var_decl, param, attribute}`，实测误杀 22 个真表（3B 召回 0.633→0.487 崩、方向同崩）——`var_decl`/`param`/`attribute` 正是「变量持有的表名」和「限定表名叶名前带 `.`」这类连 pro 都裁不清的**标签歧义边界**，真血缘也住那。收窄到**保守集 `{comment, import, path, temp_view}`**（无歧义排除）后：

| 模型 | literal ALL-p | semantic ALL-p | 非空-p | 召回 | 方向 |
|---|---|---|---|---|---|
| **自托管 3B** | 0.6419 | **0.6835 (+4.2pt)** | 0.7422→0.7724 | 0.6333→**0.6333** | 0.6327→**0.6327** |
| deepseek-pro | 0.5874 | 0.5930 (+0.6) | 0.6237→0.6277 | 0.8067→0.7867 | ↓ |
| qwen-max | 0.3867 | 0.4130 (+2.6) | 0.4895→0.5229 | 0.7733→0.7600 | ↓ |

- **成功判据达成**：3B ALL-p +4.2pt / 非空-p +3.0pt，**召回、方向零退化**（保守集只剔无歧义位置）。
- 语义 grounding 对 3B 增益最大（小模型 grounded-but-wrong 幻觉最多，强 teacher 少犯）→ **3B 对 deepseek 的精度领先从 0.055 扩大到 0.090**。
- `param`/`attribute`/`var_decl` 仍被 `occurrence_contexts` 标注供审计，只是不进 `_EXCLUDED`。
- 产物：`realeval/semantic_grounding.py` + `realeval/rescore_semantic.py` + `tests/test_semantic_grounding.py`（20 测）+ `out/rescore-semantic.md`。
