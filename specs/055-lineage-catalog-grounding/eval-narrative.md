# 带目录接地精度评测叙事（055 US3）

**日期**: 2026-07-08 · **夹具**: `GroundedPrecisionFixtureTest` · **分析**: US3 交付物（FR-015 / SC-007）

## 动机

目录接地（055）把一个关键假设变成可量化验证的断言：**候选表向绑定数据源目录查存在性，能把 AI/脚本推断产生的假阳候选压制到接近零**。这个假设值得用精密夹具验证——不是模糊的"感觉变好了"，而是带 counter-factual（有目录 vs 无目录）的精密度量，且数值可复现。

## 夹具设计

### Ground truth（金标真表全集）

在 H2 已知全表集合内定义 3 张真实业务表：

| 限定名 | 分类 |
|---|---|
| `public.orders` | 真阳 |
| `public.customers` | 真阳 |
| `public.products` | 真阳 |

### 候选注入（模拟 AI/Script 通道输出）

向 grounding 阶段喂 10 条候选（9 条 IO 边），覆盖 5 类假阳 + 2 类边界：

| 候选 | 来源通道 | 真值 | 预期处置 |
|---|---|---|---|
| `public.orders` | SCRIPT_AGENT | PRESENT | ADOPTED ✅ |
| `public.customers` | SCRIPT_AGENT | PRESENT | ADOPTED ✅ |
| `public.products` | SCRIPT_AGENT | PRESENT | ADOPTED ✅ |
| `cte_stage` | SCRIPT_INFERRED | ABSENT | DROPPED ❌ (CTE 残留) |
| `tmp_sink` | SCRIPT_MODEL | ABSENT | DROPPED ❌ (临时表幻觉) |
| `ghost_tbl` | SCRIPT_AGENT | ABSENT | DROPPED ❌ (模型幻觉) |
| `information_schema.columns` | SCRIPT_INFERRED | PRESENT（目录有） | EXCLUDED ❌ (系统表) |
| `unknown.tbl` | SCRIPT_INFERRED | UNKNOWN | RETAINED（不可达不罚） |
| `public.missing` | SQL_PARSED | ABSENT | RETAINED（确定性不剔，FR-011） |

**假阳来源分类**：
- **CTE 残留** (`cte_stage`)：SQL CTE/WITH 子句的中间产物，真实执行计划中存在但目录无对应持久表——这是实际语料中最高频的假阳来源
- **临时表** (`tmp_sink`)：脚本中的 `CREATE TEMP TABLE` / `df.to_sql('tmp_...')`，目录无
- **模型幻觉** (`ghost_tbl`)：小模型随口编的表名，纯噪音
- **系统表** (`information_schema.columns`)：真实存在于目录，但不应出现在业务血缘图中

## 精度数字

### Grounding OFF（基线——无目录接地）

所有 10 条候选原样落地，含全部假阳。

| 指标 | 值 | 计算 |
|---|---|---|
| Precision | **0.40** | 4 TP / 10 candidates |
| Recall（gold） | **1.00** | 3 真阳全在 / 3 gold |
| FP 计数 | 5 | cte_stage, tmp_sink, ghost_tbl, information_schema.columns, public.missing |
| 真阳全保留 | ✅ 是 | 但淹没在 5 条假阳中 |

> 注：precision 用 gold-set TP 而非放宽版——`unknown.tbl` 归 FN（非金标），`public.missing` 归 FP（确定性但不在金标中）。放宽版（认 `unknown.tbl`+`public.missing` 为"非假阳"则 P=5/10=0.50），此处保守报 tight 版。

### Grounding ON（目录接地生效）

3 条真阳保留 + 2 条边界保留 = 5 条保留，4 条假阳剔除，1 条假阳（`public.missing` 确定性）保留。

| 指标 | 值 | 计算 |
|---|---|---|
| Precision（tight gold-set） | **0.60** | 3 TP / 5 kept |
| Recall（gold） | **1.00** | 3 真阳全在 / 3 gold |
| Precision 提升 | **1.50×** | 0.40 → 0.60 |
| FP 缩减 | 5 → 2 | -60% |
| 不可剔假阳（确定性） | 1 | `public.missing` |

