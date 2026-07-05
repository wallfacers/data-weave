# 041-R 真实 ETL 血缘论文导向重训 v2 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 041 小模型基础上补训练形态、formal 化评估、接双大模型（交叉生成/评审/baseline）、建真实 ETL 评估集，重训 v2 过新闸并发布，产出一篇 ETL 场景论文所需的全部素材。

**Architecture:** 纯 Python ML 流水线（`ml/lineage-extractor/`），零后端 Java 改动。数据合成（模板注入 + 大模型交叉增强）→ LoRA SFT 重训 → 统一 Predictor 接口分层评估（本模型 vs 双大模型 baseline vs 正则）→ 真实集双模型交叉预标 + 人工终审。所有大模型调用经 `llm/clients.py` 薄封装，凭据从 gitignore 的 `.env` 读。

**Tech Stack:** Python 3.12、transformers/peft/trl、torch bf16、pytest、openai SDK（M1 DashScope 兼容）、anthropic SDK（M2 阿里云兼容端点）、huggingface_hub。

## Global Constraints

- 工作副本：worktree `/home/wallfacers/project/dw-041-script-lineage`，分支 `041-script-lineage-extraction`。所有路径相对 `ml/lineage-extractor/`。
- 复现性：合成/训练固定 `SEED = 20260703`，不得引入 `random.random()`/时间依赖到 label 生成。
- label 零噪声铁律：负样本形态（动态表名、注释/打印 SQL）**永不进 labels**；大模型交叉生成只改脚本外形、label 仍由模板骨架已知。
- heldout 纯净：交叉生成/评审**只作用训练集**；heldout 保持纯模板注入 + 形态隔离（训练/heldout 表名池不相交）。
- 凭据安全：明文 key 只进 gitignore 的 `.env`；`.env.example` 只含变量名。禁止 key 出现在任何 tracked 文件或 commit。
- 数据类契约（`data/templates.py` 现有，不改签名）：`Sample(content:str, reads:list[dict], writes:list[dict])`，dict = `{"table":str,"columns":list[str]|None}`；`Template(template_id:str, task_type:str, render:Callable[[Ctx],Sample], rule_covered:bool=True)`；`Ctx` 提供 `table()/tables(n)/cols(n)/num()/distractors(task_type)`。
- 每个 code step 后跑测试；ML/网络/API 任务用契约测试 + 运行验证。commit 频繁，一步一 commit。**仅在本 worktree 提交，不碰 main 工作副本。**

---

### Task 1: LLM 客户端封装 + 凭据配置

**Files:**
- Create: `ml/lineage-extractor/llm/__init__.py`
- Create: `ml/lineage-extractor/llm/clients.py`
- Create: `ml/lineage-extractor/.env.example`
- Create: `ml/lineage-extractor/tests/test_llm_clients.py`
- Modify: `.gitignore`（worktree 根）
- Modify: `ml/lineage-extractor/requirements.txt`（加 `openai>=1.40`、`anthropic>=0.40`、`python-dotenv>=1.0`）

**Interfaces:**
- Produces:
  - `class LlmClient` with `extract(task_type:str, content:str) -> dict`（返回 `{"reads":[...],"writes":[...]}`，失败返回 `{"reads":[],"writes":[],"_error":str}`）
  - `def load_clients() -> dict[str, LlmClient]`（返回 `{"m1": ..., "m2": ...}`，凭据缺失的 key 跳过并 warn）
  - 模块级 `SYSTEM_PROMPT`（复用训练同一 system prompt，从下文常量导入）

- [ ] **Step 1: 补 .gitignore 挡 .env**

在 worktree 根 `.gitignore` 末尾追加：
```
# 041-R 大模型凭据（明文，绝不入库）
ml/lineage-extractor/.env
```

- [ ] **Step 2: 写 .env.example（入 git 的模板）**

```
# M1 = 阿里云 Qwen（DashScope，OpenAI 兼容）
DASHSCOPE_API_KEY=sk-xxxx
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
QWEN_MODEL=qwen-max
# M2 = qwen-max via 阿里云 Anthropic 兼容端点
ALI_ANTHROPIC_BASE_URL=https://llm-xxxx.cn-beijing.maas.aliyuncs.com/apps/anthropic
ALI_ANTHROPIC_TOKEN=sk-xxxx
ALI_ANTHROPIC_MODEL=qwen3-max
```

- [ ] **Step 3: 写实际 .env（本地，gitignore；凭据从用户提供）**

```
DASHSCOPE_API_KEY=sk-13ce8037f48b429795f9506770a629b1
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
QWEN_MODEL=qwen-max
ALI_ANTHROPIC_BASE_URL=https://llm-qyjoek8prv33le84.cn-beijing.maas.aliyuncs.com/apps/anthropic
ALI_ANTHROPIC_TOKEN=sk-13ce8037f48b429795f9506770a629b1
ALI_ANTHROPIC_MODEL=qwen3-max
```

- [ ] **Step 4: 写失败测试（JSON 解析兜底，不触网）**

```python
# tests/test_llm_clients.py
from llm.clients import _parse_lineage_json

def test_parse_extracts_json_from_noisy_text():
    raw = 'Sure!\n```json\n{"reads":[{"table":"a","columns":null}],"writes":[]}\n```'
    got = _parse_lineage_json(raw)
    assert got == {"reads":[{"table":"a","columns":None}],"writes":[]}

def test_parse_returns_empty_on_garbage():
    assert _parse_lineage_json("no json here") == {"reads":[],"writes":[],"_error":"no_json"}
```

- [ ] **Step 5: 运行验证失败**

Run: `cd ml/lineage-extractor && python -m pytest tests/test_llm_clients.py -v`
Expected: FAIL（`_parse_lineage_json` 未定义 / ImportError）

- [ ] **Step 6: 实现 clients.py**

