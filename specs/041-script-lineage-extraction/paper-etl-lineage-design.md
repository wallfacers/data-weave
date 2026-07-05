# 设计：041-R 真实 ETL 血缘抽取 · 论文导向重训 v2

> 状态：设计已批准（2026-07-04），待转 writing-plans。
> 前身：041-script-lineage-extraction（已合并 main `c7ea88f`，模型 v1 已过闸）。
> 本轮定位：在方案 A（补训练分布 + 换新 heldout + 重训 v2）之上，一并把「发论文」所需素材做足到不返工。

## 0. 目标与主线

**终极目标**：一篇真实的、ETL 场景的论文。

**主线（main claim）**：*零真实标注、纯合成数据训练的 1.5B 小模型，在真实 ETL 脚本上抽取数据血缘（表/列读写），媲美大模型 few-shot。*

**目标档位**：workshop / 短论文起步（DEEM@SIGMOD、VLDB aiDM、CIDR；对应可行性分析的路径 A）。

**贡献分层**：
- 主贡献：合成→真实迁移 + 小模型性价比（回应审稿两大必杀：无真实评估、无 baseline）。
- 支撑章节：三通道混合架构（SQL 解析 → 规则 → 小模型 fallback，优先级管道）；三源合成数据方法论（HF 标识符收割 + 数仓名池 + 模板注入 + 防泄漏 split）。

**本轮范围（乙·论文导向一次到位）原则**：凡「现在不做、后面就得重训 v3 或重造数据」的，本轮做掉；纯跑数类（baseline 实跑、消融）留论文阶段，但**接口本轮留好**。

## 1. 训练数据系统性扩形态 · `ml/lineage-extractor/data/templates.py`

- **custom-wrapper 类（直接修当前弱点）**：补自定义包装函数模板，让模型学 **动词 → 读写方向** 的一般规律：
  - reads 动词前缀：`load_ / read_ / fetch_ / extract_ / source_ / pull_ / get_`
  - writes 动词前缀：`write_ / save_ / sink_ / persist_ / dump_ / export_ / push_ / upsert_`
  - 函数名与表名充分随机化，避免死记具体 API；覆盖「读表字面量与写表字面量同现于相邻两行」的无标准锚点形态。
- **系统性扩谱系 30 → ~60+ 模板**：
  - 多引擎 CLI：现有 impala/clickhouse/psql + 补 presto/trino/beeline/spark-sql/bq(bq query)/snowsql。
  - ORM / 链式 DataFrame：SQLAlchemy ORM、pandas `read_sql`/`to_sql`、polars、pyspark `saveAsTable`/`writeTo`/`insertInto`。
  - 配置驱动 / 间接：dbt `ref()`/`source()`、Airflow operator 参数式表名。
  - 负样本保留：注释 SQL、仅 print/log 的 SQL、动态拼接表名（应被忽略，维持低幻觉）。
- 训练集规模保持 ~10k（模板多样性提升为主，而非样本量堆叠）。固定 `SEED=20260703` 不变，保证可复现。

## 2. heldout 重构 + 分层 · `templates.py` + `data/synth_pipeline.py`

- custom-wrapper 已并入训练集 → **换新的未见形态**作 heldout 隔离项（ORM 链式 DSL、罕见引擎、装饰器风格 pipeline），继续诚实测量泛化。
- heldout 规模 240 → ~600，**按形态分层**（`meta.template_id` / `meta.split_group` 已有，补 `meta.form_family` 形态族标签），供错误分析章节与分层指标使用。
- 硬约束保持：训练 / heldout 表名池不相交（防名字记忆泄漏）；HF 只取标识符不取语句（label 零噪声）。

## 3. 评估协议 formal 化 · `ml/lineage-extractor/eval/evaluate.py`

- **统一 predictor 接口**：定义 `Predictor.predict(task_type, content) -> {reads, writes}`，任何实现均可插——本模型 sidecar、大模型 baseline、正则基线、规则通道。评估器与被评对象解耦。
- **分层指标**（本轮新增/强化）：
  - 表级 Precision / Recall / F1
  - 字段级正确率
  - **方向准确率（新）**：读/写不混淆率——直接量化 custom-wrapper 弱点，作为新过闸项。
  - 幻觉率（凭空表名占比）、非法输出率。
  - 分层维度：按 `form_family` 形态、按数据源（合成 / 真实）。