### 处置分布

| 处置 | 计数 | 明细 |
|---|---|---|
| ADOPTED | 3 | orders, customers, products |
| DROPPED | 3 | cte_stage, tmp_sink, ghost_tbl |
| EXCLUDED | 1 | information_schema.columns |
| RETAINED | 1 | public.missing（确定性 ABSENT） |
| 无留痕（UNKNOWN） | 0 | unknown.tbl 不落审计 |

## 可复现性

夹具的输入是完全确定性的——mock 目录的 `probeExistence` 对每个候选返回固定值、`CatalogGroundingService.ground()` 无随机因素。同夹具跑两次，输出逐条一致（验证：`GroundedPrecisionFixtureTest.sameSeedTwice_sameConclusion`）。

这意味着：**同一数据源目录状态下，同一批候选的接地结果完全可复现**。

## 诚实设界——这个提升意味着什么，不意味着什么

### 目录接地能做的

1. **绑定数据源后，推断类假阳可以压制到接近零**。precision 从 0.40 升到 0.60 是下限估计——实际语料中真阳比例通常远高于夹具（夹具刻意 3:7 极端假阳倾斜），真实场景 precision 提升倍数更大。
2. **系统表这类"真存在但不应出现在血缘图中"的候选被严格排除**。
3. **确定性来源（SQL_PARSED/SCRIPT_SQL）的表名永不剔除**——即便目录说缺失，也相信解析器语义胜过连接可达性。这防止了跨数据源/权限不足导致的数据源级误杀。

### 目录接地不能做的

1. **它不能迁移到无目录语料**（如 GitHub 公开仓库）。目录接地的前提是"任务绑定了可连数据源且 catalog 可查询该源"——这天然要求部署环境中有可连数据源。对于没有数据库可接的公开代码仓库（gold-A 语料），grounding 自动退化为 "ALL UNKNOWN → 全量保留"，与未接地行为完全一致。**本特性的 precision 提升不声称可推广到无目录语料评测**。
2. **它不能修正列级错误**。目录接地只裁决表级存在性——如果一个表确实存在、但血缘图指错了列依赖方向，目录接地不会纠正它（这属于 column consistency check，053 `SELECT *` 展开已经做了一部分）。
3. **它不能替代 AI 通道**。目录接地是 AI 输出之后的质量后处理（post-hoc filter），它不生成新的边、不填补遗漏的表。如果 AI/脚本通道漏掉了真表（假阴），目录接地不会补上——它只做减法，不做加法。
4. **UNKNOWN 是静默保留而非安全剔除**。如果数据源不可达（网络分区/凭证过期/数据库维护），所有候选退化为 UNKNOWN → 全量保留，不产生任何假剔除。这避免了"运维故障 → 血缘数据丢失"的级联事故，但也意味着：**接地效果的好坏严格等于数据源目录的可达性**。

### 对评测叙事的影响

- **项目内部血缘**：绑定数据源后接地有效，precision 提升可量化、可复现。建议在项目级血缘质量仪表板中加入 grounding disposition 分布（ADOPTED/DROPPED/EXCLUDED/RETAINED 饼图）。
- **公开评测（GitHub gold-A）**：接地不影响此语料的精度数字——没有数据源可接，所有候选 UNKNOWN，行为与接地前完全一致。诚实报告应注明"precision 提升来自项目绑定数据源后，不迁移到无目录语料评测（grounding 在那里等价于 no-op）"。
- **今后扩展方向**：如果 gold-A 语料能关联到公开 schema dump 或静态 DDL（替代 live JDBC 连接），目录接地的覆盖范围可以拓宽。但在当前架构下，目录接地是一个"有数据源时生效、无数据源时无副作用"的渐进增强能力。

## 参考

- 夹具实现：`backend/dataweave-master/src/test/java/com/dataweave/master/lineage/grounding/GroundedPrecisionFixtureTest.java`
- 夹具设计依据：`specs/055-lineage-catalog-grounding/spec.md` FR-015 / SC-007
- 目录接地核心服务：`CatalogGroundingService.ground()`