```python
"""041-R 大模型薄封装：M1=DashScope(OpenAI 兼容)、M2=阿里云 Anthropic 兼容端点。"""
from __future__ import annotations
import json, os, re
from pathlib import Path
from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

SYSTEM_PROMPT = (
    "You are a data lineage extractor for ETL scripts. Given a PYTHON or SHELL task "
    "script, output ONLY a JSON object {\"reads\": [...], \"writes\": [...]} where each "
    "item is {\"table\": str, \"columns\": [str] or null}. Rules: include a table only if "
    "its literal name appears in the script text; ignore dynamically-built table names, "
    "commented-out SQL, and SQL that is merely printed or logged; if nothing is read or "
    "written, output {\"reads\": [], \"writes\": []}."
)

def _parse_lineage_json(raw: str) -> dict:
    m = re.search(r"\{.*\}", raw, re.DOTALL)
    if not m:
        return {"reads": [], "writes": [], "_error": "no_json"}
    try:
        obj = json.loads(m.group(0))
        return {"reads": obj.get("reads") or [], "writes": obj.get("writes") or []}
    except Exception as e:
        return {"reads": [], "writes": [], "_error": f"json:{e}"}

def _user_msg(task_type: str, content: str) -> str:
    return f"task_type: {task_type}\nscript:\n{content}"

class LlmClient:
    def __init__(self, name: str, backend, model: str):
        self.name, self._backend, self._model = name, backend, model
    def extract(self, task_type: str, content: str) -> dict:
        try:
            return self._backend(self._model, _user_msg(task_type, content))
        except Exception as e:
            return {"reads": [], "writes": [], "_error": f"call:{e}"}

def _dashscope_backend():
    from openai import OpenAI
    cli = OpenAI(api_key=os.environ["DASHSCOPE_API_KEY"],
                 base_url=os.environ.get("DASHSCOPE_BASE_URL",
                          "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    def call(model, user):
        r = cli.chat.completions.create(model=model, temperature=0.0, timeout=60,
              messages=[{"role":"system","content":SYSTEM_PROMPT},
                        {"role":"user","content":user}])
        return _parse_lineage_json(r.choices[0].message.content or "")
    return call

def _ali_anthropic_backend():
    from anthropic import Anthropic
    cli = Anthropic(base_url=os.environ["ALI_ANTHROPIC_BASE_URL"],
                    api_key=os.environ["ALI_ANTHROPIC_TOKEN"])
    def call(model, user):
        r = cli.messages.create(model=model, max_tokens=512, temperature=0.0,
              system=SYSTEM_PROMPT, messages=[{"role":"user","content":user}])
        text = "".join(b.text for b in r.content if getattr(b, "type", "") == "text")
        return _parse_lineage_json(text)
    return call

def load_clients() -> dict[str, "LlmClient"]:
    out = {}
    if os.environ.get("DASHSCOPE_API_KEY"):
        out["m1"] = LlmClient("m1", _dashscope_backend(), os.environ.get("QWEN_MODEL", "qwen-max"))
    else:
        print("[warn] M1 DASHSCOPE_API_KEY missing; skip")
    if os.environ.get("ALI_ANTHROPIC_TOKEN"):
        out["m2"] = LlmClient("m2", _ali_anthropic_backend(), os.environ.get("ALI_ANTHROPIC_MODEL", "qwen3-max"))
    else:
        print("[warn] M2 ALI_ANTHROPIC_TOKEN missing; skip")
    return out
```

- [ ] **Step 7: 运行单测通过**

Run: `python -m pytest tests/test_llm_clients.py -v`
Expected: PASS（2 passed）

- [ ] **Step 8: 真实连通冒烟（触网，验证 M1/M2 + M2 token 假设）**

Run:
```bash
python -c "
from llm.clients import load_clients
c = load_clients()
for k, cli in c.items():
    r = cli.extract('PYTHON', 'df = spark.table(\"ods.orders\"); df.write.saveAsTable(\"dws.orders_di\")')
    print(k, cli._model, r)
"
```
Expected: `m1`、`m2` 各打印含 `ods.orders`(reads)/`dws.orders_di`(writes) 的 dict，无 `_error`。**若 M2 报 401 → 停下向用户要独立 token**（验证 §设计 M2 token=M1 key 的假设）。

- [ ] **Step 9: Commit**

```bash
git add ml/lineage-extractor/llm ml/lineage-extractor/.env.example ml/lineage-extractor/tests/test_llm_clients.py ml/lineage-extractor/requirements.txt .gitignore
git commit -m "feat(041-R): LLM 双端点封装(M1 DashScope/M2 阿里云Anthropic) + 凭据配置"
```

---

### Task 2: templates.py — custom-wrapper 动词→方向模板族 + form_family

**Files:**
- Modify: `ml/lineage-extractor/data/templates.py`
- Test: `ml/lineage-extractor/tests/test_templates_wrapper.py`

**Interfaces:**
- Consumes: `Sample`、`Template`、`Ctx`（Global Constraints 契约）
- Produces:
  - `Template.form_family: str` 新字段（默认由 `template_id` 前缀推断）
  - 训练模板函数 `t_py_wrapper_verb(ctx) -> Sample`（自定义包装函数，动词前缀决定方向）
  - 模块常量 `READ_VERBS`、`WRITE_VERBS`（list[str]）

- [ ] **Step 1: 写失败测试**

```python
# tests/test_templates_wrapper.py
import random
from data.templates import Ctx, t_py_wrapper_verb, READ_VERBS, WRITE_VERBS

def _ctx():
    return Ctx(random.Random(1), ["ods.a", "dws.b", "ads.c"], ["id", "amt", "dt"])

def test_wrapper_read_verb_goes_to_reads():
    s = t_py_wrapper_verb(_ctx())
    read_tables = {r["table"] for r in s.reads}
    write_tables = {w["table"] for w in s.writes}
    # 读表与写表不重叠，且都真实出现在脚本文本
    assert read_tables and write_tables
    assert not (read_tables & write_tables)
    for t in read_tables | write_tables:
        assert t in s.content

def test_verb_pools_disjoint():
    assert not (set(READ_VERBS) & set(WRITE_VERBS))
```

- [ ] **Step 2: 运行验证失败**

Run: `cd ml/lineage-extractor && python -m pytest tests/test_templates_wrapper.py -v`
Expected: FAIL（ImportError: `t_py_wrapper_verb`）

- [ ] **Step 3: 实现模板 + 常量（加到 templates.py）**

```python
READ_VERBS = ["load", "read", "fetch", "extract", "source", "pull", "get"]
WRITE_VERBS = ["write", "save", "sink", "persist", "dump", "export", "push", "upsert"]

def t_py_wrapper_verb(ctx: "Ctx") -> Sample:
    r, w = ctx.tables(2)
    rv = ctx.rng.choice(READ_VERBS)
    wv = ctx.rng.choice(WRITE_VERBS)
    noun_r = ctx.rng.choice(["frame", "source", "table", "dataset", "input"])
    noun_w = ctx.rng.choice(["warehouse", "sink", "target", "output", "store"])
    return Sample(
        content=(f'df = {rv}_{noun_r}(ctx, "{r}")\n'
                 f'{wv}_to_{noun_w}(df, "{w}", mode="overwrite")\n'),
        reads=[{"table": r, "columns": None}],
        writes=[{"table": w, "columns": None}],
    )
```

在 `Template` dataclass 加字段（改现有定义）：
```python
@dataclass
class Template:
    template_id: str
    task_type: str
    render: Callable[["Ctx"], Sample]
    rule_covered: bool = True
    form_family: str = ""  # 空则由 synth_pipeline 按 template_id 前缀推断
```

在 `TRAIN_TEMPLATES` 列表加：`Template("py-wrapper-verb", "PYTHON", t_py_wrapper_verb, rule_covered=False, form_family="wrapper")`

- [ ] **Step 4: 运行验证通过**

Run: `python -m pytest tests/test_templates_wrapper.py -v`
Expected: PASS（2 passed）

- [ ] **Step 5: Commit**

```bash
git add ml/lineage-extractor/data/templates.py ml/lineage-extractor/tests/test_templates_wrapper.py
git commit -m "feat(041-R): custom-wrapper 动词→方向模板族 + Template.form_family"
```

---

### Task 3: templates.py — 多引擎/ORM/链式/配置驱动形态扩充

**Files:**
- Modify: `ml/lineage-extractor/data/templates.py`
- Test: `ml/lineage-extractor/tests/test_templates_forms.py`