- 输出：结构化 JSON（论文表格可直出）+ markdown 报告（沿用 eval-report.md 风格）。

## 3.5 大模型层（教师/评委）· `ml/lineage-extractor/llm/`

两个可用大模型端点（无官方 GPT/Claude key，以下替代）承担**交叉生成、交叉评审、baseline** 三重角色。统一薄封装 `llm/clients.py`，凭据从 gitignore 的 `.env` 读，明文不入 git。

- **M1 = 阿里云 Qwen（DashScope）**：OpenAI 兼容 API，env `DASHSCOPE_API_KEY`、`QWEN_MODEL`（如 `qwen-plus`/`qwen-max`）。
- **M2 = qwen-max via 阿里云 Anthropic 兼容端点**：Anthropic SDK，env `ALI_ANTHROPIC_BASE_URL`（`.../apps/anthropic`）、`ALI_ANTHROPIC_TOKEN`、`ALI_ANTHROPIC_MODEL`（`qwen3-max`）。
- 统一接口 `LlmClient.extract(task_type, content) -> {reads,writes}`（few-shot 提示复用训练 SYSTEM_PROMPT，确定性低温）。所有调用带超时/重试/JSON 解析兜底，失败降级留痕。

## 4. 真实评估集 pipeline + 标注 schema（论文命脉，新增）· 新目录 `ml/lineage-extractor/realeval/`

- **采集** `collect.py`：GitHub code search 抓开源 ETL 脚本（Airflow DAG、dbt models、PySpark jobs、Sqoop+Hive shell）。
  - **license 过滤**：只保留可再分发（Apache-2.0 / MIT / BSD），记录来源 repo + commit + license，供数据集卡与合规。
  - 去重（近似哈希）+ 脱敏（去 host、密码、内网库名、token）。
  - 输出候选池 `realeval/pool/*.json`（含 provenance）。
- **标注 schema**：与合成 labels 同格式 `{reads:[{table,columns|null}], writes:[...]}`；`realeval/ANNOTATION.md` 定标注规范，含边界 case 裁决：动态拼接表名（忽略）、CTE/临时表（不计外部表）、注释/打印 SQL（忽略）、多语句（全部计入）、`SELECT *`（列= null）。
- **半自动预标 · 双模型交叉** `prelabel.py`：M1、M2（§3.5）+ 规则通道各自独立预标 → 生成 `realeval/tolabel/*.json` 待校验清单，每条含：三方预标值、**一致性标记（全一致=高置信 / 分歧=高亮）**、并集/交集候选。降低单模型偏差。
- **终审**：用户抽查全一致项、**优先终审分歧项**，改错产出 `realeval/gold/*.jsonl`（金标准）。
- 规模：首批 100–150 条，覆盖多引擎分层。**本轮交付 = pipeline + schema + 启动采集 + 双模型预标跑通**；终审分批进行，不阻塞重训。

## 4.5 合成数据交叉生成 + 评审（数据质量门，增强 §1）· `ml/lineage-extractor/data/`

- **交叉生成** `augment.py`（可选增强）：M1 基于模板骨架生成更真实的脚本变体（改写命名/结构，保持 label 语义）；扩充形态真实感，缓解"模板味"。生成结果的 label 由**生成侧模板已知 = 免标**（骨架驱动，非自由生成），杜绝 label 噪声。
- **交叉评审** `review_data.py`：M2 作 critic 抽查合成样本，判定 (content, label) 是否自洽（表名是否真出现、读写方向是否正确）→ 输出可疑清单，人工复核后剔除/修模板。作为数据质量门，catch 模板 bug。此评审结果本身可写入论文"数据质量保障"小节。
- 硬约束：生成/评审**只作用于训练集**；heldout 保持纯模板注入（label 零噪声、形态隔离不被大模型污染）。

## 5. baseline（§3.5 大模型 + 正则）· `ml/lineage-extractor/eval/baselines/`