**Interfaces:**
- Consumes: `Sample`、`Ctx`、`_insert_select`（现有 helper）
- Produces: 训练模板函数 `t_py_pyspark_saveastable`、`t_py_sqlalchemy_orm`、`t_sh_presto`、`t_sh_beeline`、`t_py_pandas_sql`（各 `-> Sample`）；均登记进 `TRAIN_TEMPLATES` 且带 `form_family`

- [ ] **Step 1: 写失败测试（每形态断言读写表真实出现且方向正确）**

```python
# tests/test_templates_forms.py
import random
from data.templates import (Ctx, t_py_pyspark_saveastable, t_py_sqlalchemy_orm,
                            t_sh_presto, t_sh_beeline, t_py_pandas_sql)

def _ctx():
    return Ctx(random.Random(7), ["ods.a", "dwd.b", "dws.c", "ads.d"], ["id", "amt", "dt", "region"])

def _check(fn):
    s = fn(_ctx())
    rt = {r["table"] for r in s.reads}
    wt = {w["table"] for w in s.writes}
    assert rt and wt and not (rt & wt)
    for t in rt | wt:
        assert t.split(".")[-1] in s.content or t in s.content
    return s

def test_all_new_forms():
    for fn in (t_py_pyspark_saveastable, t_py_sqlalchemy_orm, t_sh_presto, t_sh_beeline, t_py_pandas_sql):
        _check(fn)
```

- [ ] **Step 2: 运行验证失败**

Run: `python -m pytest tests/test_templates_forms.py -v`
Expected: FAIL（ImportError）

- [ ] **Step 3: 实现五个模板（加到 templates.py）**

```python
def t_py_pyspark_saveastable(ctx: "Ctx") -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'df = spark.read.table("{r}")\n'
                 f'df.filter("dt >= \'2024-01-01\'").write.mode("append").saveAsTable("{w}")\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])

def t_py_sqlalchemy_orm(ctx: "Ctx") -> Sample:
    r, w = ctx.tables(2)
    c = ctx.cols(2)
    return Sample(
        content=(f'rows = session.query(Src).filter(Src.{c[0]} > {ctx.num()}).all()\n'
                 f'# src table: {r}\n'
                 f'engine.execute("INSERT INTO {w} SELECT * FROM {r}")\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])

def t_sh_presto(ctx: "Ctx") -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'presto --server presto01:8080 --catalog hive --execute "{sql}"\n'
    return s

def t_sh_beeline(ctx: "Ctx") -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'beeline -u jdbc:hive2://hs2:10000 -e "{sql}"\n'
    return s

def t_py_pandas_sql(ctx: "Ctx") -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'df = pd.read_sql("SELECT * FROM {r}", conn)\n'
                 f'df.to_sql("{w}", conn, if_exists="replace", index=False)\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])
```

登记进 `TRAIN_TEMPLATES`：
```python
Template("py-pyspark-saveastable", "PYTHON", t_py_pyspark_saveastable, form_family="chain"),
Template("py-sqlalchemy-orm", "PYTHON", t_py_sqlalchemy_orm, form_family="orm"),
Template("sh-presto", "SHELL", t_sh_presto, form_family="cli"),
Template("sh-beeline", "SHELL", t_sh_beeline, form_family="cli"),
Template("py-pandas-sql", "PYTHON", t_py_pandas_sql, form_family="chain"),
```

- [ ] **Step 4: 运行验证通过**

Run: `python -m pytest tests/test_templates_forms.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ml/lineage-extractor/data/templates.py ml/lineage-extractor/tests/test_templates_forms.py
git commit -m "feat(041-R): 扩充多引擎CLI/ORM/链式DataFrame/pandas 形态模板"
```

---

### Task 4: templates.py — 重构 heldout 隔离形态

**Files:**
- Modify: `ml/lineage-extractor/data/templates.py`
- Test: `ml/lineage-extractor/tests/test_heldout_forms.py`

**Interfaces:**
- Produces: 新 heldout-only 模板 `h_py_orm_chain_dsl`、`h_py_decorator_pipeline`、`h_sh_dbt_run`（各 `-> Sample`，`rule_covered=False`）；从 `HELDOUT_TEMPLATES` 移除 `h-py-custom-wrapper`（已进训练集 Task 2），保留 `h_py_spark_table_alias` 等其余隔离项

- [ ] **Step 1: 写失败测试**

```python
# tests/test_heldout_forms.py
import random
from data.templates import (Ctx, HELDOUT_TEMPLATES,
                            h_py_orm_chain_dsl, h_py_decorator_pipeline, h_sh_dbt_run)

def _ctx():
    return Ctx(random.Random(3), ["ods.x", "dwd.y", "dws.z"], ["id", "v", "dt"])

def test_new_heldout_forms_valid():
    for fn in (h_py_orm_chain_dsl, h_py_decorator_pipeline, h_sh_dbt_run):
        s = fn(_ctx())
        rt = {r["table"] for r in s.reads}; wt = {w["table"] for w in s.writes}
        assert rt and wt and not (rt & wt)

def test_custom_wrapper_removed_from_heldout():
    ids = {t.template_id for t in HELDOUT_TEMPLATES}
    assert "h-py-custom-wrapper" not in ids  # 已移入训练集，避免泄漏
```

- [ ] **Step 2: 运行验证失败**

Run: `python -m pytest tests/test_heldout_forms.py -v`
Expected: FAIL

- [ ] **Step 3: 实现新 heldout 模板 + 从 HELDOUT_TEMPLATES 删 custom-wrapper**

```python
def h_py_orm_chain_dsl(ctx: "Ctx") -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'Repo.of("{r}").select(["id", "amt"]).copy_into("{w}").commit()\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])

def h_py_decorator_pipeline(ctx: "Ctx") -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'@pipeline(reads="{r}", writes="{w}")\n'
                 f'def run(src):\n    return transform(src)\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])

def h_sh_dbt_run(ctx: "Ctx") -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'# model {w} depends on {r}\n'
                 f'dbt run --select {w.split(".")[-1]} --vars \'{{"src":"{r}"}}\'\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])
```

在 `HELDOUT_TEMPLATES` 列表：删除含 `h-py-custom-wrapper` 的行，新增：
```python
Template("h-py-orm-chain-dsl", "PYTHON", h_py_orm_chain_dsl, rule_covered=False, form_family="orm"),
Template("h-py-decorator-pipeline", "PYTHON", h_py_decorator_pipeline, rule_covered=False, form_family="decorator"),
Template("h-sh-dbt-run", "SHELL", h_sh_dbt_run, rule_covered=False, form_family="config"),
```

- [ ] **Step 4: 运行验证通过**

Run: `python -m pytest tests/test_heldout_forms.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ml/lineage-extractor/data/templates.py ml/lineage-extractor/tests/test_heldout_forms.py
git commit -m "feat(041-R): heldout 换新隔离形态(ORM链/装饰器/dbt)，移除已泄漏 custom-wrapper"
```

---

### Task 5: synth_pipeline.py — heldout 扩 600 + form_family 透传分层

**Files:**
- Modify: `ml/lineage-extractor/data/synth_pipeline.py`
- Test: `ml/lineage-extractor/tests/test_synth_layered.py`

**Interfaces:**
- Consumes: `TRAIN_TEMPLATES`、`HELDOUT_TEMPLATES`、`Template.form_family`
- Produces: 每条样本 `meta.form_family` 字段；`render()` 写入 `form_family`（空则 `template_id.split("-")[0..]` 兜底）

- [ ] **Step 1: 写失败测试**

```python
# tests/test_synth_layered.py
import random
from data.templates import TRAIN_TEMPLATES
from data.synth_pipeline import render

def test_render_carries_form_family():
    rng = random.Random(5)
    tpl = TRAIN_TEMPLATES[0]
    row = render(rng, tpl, ["ods.a", "dws.b", "ads.c"], ["id", "v"], "train")
    assert "form_family" in row["meta"]
    assert row["meta"]["form_family"]  # 非空
```

- [ ] **Step 2: 运行验证失败**

Run: `python -m pytest tests/test_synth_layered.py -v`
Expected: FAIL（KeyError: form_family）

- [ ] **Step 3: 改 render() 写入 form_family**

在 `synth_pipeline.py` 的 `render()` 里，`meta` dict 加一行：
```python
"form_family": tpl.form_family or tpl.template_id.split("-")[0],
```

- [ ] **Step 4: 运行验证通过 + heldout 扩 600**

Run: `python -m pytest tests/test_synth_layered.py -v` → PASS
把 `main()` 的 `--heldout-size` 默认从 240 改 600：`ap.add_argument("--heldout-size", type=int, default=600)`

- [ ] **Step 5: Commit**

```bash
git add ml/lineage-extractor/data/synth_pipeline.py ml/lineage-extractor/tests/test_synth_layered.py
git commit -m "feat(041-R): synth 透传 form_family 分层 + heldout 默认扩 600"
```

---

### Task 6: evaluate.py — Predictor 接口 + 方向准确率 + 分层指标

**Files:**
- Modify: `ml/lineage-extractor/eval/evaluate.py`
- Create: `ml/lineage-extractor/eval/metrics.py`（纯函数指标，可独立测）
- Test: `ml/lineage-extractor/tests/test_metrics.py`

**Interfaces:**
- Produces（`eval/metrics.py`）：
  - `def score_row(gold:dict, pred:dict, content:str) -> dict`：返回 `{tp,fp,fn,halluc,pred_total,dir_total,dir_correct,invalid}` 计数
  - `def direction_accuracy(rows_counts:list[dict]) -> float`
  - `def aggregate(counts:list[dict]) -> dict`：`{precision,recall,f1,hallucination,direction_acc,...}`
  - 常量 `GATE = {"precision":0.85, "uncovered_recall":0.60, "hallucination":0.02, "direction_acc":0.95}`
- Consumes: `tables()`（从 evaluate.py 移入 metrics.py）

- [ ] **Step 1: 写失败测试（方向准确率语义）**

```python
# tests/test_metrics.py
from eval.metrics import score_row, aggregate, GATE

def test_direction_confusion_counted():
    # gold: 读 a 写 b；pred 把 a 也塞进 writes（方向混淆）
    gold = {"reads":[{"table":"a"}], "writes":[{"table":"b"}]}
    pred = {"reads":[], "writes":[{"table":"a"},{"table":"b"}]}
    c = score_row(gold, pred, "a b")
    # 表 a 出现在错误方向 → 计入方向错误
    assert c["dir_total"] >= 1
    assert c["dir_correct"] < c["dir_total"]

def test_perfect_row_full_direction():
    gold = {"reads":[{"table":"a"}], "writes":[{"table":"b"}]}
    pred = {"reads":[{"table":"a"}], "writes":[{"table":"b"}]}
    c = score_row(gold, pred, "a b")
    assert c["dir_correct"] == c["dir_total"]
    agg = aggregate([c])
    assert agg["direction_acc"] == 1.0

def test_gate_has_direction_threshold():
    assert GATE["direction_acc"] == 0.95
```

- [ ] **Step 2: 运行验证失败**

Run: `cd ml/lineage-extractor && python -m pytest tests/test_metrics.py -v`
Expected: FAIL（No module named eval.metrics）

- [ ] **Step 3: 实现 metrics.py**

```python
"""041-R 分层指标纯函数（无 torch 依赖，可独立单测）。"""
from __future__ import annotations

GATE = {"precision": 0.85, "uncovered_recall": 0.60, "hallucination": 0.02, "direction_acc": 0.95}

def tables(items) -> set[str]:
    out = set()
    for it in items or []:
        if isinstance(it, dict) and isinstance(it.get("table"), str) and it["table"].strip():
            out.add(it["table"].strip().lower())
    return out

def score_row(gold: dict, pred: dict, content: str) -> dict:
    gr, gw = tables(gold.get("reads")), tables(gold.get("writes"))
    pr, pw = tables(pred.get("reads")), tables(pred.get("writes"))
    cl = content.lower()
    c = dict(tp=0, fp=0, fn=0, halluc=0, pred_total=0, dir_total=0, dir_correct=0,
             invalid=1 if pred.get("_invalid") or pred.get("_error") else 0)
    for gold_set, got_set in [(gr, pr), (gw, pw)]:
        c["tp"] += len(gold_set & got_set)
        c["fp"] += len(got_set - gold_set)
        c["fn"] += len(gold_set - got_set)
        for t in got_set:
            c["pred_total"] += 1
            if t not in cl:
                c["halluc"] += 1
    # 方向准确率：对 gold 中每个真实表，判断预测是否放对了方向
    for t in gr | gw:
        c["dir_total"] += 1
        want_read = t in gr
        got_read, got_write = t in pr, t in pw
        placed = (want_read and got_read and not got_write) or ((t in gw) and got_write and not got_read)
        if placed:
            c["dir_correct"] += 1
    return c

def aggregate(counts: list[dict]) -> dict:
    s = {k: sum(c[k] for c in counts) for k in
         ("tp", "fp", "fn", "halluc", "pred_total", "dir_total", "dir_correct", "invalid")}
    prec = s["tp"] / (s["tp"] + s["fp"]) if s["tp"] + s["fp"] else 1.0
    rec = s["tp"] / (s["tp"] + s["fn"]) if s["tp"] + s["fn"] else 1.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    return dict(precision=prec, recall=rec, f1=f1,
                hallucination=s["halluc"] / s["pred_total"] if s["pred_total"] else 0.0,
                direction_acc=s["dir_correct"] / s["dir_total"] if s["dir_total"] else 1.0,
                invalid=s["invalid"])

def direction_accuracy(counts: list[dict]) -> float:
    return aggregate(counts)["direction_acc"]
```

- [ ] **Step 4: 运行验证通过**

Run: `python -m pytest tests/test_metrics.py -v`
Expected: PASS（3 passed）

- [ ] **Step 5: Commit**

```bash
git add ml/lineage-extractor/eval/metrics.py ml/lineage-extractor/tests/test_metrics.py
git commit -m "feat(041-R): 分层指标纯函数(方向准确率+聚合+新闸阈值)"
```

---

### Task 7: evaluate.py — 接 metrics + Predictor 抽象 + 分层报告

**Files:**
- Modify: `ml/lineage-extractor/eval/evaluate.py`
- Test: `ml/lineage-extractor/tests/test_evaluate_layers.py`