- 在 §3 predictor 接口下的 adapter：`qwen_dashscope.py`（M1 few-shot）、`ali_anthropic.py`（M2 few-shot）、`regex_baseline.py`（正则，零依赖）。
- 本轮落三者接口 + 正则实跑；M1/M2 few-shot 可随 §3.5 封装一并跑通入表（不再是论文阶段才做——已有 key）。
- **Limitation（论文诚实披露）**：M2(qwen-max) 与本模型(Qwen2.5-Coder-1.5B) 同属 Qwen 系，非跨家族独立 baseline；路径 B 阶段补 GPT-4o/Claude 跨家族对照。

## 6. 重训 v2 + 过闸 + 发布

- `sft_qlora.py` 训练逻辑不变（Qwen2.5-Coder-1.5B + LoRA r16/α32、2 epoch、bf16、SEED 固定），仅喂扩充数据 → `out/run2`。
- **过闸门槛**：表级 precision ≥ 0.85 且 **方向准确率 ≥ 0.95（新闸）** 且 幻觉率 ≤ 0.02 且 heldout custom-wrapper-successor 子集实测优于 v1。
- **证伪式核验（项目文化）**：不看总分看子集——必须亲眼见 heldout 无锚点 wrapper 形态从「方向混淆」转正确，否则判未过。
- 过闸后 `publish.py --version v2`（模型 repo 打 v2 tag；v1 保留可对比）。

## 7. 归档（论文可引用结构）

- 合成数据集（v1 发布中）、模型 v1/v2、分层评估报告、真实集 gold、baseline 结果 → 统一目录 + 数据/模型卡 + 复现说明（SEED + `requirements.txt` 版本锁 + 采集 provenance）。

## 8. 隔离、不阻塞与验证

- **工作副本**：现有 worktree `/home/wallfacers/project/dw-041-script-lineage`（分支 `041-script-lineage-extraction`，训练产物 3.5G 在此）。设计与后续文档进本特性 `specs/041-script-lineage-extraction/`。
- **不阻塞并行**：重训 GPU ~70min ∥ 真实集采集/标注（人力）∥ baseline 论文阶段。三条互不阻塞。
- **验证口径**：重训后 `evaluate.py` 出分层报告 = 唯一事实来源；方向准确率 + custom-wrapper 后继子集为成败判据。

## 附：文件改动清单

| 文件 | 动作 |
|---|---|
| `data/templates.py` | 扩 custom-wrapper 类 + 多引擎/ORM/链式/配置驱动形态；换 heldout 隔离项；补 `form_family` |
| `data/synth_pipeline.py` | heldout 扩 600 + 形态分层；透传 `form_family` |
| `data/augment.py`（新） | M1 交叉生成真实感脚本变体（骨架驱动免标） |
| `data/review_data.py`（新） | M2 critic 评审合成样本自洽性 → 可疑清单 |
| `llm/clients.py`（新） | M1/M2 统一封装，`.env` 读凭据，超时/重试/JSON 兜底 |
| `eval/evaluate.py` | Predictor 接口 + 分层指标（含方向准确率）+ JSON/md 双输出 |
| `eval/baselines/`（新） | `qwen_dashscope.py` + `ali_anthropic.py` + `regex_baseline.py` |
| `realeval/`（新） | `collect.py` + `prelabel.py`（双模型交叉）+ `ANNOTATION.md` + `pool/tolabel/gold` |
| `.env`（gitignore） | M1/M2 凭据；`.env.example` 入 git 作模板 |
| `.gitignore` | 补 `ml/lineage-extractor/.env` 规则 |
| `out/run2/`（gitignore） | v2 训练产物 |
| `publish.py` | 复用，`--version v2` |

## 非目标（YAGNI）

- 本轮不做跨领域（金融/制造）大规模泛化实验（路径 B 才需要）。
- 不改三通道后端 Java 侧（架构章节复用已落地 041 实现，无代码改动）。
- 不冲 SIGMOD/VLDB 顶会（需大量真实企业数据，超本轮）。
- 交叉生成/评审只作用训练集，不碰 heldout（保泛化度量诚实）。
- baseline 本轮用 Qwen 系（有 key）；跨家族 GPT/Claude 留路径 B。