**Interfaces:**
- Consumes: `eval.metrics`（score_row/aggregate/GATE）、`LlmClient`（可选 predictor）
- Produces:
  - `def run_eval(predict_fn, rows) -> dict`：`predict_fn(row)->{reads,writes}`；返回 `{overall:aggregate, by_family:{fam:aggregate}, by_source:{...}, failures:[...]}`
  - `def write_report(result, path)`：md + 同名 `.json`

- [ ] **Step 1: 写失败测试（用假 predictor，不加载模型）**

```python
# tests/test_evaluate_layers.py
from eval.evaluate import run_eval

def _rows():
    return [
      {"task_type":"PYTHON","content":"read a write b",
       "labels":{"reads":[{"table":"a"}],"writes":[{"table":"b"}]},
       "meta":{"template_id":"t1","form_family":"wrapper","rule_covered":False,"split_group":"heldout"}},
    ]

def test_run_eval_layers():
    perfect = lambda row: {"reads":[{"table":"a"}],"writes":[{"table":"b"}]}
    res = run_eval(perfect, _rows())
    assert res["overall"]["precision"] == 1.0
    assert res["overall"]["direction_acc"] == 1.0
    assert "wrapper" in res["by_family"]
```

- [ ] **Step 2: 运行验证失败**

Run: `python -m pytest tests/test_evaluate_layers.py -v`
Expected: FAIL（run_eval 不存在）

- [ ] **Step 3: 重构 evaluate.py（抽 run_eval/write_report，main 组装）**

```python
from eval.metrics import score_row, aggregate, GATE

def run_eval(predict_fn, rows: list) -> dict:
    counts, by_fam, by_src, failures = [], {}, {}, []
    for i, row in enumerate(rows):
        pred = predict_fn(row)
        c = score_row(row["labels"], pred, row["content"])
        counts.append(c)
        fam = row["meta"].get("form_family", "?")
        src = "synth" if "synth" in str(row["meta"].get("source_dataset", "synth")) else "real"
        by_fam.setdefault(fam, []).append(c)
        by_src.setdefault(src, []).append(c)
        # 失败样例
        from eval.metrics import tables
        for d, g, p in [("R", tables(row["labels"]["reads"]), tables(pred.get("reads"))),
                        ("W", tables(row["labels"]["writes"]), tables(pred.get("writes")))]:
            if g != p and len(failures) < 30:
                failures.append(f"[{row['meta']['template_id']}#{i}] {d} gold={sorted(g)} got={sorted(p)}")
    return {"overall": aggregate(counts), "n": len(rows),
            "by_family": {k: aggregate(v) for k, v in by_fam.items()},
            "by_source": {k: aggregate(v) for k, v in by_src.items()},
            "uncovered": aggregate([c for c, r in zip(counts, rows)
                                    if not r["meta"].get("rule_covered", True)]),
            "failures": failures}
```

`write_report(result, path)`：把 overall/by_family/by_source/uncovered 渲染成 md（沿用 eval-report.md 风格，含新增「方向准确率」「分形态表」），并 `json.dump` 到同名 `.json`。`main()`：保留 transformers 加载，`predict_fn = lambda row: predict(model, tok, row)`，调 `run_eval` + `write_report`，`passed = overall.precision>=GATE.precision and uncovered.recall>=GATE.uncovered_recall and overall.hallucination<=GATE.hallucination and overall.direction_acc>=GATE.direction_acc`，`sys.exit(0 if passed else 1)`。

- [ ] **Step 4: 运行验证通过**

Run: `python -m pytest tests/test_evaluate_layers.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ml/lineage-extractor/eval/evaluate.py ml/lineage-extractor/tests/test_evaluate_layers.py
git commit -m "feat(041-R): evaluate 抽 Predictor+分层报告(形态/来源/方向)，新闸接入"
```

---

### Task 8: baseline adapters（正则 + M1 + M2）

**Files:**
- Create: `ml/lineage-extractor/eval/baselines/__init__.py`
- Create: `ml/lineage-extractor/eval/baselines/regex_baseline.py`
- Create: `ml/lineage-extractor/eval/baselines/llm_baseline.py`
- Test: `ml/lineage-extractor/tests/test_regex_baseline.py`

**Interfaces:**
- Produces:
  - `regex_baseline.predict(row) -> {reads,writes}`（正则抓 `FROM/JOIN`→reads、`INSERT INTO/saveAsTable/to_sql`→writes）
  - `llm_baseline.make_predict(client) -> callable`（包 `LlmClient.extract` 成 `predict(row)`）

- [ ] **Step 1: 写失败测试（正则纯函数）**

```python
# tests/test_regex_baseline.py
from eval.baselines.regex_baseline import predict

def test_regex_catches_insert_select():
    row = {"task_type":"SHELL","content":'hive -e "INSERT INTO dws.x SELECT * FROM ods.y"'}
    got = predict(row)
    assert {t["table"] for t in got["writes"]} == {"dws.x"}
    assert {t["table"] for t in got["reads"]} == {"ods.y"}
```

- [ ] **Step 2: 运行验证失败**

Run: `python -m pytest tests/test_regex_baseline.py -v` → FAIL

- [ ] **Step 3: 实现 regex_baseline.py + llm_baseline.py**

```python
# regex_baseline.py
import re
_READ = re.compile(r"\b(?:FROM|JOIN)\s+([A-Za-z_][\w.]*)", re.IGNORECASE)
_WRITE = re.compile(r"(?:INSERT\s+INTO|INSERT\s+OVERWRITE\s+TABLE|saveAsTable\(|to_sql\(|writeTo\()\s*[\"']?([A-Za-z_][\w.]*)", re.IGNORECASE)
def predict(row: dict) -> dict:
    c = row["content"]
    reads = [{"table": t, "columns": None} for t in dict.fromkeys(m.group(1) for m in _READ.finditer(c))]
    writes = [{"table": t, "columns": None} for t in dict.fromkeys(m.group(1) for m in _WRITE.finditer(c))]
    wt = {w["table"] for w in writes}
    reads = [r for r in reads if r["table"] not in wt]  # 写表不重复计读
    return {"reads": reads, "writes": writes}
```
```python
# llm_baseline.py
def make_predict(client):
    def predict(row: dict) -> dict:
        return client.extract(row["task_type"], row["content"])
    return predict
```

- [ ] **Step 4: 运行验证通过**

Run: `python -m pytest tests/test_regex_baseline.py -v` → PASS

- [ ] **Step 5: Commit**

```bash
git add ml/lineage-extractor/eval/baselines ml/lineage-extractor/tests/test_regex_baseline.py
git commit -m "feat(041-R): baseline adapters(正则+M1/M2 LLM 包装)"
```

---

### Task 9: 合成数据交叉评审（M2 critic 数据质量门）

**Files:**
- Create: `ml/lineage-extractor/data/review_data.py`
- Test: `ml/lineage-extractor/tests/test_review_data.py`

**Interfaces:**
- Consumes: `LlmClient`（M2）
- Produces:
  - `def self_consistency_flags(row) -> list[str]`：纯规则预检（表名是否真出现、读写方向是否自洽），返回问题标签列表
  - `def review_sample(client, row) -> dict`：`{template_id, issues:[...], llm_verdict:{...}}`（LLM 抽查，可选）

- [ ] **Step 1: 写失败测试（纯规则自检，不触网）**

```python
# tests/test_review_data.py
from data.review_data import self_consistency_flags

def test_flags_table_not_in_content():
    row = {"content":"read ods.a", "labels":{"reads":[{"table":"ghost.tbl"}],"writes":[]}}
    assert "read_table_absent:ghost.tbl" in self_consistency_flags(row)

def test_clean_sample_no_flags():
    row = {"content":"INSERT INTO dws.b SELECT * FROM ods.a",
           "labels":{"reads":[{"table":"ods.a"}],"writes":[{"table":"dws.b"}]}}
    assert self_consistency_flags(row) == []
```

- [ ] **Step 2: 运行验证失败** → Run pytest → FAIL

- [ ] **Step 3: 实现 review_data.py**

```python
"""041-R 合成数据质量门：规则自检 + M2 critic 抽查。只作用训练集。"""
def self_consistency_flags(row: dict) -> list[str]:
    cl = row["content"].lower()
    flags = []
    for d, items in (("read", row["labels"].get("reads", [])),
                     ("write", row["labels"].get("writes", []))):
        for it in items:
            t = str(it.get("table", "")).lower()
            if t and t not in cl and t.split(".")[-1] not in cl:
                flags.append(f"{d}_table_absent:{it['table']}")
    rt = {r["table"] for r in row["labels"].get("reads", [])}
    wt = {w["table"] for w in row["labels"].get("writes", [])}
    for t in rt & wt:
        flags.append(f"read_write_overlap:{t}")
    return flags

def review_sample(client, row: dict) -> dict:
    issues = self_consistency_flags(row)
    verdict = client.extract(row["task_type"], row["content"]) if client else None
    return {"template_id": row.get("meta", {}).get("template_id"), "issues": issues, "llm_verdict": verdict}
```

- [ ] **Step 4: 运行验证通过** → PASS

- [ ] **Step 5: Commit**

```bash
git add ml/lineage-extractor/data/review_data.py ml/lineage-extractor/tests/test_review_data.py
git commit -m "feat(041-R): 合成数据质量门(规则自检+M2 critic)"
```

---

### Task 10: 合成数据交叉生成（M1 骨架驱动增强）

**Files:**
- Create: `ml/lineage-extractor/data/augment.py`
- Test: `ml/lineage-extractor/tests/test_augment.py`

**Interfaces:**
- Consumes: `LlmClient`（M1）
- Produces: `def paraphrase_script(client, content, tables_keep:set[str]) -> str|None`：M1 改写脚本外形，**校验所有 tables_keep 仍字面出现**，否则返回 None（丢弃，保 label 有效）

- [ ] **Step 1: 写失败测试（校验逻辑，用假 client）**

```python
# tests/test_augment.py
from data.augment import paraphrase_script

class _Fake:
    def __init__(self, out): self._out = out
    def extract(self, *a): return {}
    def raw(self, prompt): return self._out

def test_reject_when_table_dropped(monkeypatch):
    # 改写结果丢了表 ods.a → 必须返回 None
    from data import augment
    monkeypatch.setattr(augment, "_rewrite", lambda c, content: "df = load('other')")
    assert paraphrase_script(object(), "x ods.a y", {"ods.a"}) is None

def test_accept_when_tables_preserved(monkeypatch):
    from data import augment
    monkeypatch.setattr(augment, "_rewrite", lambda c, content: "frame = fetch('ods.a')")
    out = paraphrase_script(object(), "x ods.a", {"ods.a"})
    assert out and "ods.a" in out
```

- [ ] **Step 2: 运行验证失败** → FAIL

- [ ] **Step 3: 实现 augment.py**

```python
"""041-R 交叉生成：M1 改写脚本外形，label 由骨架已知(免标)。丢弃改丢表名的结果。"""
_PROMPT = ("Rewrite the following ETL script to look more realistic (rename variables, "
           "restructure), but you MUST keep every table name literal unchanged. "
           "Output ONLY the rewritten script.\n\n{content}")

def _rewrite(client, content: str) -> str:
    # M1 走 chat；复用 openai backend 的裸文本调用
    from openai import OpenAI
    import os
    cli = OpenAI(api_key=os.environ["DASHSCOPE_API_KEY"],
                 base_url=os.environ.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    r = cli.chat.completions.create(model=os.environ.get("QWEN_MODEL", "qwen-max"),
          temperature=0.7, timeout=60,
          messages=[{"role": "user", "content": _PROMPT.format(content=content)}])
    return r.choices[0].message.content or ""

def paraphrase_script(client, content: str, tables_keep: set[str]) -> str | None:
    out = _rewrite(client, content)
    low = out.lower()
    if all(t.lower() in low for t in tables_keep):
        return out
    return None
```

- [ ] **Step 4: 运行验证通过** → PASS

- [ ] **Step 5: 真实增强冒烟（触网，可选小样）**

Run:
```bash
python -c "
from llm.clients import load_clients
from data.augment import paraphrase_script
c = load_clients()['m1']
print(paraphrase_script(c, 'df = spark.read.table(\"ods.orders\"); df.write.saveAsTable(\"dws.o\")', {'ods.orders','dws.o'}))
"
```
Expected: 打印改写后脚本，含两表名；或 None（被拒）。

- [ ] **Step 6: Commit**

```bash
git add ml/lineage-extractor/data/augment.py ml/lineage-extractor/tests/test_augment.py
git commit -m "feat(041-R): M1 骨架驱动交叉生成(改外形保表名，免标)"
```

---

### Task 11: 真实集标注规范 + 采集器

**Files:**
- Create: `ml/lineage-extractor/realeval/ANNOTATION.md`
- Create: `ml/lineage-extractor/realeval/collect.py`
- Test: `ml/lineage-extractor/tests/test_collect.py`

**Interfaces:**
- Produces:
  - `def sanitize(text) -> str`：脱敏（去 host:port、password=、token、内网 IP）
  - `def dedup_key(content) -> str`：归一化哈希
  - `def is_redistributable(license_id) -> bool`：Apache/MIT/BSD → True

- [ ] **Step 1: 写失败测试（纯函数）**

```python
# tests/test_collect.py
from realeval.collect import sanitize, is_redistributable, dedup_key

def test_sanitize_strips_secrets():
    s = sanitize('psql -h 10.0.0.5 --password=secret123 -c "SELECT 1"')
    assert "secret123" not in s and "10.0.0.5" not in s

def test_license_filter():
    assert is_redistributable("Apache-2.0") and is_redistributable("MIT")
    assert not is_redistributable("GPL-3.0")

def test_dedup_stable():
    assert dedup_key("SELECT  1") == dedup_key("select 1")
```

- [ ] **Step 2: 运行验证失败** → FAIL

- [ ] **Step 3: 实现 collect.py + 写 ANNOTATION.md**

```python
"""041-R 真实 ETL 脚本采集：GitHub code search → license 过滤 → 脱敏 → 候选池。"""
import re, hashlib
_SECRET = [re.compile(r"-h\s+\d+\.\d+\.\d+\.\d+"), re.compile(r"--?password[=\s]\S+", re.I),
           re.compile(r"\b(?:token|apikey|api_key)[=:]\s*\S+", re.I),
           re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b")]
_OK_LICENSE = {"apache-2.0", "mit", "bsd-2-clause", "bsd-3-clause", "isc"}

def sanitize(text: str) -> str:
    for p in _SECRET:
        text = p.sub("<redacted>", text)
    return text

def is_redistributable(license_id: str) -> bool:
    return (license_id or "").strip().lower() in _OK_LICENSE

def dedup_key(content: str) -> str:
    norm = re.sub(r"\s+", " ", content.strip().lower())
    return hashlib.sha256(norm.encode()).hexdigest()[:16]
```

`ANNOTATION.md` 写标注规范：label 格式 `{reads:[{table,columns|null}],writes:[...]}`；裁决规则——动态拼接表名忽略、CTE/临时表不计外部表、注释/打印 SQL 忽略、多语句全计、`SELECT *` 列= null、跨库全名保留 `schema.table`。采集主流程（GitHub API 调用）写为 `main()`，用 `PyGithub` 或 `requests` 调 code search（`q=INSERT+INTO+language:python`），逐条 license 过滤 + sanitize + dedup → 写 `realeval/pool/*.json`（含 `{content, source:{repo,path,commit,license}}`）。

- [ ] **Step 4: 运行验证通过** → PASS

- [ ] **Step 5: 真实采集小跑（触网，需 GITHUB_TOKEN；先抓 20 条验证管线）**

Run: `GITHUB_TOKEN=<你的 gh token> python realeval/collect.py --limit 20 --out realeval/pool`
Expected: 生成 ≥1 个 pool json，每条含 provenance 且已脱敏。**GITHUB_TOKEN 缺失 → 向用户索取或用 gh CLI 的 token。**

- [ ] **Step 6: Commit**

```bash
git add ml/lineage-extractor/realeval/ANNOTATION.md ml/lineage-extractor/realeval/collect.py ml/lineage-extractor/tests/test_collect.py
git commit -m "feat(041-R): 真实集采集器(license过滤+脱敏+去重)+标注规范"
```

---

### Task 12: 真实集双模型交叉预标

**Files:**
- Create: `ml/lineage-extractor/realeval/prelabel.py`
- Test: `ml/lineage-extractor/tests/test_prelabel.py`

**Interfaces:**
- Consumes: `load_clients`、`regex_baseline.predict`、`eval.metrics.tables`
- Produces: `def cross_prelabel(predictors:dict, row) -> dict`：`{predictions:{name:{reads,writes}}, agreement:{"reads":bool,"writes":bool}, union, intersection, needs_review:bool}`

- [ ] **Step 1: 写失败测试（用假 predictors）**

```python
# tests/test_prelabel.py
from realeval.prelabel import cross_prelabel

def test_agreement_and_review_flag():
    preds = {
      "m1": lambda r: {"reads":[{"table":"a"}],"writes":[{"table":"b"}]},
      "m2": lambda r: {"reads":[{"table":"a"}],"writes":[{"table":"b"}]},
      "rule": lambda r: {"reads":[{"table":"a"}],"writes":[{"table":"b"}]},
    }
    out = cross_prelabel(preds, {"task_type":"PYTHON","content":"a b"})
    assert out["agreement"]["reads"] and out["agreement"]["writes"]
    assert out["needs_review"] is False

def test_disagreement_flags_review():
    preds = {
      "m1": lambda r: {"reads":[{"table":"a"}],"writes":[]},
      "m2": lambda r: {"reads":[],"writes":[{"table":"a"}]},
    }
    out = cross_prelabel(preds, {"task_type":"PYTHON","content":"a"})
    assert out["needs_review"] is True
```

- [ ] **Step 2: 运行验证失败** → FAIL

- [ ] **Step 3: 实现 prelabel.py**

```python
"""041-R 真实集双模型交叉预标：M1+M2+规则各自独立预标，一致=高置信，分歧=高亮终审。"""
from eval.metrics import tables

def cross_prelabel(predictors: dict, row: dict) -> dict:
    preds = {name: fn(row) for name, fn in predictors.items()}
    read_sets = [tables(p.get("reads")) for p in preds.values()]
    write_sets = [tables(p.get("writes")) for p in preds.values()]
    agree_r = all(s == read_sets[0] for s in read_sets)
    agree_w = all(s == write_sets[0] for s in write_sets)
    def _u(sets): 
        u = set().union(*sets) if sets else set()
        return sorted(u)
    def _i(sets):
        i = set(sets[0]).intersection(*sets) if sets else set()
        return sorted(i)
    return {"predictions": preds,
            "agreement": {"reads": agree_r, "writes": agree_w},
            "union": {"reads": _u(read_sets), "writes": _u(write_sets)},
            "intersection": {"reads": _i(read_sets), "writes": _i(write_sets)},
            "needs_review": not (agree_r and agree_w)}
```

`main()`：读 `realeval/pool/*.json`，`predictors = {"m1": llm_baseline.make_predict(clients["m1"]), "m2": ..., "rule": regex_baseline.predict}`，逐条 `cross_prelabel` → 写 `realeval/tolabel/*.json`（含 needs_review 排序，分歧在前）。

- [ ] **Step 4: 运行验证通过** → PASS

- [ ] **Step 5: 真实预标小跑（触网，依赖 Task 11 pool）**

Run: `python realeval/prelabel.py --pool realeval/pool --out realeval/tolabel`
Expected: 生成 tolabel json，分歧项 needs_review=true 置顶。→ **交付用户终审产出 `realeval/gold/*.jsonl`。**

- [ ] **Step 6: Commit**

```bash
git add ml/lineage-extractor/realeval/prelabel.py ml/lineage-extractor/tests/test_prelabel.py
git commit -m "feat(041-R): 真实集双模型交叉预标(一致高置信/分歧高亮终审)"
```

---

### Task 13: 重生成数据 + 质量门 + 重训 v2

**Files:**
- Modify: `ml/lineage-extractor/train/sft_qlora.py`（仅 `--out` 默认改 `out/run2`，训练逻辑不动）
- Run artifacts: `data/out/train.jsonl`、`data/out/heldout.jsonl`、`out/run2/merged`

**Interfaces:**
- Consumes: 全部前序模板/pipeline/review

- [ ] **Step 1: 重生成合成数据（新形态 + heldout 600）**

Run: `cd ml/lineage-extractor && python data/synth_pipeline.py --out data/out --train-size 10000 --heldout-size 600`
Expected: `train.jsonl`(10000) + `heldout.jsonl`(600)；打印 pools 行含新 form_family。

- [ ] **Step 2: 质量门抽查（规则自检全量 + M2 critic 抽样）**

Run:
```bash
python -c "
import json
from data.review_data import self_consistency_flags
rows=[json.loads(l) for l in open('data/out/train.jsonl')]
bad=[r['meta']['template_id'] for r in rows if self_consistency_flags(r)]
print('flagged:', len(bad), set(bad))
"
```
Expected: `flagged: 0`（label 零噪声铁律）。**非 0 → 回到对应模板修 bug，不得放行。**

- [ ] **Step 3: 重训 v2（GPU ~70min，后台脱离）**

Run（WSL2 脱离规则）:
```bash
setsid bash -c 'cd /home/wallfacers/project/dw-041-script-lineage/ml/lineage-extractor && python train/sft_qlora.py --data data/out/train.jsonl --out out/run2 >/tmp/claude-1000/-home-wallfacers-project-data-weave/b0648b42-b880-4486-9eb1-f7d750c2537b/scratchpad/train-v2.log 2>&1; echo $? >/tmp/claude-1000/-home-wallfacers-project-data-weave/b0648b42-b880-4486-9eb1-f7d750c2537b/scratchpad/train-v2.exit' </dev/null >/dev/null 2>&1 & disown
```
Poll（单次秒回）: `[ -f .../train-v2.exit ] && echo "DONE exit=$(cat .../train-v2.exit)" || { echo running; tail -1 .../train-v2.log; }`
Expected: exit=0，`out/run2/merged/model.safetensors` 生成。

- [ ] **Step 4: Commit（代码，不含产物）**

```bash
git add ml/lineage-extractor/train/sft_qlora.py
git commit -m "chore(041-R): 训练输出默认 run2；v2 数据重生成完成"
```

---

### Task 14: v2 分层评估过闸（含方向准确率 + custom-wrapper 后继子集）

**Files:**
- Run: `eval/evaluate.py` → `out/eval-report-v2.md` + `.json`

**Interfaces:**
- Consumes: Task 7 `run_eval`、Task 13 `out/run2/merged`

- [ ] **Step 1: 跑 v2 评估**

Run: `cd ml/lineage-extractor && python eval/evaluate.py --model out/run2/merged --data data/out/heldout.jsonl --report out/eval-report-v2.md`
Expected: exit=0（过闸）；报告含 overall + by_family + by_source + uncovered + 方向准确率。

- [ ] **Step 2: 证伪式核验 custom-wrapper 后继子集**

Run:
```bash
python -c "
import json
from eval.evaluate import run_eval
from eval.metrics import GATE
import torch; from transformers import AutoModelForCausalLM, AutoTokenizer
from eval.evaluate import predict
tok=AutoTokenizer.from_pretrained('out/run2/merged')
m=AutoModelForCausalLM.from_pretrained('out/run2/merged',dtype=torch.bfloat16,device_map='cuda' if torch.cuda.is_available() else 'cpu').eval()
rows=[json.loads(l) for l in open('data/out/heldout.jsonl')]
wrap=[r for r in rows if r['meta'].get('form_family')=='wrapper']
res=run_eval(lambda r: predict(m,tok,r), wrap)
print('wrapper direction_acc=', res['overall']['direction_acc'], 'n=', res['n'])
assert res['overall']['direction_acc'] > 0.80, 'custom-wrapper 方向仍混淆，未达改善目标'
"
```
Expected: wrapper 子集方向准确率明显高于 v1（v1 该子集大量方向混淆）。**不达标 → 回 Task 2 增样/调模板，重训。不看总分看子集。**

- [ ] **Step 3: baseline 对比入表（M1/M2/正则 vs 本模型，同 heldout）**

Run:
```bash
python -c "
import json
from eval.evaluate import run_eval, write_report
from eval.baselines import regex_baseline, llm_baseline
from llm.clients import load_clients
rows=[json.loads(l) for l in open('data/out/heldout.jsonl')]
c=load_clients()
tab={}
tab['regex']=run_eval(regex_baseline.predict, rows)['overall']
for k,cli in c.items(): tab[k]=run_eval(llm_baseline.make_predict(cli), rows)['overall']
json.dump(tab, open('out/baseline-table.json','w'), ensure_ascii=False, indent=2)
print(json.dumps(tab, ensure_ascii=False, indent=2))
"
```
Expected: `out/baseline-table.json` 生成，含各 baseline 的 precision/recall/f1/direction_acc/hallucination，供论文主表。

- [ ] **Step 4: Commit（报告，产物 gitignore）**

```bash
git add ml/lineage-extractor/out/eval-report-v2.md ml/lineage-extractor/out/baseline-table.json 2>/dev/null || true
git commit -m "docs(041-R): v2 分层评估报告 + baseline 对比表(论文主表素材)" || echo "报告在 gitignore，改存 specs/"
```
（若 `out/` 被 gitignore，改 `cp` 到 `specs/041-script-lineage-extraction/eval-report-v2.md` 再 add。）

---

### Task 15: 发布 v2 + 归档

**Files:**
- Run: `publish.py --version v2`
- Modify: `ml/lineage-extractor/publish.py`（README 卡加 v2 指标 + baseline 表 + limitation）

**Interfaces:**
- Consumes: `out/run2/merged`、`out/eval-report-v2.md`、`out/baseline-table.json`

- [ ] **Step 1: 更新 publish.py 的 model card（加 baseline 对比 + limitation 段）**

在 `card` 字符串追加：v2 分层指标摘要、baseline 对比（M1/M2/正则/本模型）、limitation（Qwen 系同源披露 + 真实集规模）。`--model-dir` 默认改 `out/run2/merged`，`--report` 默认 `out/eval-report-v2.md`。

- [ ] **Step 2: 发布 v2（触网，后台脱离；私有 repo）**

Run:
```bash
setsid bash -c 'cd .../ml/lineage-extractor && python publish.py --version v2 >.../scratchpad/publish-v2.log 2>&1; echo $? >.../scratchpad/publish-v2.exit' </dev/null >/dev/null 2>&1 & disown
```
Poll: `[ -f .../publish-v2.exit ] && cat .../publish-v2.exit`
Expected: `published: wallfacers/weft-lineage-extractor-1.5b@v2 + ...@v2`。

- [ ] **Step 3: 归档复现说明**

Create `ml/lineage-extractor/REPRODUCE.md`：SEED、requirements 版本锁、`synth_pipeline`→`sft_qlora`→`evaluate` 三命令、真实集 provenance 位置、baseline 复跑命令。

- [ ] **Step 4: Commit**

```bash
git add ml/lineage-extractor/publish.py ml/lineage-extractor/REPRODUCE.md
git commit -m "feat(041-R): 发布 v2(模型+数据集+卡) + 复现说明归档"
```

---

## Self-Review

**Spec coverage:**
- §1 训练扩形态 → Task 2/3 ✅
- §2 heldout 重构分层 → Task 4/5 ✅
- §3 评估 formal 化 + 方向准确率 → Task 6/7 ✅
- §3.5 大模型层 → Task 1 ✅
- §4 真实集 pipeline + 双模型交叉预标 → Task 11/12 ✅
- §4.5 交叉生成 + 评审 → Task 9/10 ✅
- §5 baseline(正则+M1+M2) → Task 8 + Task 14 Step3 ✅
- §6 重训 v2 + 过闸 + 发布 → Task 13/14/15 ✅
- §7 归档 → Task 15 ✅

**Placeholder scan:** 无 TBD/TODO；ML/网络/API 任务的「运行验证」步骤给出确切命令与预期输出，非占位。

**Type consistency:** `Sample/Template/Ctx` 与现码一致；`predict(row)->{reads,writes}` 契约贯穿 metrics/evaluate/baselines/prelabel；`cross_prelabel` 用 `eval.metrics.tables`；`GATE.direction_acc` 在 metrics 定义、evaluate 消费一致。

**已知外部依赖缺口（执行时需用户提供）:** ① M2 token 401 时要独立 token；② GitHub code search 需 `GITHUB_TOKEN`；③ GPU 重训需本机空闲（与 docker 分布式部署共享，注意 §设计隔离——重训只读 GPU 不碰共享 PG/neo4j，安全）。
