"""041-R 发布：负结果研究 artifact → 用户 HF **公开** repo。

发布物 = ①模型权重 + 诚实模型卡（负结果定位，绝不吹成生产工具）；
        ②数据集 repo：合成 train/heldout + 全部评测/泄漏/曲线报告 + findings + 裁决约定。

真实集金标（`realeval/gold/*.jsonl`）含第三方 GitHub 脚本内容，默认**不上传**；
需 `--include-real-gold` 显式开启，并自负 license 合规（collect.py 已做 license 过滤 + 脱敏）。

前置：`hf auth login` 的 token 具备 write（HF_TOKEN 仅在 gitignored .env）；repo 公开。
用法：python publish.py [--model-dir out/run1/merged] [--version v2] [--include-real-gold]
"""

from __future__ import annotations

import argparse
from pathlib import Path

from huggingface_hub import HfApi

MODEL_REPO = "wallfacers/weft-lineage-extractor-1.5b"
DATA_REPO = "wallfacers/weft-script-lineage-synth"

# ---------------------------------------------------------------------------
# 诚实模型卡（负结果 artifact）。全静态，数字为已冻结的最终实测值。
# 用 r""" 原始串，勿对本体做 .format（JSON 花括号多）。
# ---------------------------------------------------------------------------

_MODEL_CARD = r"""---
license: other
license_name: weft-research
license_link: https://github.com/wallfacers/data-weave
pipeline_tag: text-generation
base_model: Qwen/Qwen2.5-Coder-1.5B-Instruct
tags:
- research-artifact
- negative-result
- memorization
- domain-shift
- data-lineage
- etl
- lora
language:
- en
widget:
- example_title: Clean literal case (works)
  text: |
    task_type: PYTHON
    script:
    import psycopg2
    cur.execute("SELECT id, name FROM users WHERE active = 1")
    cur.execute("INSERT INTO user_summary (user_id) VALUES (%s)", rows)
---

# weft-lineage-extractor-1.5b

> ## ⚠️ This is a RESEARCH ARTIFACT documenting a NEGATIVE RESULT — not a usable lineage tool.
>
> A 1.5B model LoRA-fine-tuned **only on synthetic ETL scripts** to extract table-level data
> lineage. On its **synthetic** held-out set it looks near-perfect (**precision 0.995**). On
> **real GitHub ETL scripts it collapses** (precision **0.27**), and a large share of its
> mistakes are **verbatim table names memorized from the synthetic training pool** (**22–40%**
> of hallucinations, depending on language). **Do not deploy this for production lineage.** It
> is published so the failure — a systematic pathology of *synthetic-only training* — is
> reproducible and citable.

**What you should take away:** synthetic-benchmark scores for structured-extraction models can
be *severely* optimistic. A model can ace a held-out synthetic split by *memorizing the
generator's vocabulary*, then emit those memorized names on real, out-of-distribution inputs.

- **Base:** [Qwen/Qwen2.5-Coder-1.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct)
- **Training data:** 10,000 **synthetic** ETL scripts (Python/Shell, 9 structural forms) — no real scripts in training.
- **Companion artifacts (same study):** 0.5B / 3B scale points and a Scala/Java (JVM) variant — see *Scale & cross-language* below.

---

## The headline: synthetic looks great, real does not

Same model, table-level metrics, identical extraction convention ("Convention A": label a table
only if its literal name appears in an executable read/write statement; ignore dynamic names,
file paths, temp views, comments, config-driven jobs).

| Evaluation set | precision | direction acc. | hallucination |
|---|---|---|---|
| **Synthetic held-out** (600, structural-form isolated) | **0.995** | **0.995** | 0.001 |
| **Real GitHub ETL** (139 scripts, human gold) | **0.270** | **0.496** | 0.153 |

On real scripts the model barely beats a regex baseline and trails both a large general LLM
(Qwen-Max) and Claude, on every metric. Direction accuracy 0.496 ≈ a coin flip.

**Four-way comparison on the real Python/Shell set** (n=139, non-empty gold 59):

| extractor | precision | hallucination | recall (non-∅) | direction (non-∅) |
|---|---|---|---|---|
| **this model (sft-1.5b)** | 0.270 | 0.153 | 0.618 | 0.496 |
| Qwen-Max (general LLM) | 0.327 | 0.301 | 0.939 | 0.872 |
| Claude (general LLM) | 0.542 | 0.134 | 0.806 | 0.730 |
| regex baseline | 0.166 | 0.000 | 0.473 | 0.397 |

---

## Why it fails: memorization leak

A **hallucination** = a predicted table name that is neither in the gold nor literally present in
the script text. We check how many of these are **verbatim** names from the synthetic training
pool, or share its **shape** (`schema.schema_base_suffix`, e.g. `dws.dws_member_point_di`).

| set | hallucinations | verbatim training-pool names | synthetic-shaped |
|---|---|---|---|
| Python/Shell real | 76 | **17 (22.4%)** | 19 (25.0%) |
| JVM (Scala/Java) real | 98 | **40 (40.8%)** | 49 (50.0%) |

The model, given a real script it cannot parse, **falls back to reciting table names it saw
during training** — e.g. emitting `dws.dws_member_point_df` or `mart.mart_users_di` on an
unrelated `SqlDao.java`. This is the negative result, and it is **gold-independent**: the
verbatim-pool hits are memorized generator vocabulary, not an artifact of label quality.

### Scale & cross-language (companion points)

Same recipe, different base size (0.5B / 1.5B / 3B) and a JVM-augmented variant:

| scale | synthetic prec | real prec | real direction | **verbatim leak** |
|---|---|---|---|---|
| 0.5B | 0.994 | 0.243 | 0.369 | **37.4%** |
| 1.5B (this) | 0.995 | 0.270 | 0.496 | **22.4%** |
| 3B | 0.988 | 0.325 | 0.468 | **10.9%** |
| 1.5B + JVM, real JVM eval | ~0.99 (synth) | 0.165 | 0.418 | **40.8%** |

Two findings: **(1)** memorization leak shrinks monotonically with model size — it is a
capacity problem — but **(2)** read/write **direction confusion does not improve with scale**,
and the failure **reproduces across languages** (worse on more-OOD real JVM). "Add more synthetic
data / another language" does **not** fix the real-world gap.

---

## What actually works (use this instead)

Table lineage is best solved by **routing on the job's form**, not one model for everything:

1. **Config-driven jobs** (SeaTunnel HOCON, DataX JSON — explicit `source`/`sink` fields) → a small **rule parser**. Easiest class, no model.
2. **SQL jobs** (Flink/Spark/Hive SQL) → a **SQL parser** (e.g. Apache Calcite) for exact table/column lineage.
3. **Free-form imperative scripts** (the residual) → an LLM channel — and for *that*, a large general model (Claude / Qwen-Max) beats this synthetic-only small model on every real-world metric here.

This model's role is **evidence for point 3's caveat**, not a production extractor.

---

## Intended use

- ✅ **Reproducing / studying** the synthetic-only-training memorization-leak failure.
- ✅ A **baseline** for work on abstention, real-data augmentation, or leak mitigation in structured extraction.
- ❌ **Not** for production data-lineage, governance, or any setting where wrong lineage has consequences.

---

## Prompt format & quick start (for reproduction)

Chat template, two roles. System prompt (exact — must match training verbatim):

```
You are a data lineage extractor for ETL scripts. Given a PYTHON or SHELL task
script, output ONLY a JSON object {"reads": [...], "writes": [...]} where each
item is {"table": str, "columns": [str] or null}. Rules: include a table only if
its literal name appears in the script text; ignore dynamically-built table names,
commented-out SQL, and SQL that is merely printed or logged; if nothing is read or
written, output {"reads": [], "writes": []}.
```

```python
import json, re, torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL = "wallfacers/weft-lineage-extractor-1.5b"
tok = AutoTokenizer.from_pretrained(MODEL)
model = AutoModelForCausalLM.from_pretrained(MODEL, torch_dtype=torch.bfloat16, device_map="auto").eval()

SYSTEM = ("You are a data lineage extractor for ETL scripts. Given a PYTHON or SHELL task "
          "script, output ONLY a JSON object {\"reads\": [...], \"writes\": [...]} where each "
          "item is {\"table\": str, \"columns\": [str] or null}. Rules: include a table only if "
          "its literal name appears in the script text; ignore dynamically-built table names, "
          "commented-out SQL, and SQL that is merely printed or logged; if nothing is read or "
          "written, output {\"reads\": [], \"writes\": []}.")

def extract(task_type, script, max_new_tokens=256):
    msgs = [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"task_type: {task_type}\nscript:\n{script}"}]
    inp = tok.apply_chat_template(msgs, add_generation_prompt=True, tokenize=True,
                                  return_dict=True, return_tensors="pt").to(model.device)
    with torch.no_grad():
        out = model.generate(**inp, max_new_tokens=max_new_tokens, do_sample=False,
                             pad_token_id=tok.pad_token_id or tok.eos_token_id)
    raw = tok.decode(out[0][inp["input_ids"].shape[1]:], skip_special_tokens=True).strip()
    m = re.search(r"\{.*\}", raw, re.DOTALL)
    return json.loads(m.group(0)) if m else {"reads": [], "writes": []}

# Works on a clean literal case; recites memorized names on hard real scripts (that's the point):
print(extract("PYTHON", 'cur.execute("SELECT * FROM orders WHERE status = \'pending\'")'))
# -> {"reads": [{"table": "orders", "columns": null}], "writes": []}
```

Decoding is deterministic (`do_sample=False`): same input → same output.

---

## Training

| Parameter | Value |
|---|---|
| Base model | Qwen/Qwen2.5-Coder-1.5B-Instruct |
| Method | LoRA (r=16, α=32, dropout=0.05; q/k/v/o/gate/up/down_proj) |
| Epochs / LR | 2 / 2e-4 cosine, 3% warmup |
| Effective batch / max len | 16 (2×8 grad-accum) / 2048 |
| Precision / hardware | bfloat16 / single 12 GB GPU |
| Training data | 10,000 **synthetic** ETL scripts (9 structural forms) — **zero real scripts** |
| Seed | 20260703 (reproducible) |

The synthetic generator draws table names from a fixed pool; the memorization leak above is that
pool resurfacing on real inputs.

---

## Limitations & honest disclosures

- **Not a production tool** (see above). Real-world precision ~0.27; direction ~coin-flip.
- **Literal-only by design:** dynamic names (f-strings, shell vars, `format()`), commented/logged SQL, temp views, and config-driven jobs are intentionally out of scope.
- **Evaluation gold** is human-adjudicated under Convention A; the real sets are small (Python/Shell n=139 / non-empty 59; JVM n=141 / non-empty 28). The headline **leak metric is gold-independent** and stable to full-file re-adjudication (verbatim 40.4%→40.8% on JVM).
- **Column-level** output exists in the schema but is best-effort; evaluated claims are **table-level**.

---

## Links & citation

- **Dataset (synthetic + eval/leak reports):** [wallfacers/weft-script-lineage-synth](https://huggingface.co/datasets/wallfacers/weft-script-lineage-synth)
- **Platform:** [Weft (data-weave)](https://github.com/wallfacers/data-weave)
- **Base model:** [Qwen/Qwen2.5-Coder-1.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct)

```bibtex
@misc{weft-lineage-negresult-2026,
  author       = {{Weft Contributors}},
  title        = {{Synthetic-only training induces memorization leak in small
                   models for ETL data-lineage extraction: a negative result}},
  year         = 2026,
  publisher    = {{Hugging Face}},
  howpublished = {{\url{https://huggingface.co/wallfacers/weft-lineage-extractor-1.5b}}},
}
```

Trained with [TRL](https://huggingface.co/docs/trl) + [PEFT](https://huggingface.co/docs/peft).
"""

# 数据集 repo 的 README（同样诚实定位）。
_DATASET_CARD = r"""---
license: other
license_name: weft-research
pretty_name: Weft Script-Lineage (synthetic + negative-result evidence)
tags:
- data-lineage
- etl
- synthetic
- negative-result
language:
- en
---

# weft-script-lineage — synthetic training data + negative-result evidence

Companion data for the model [wallfacers/weft-lineage-extractor-1.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-1.5b),
a **research artifact** demonstrating that synthetic-only training induces a **memorization
leak** in small models for ETL table-lineage extraction.

## Contents

- `train.jsonl` / `heldout.jsonl` — 10,000 + 600 **synthetic** ETL scripts with table-lineage
  labels (Python/Shell). `out-jvm/` adds the Scala/Java-augmented variant.
- `reports/` — the frozen evaluation evidence:
  - `eval-report-v2.md` — synthetic held-out (near-perfect, the misleading number).
  - `eval-real.md` / `eval-real-jvm.md` — four-way comparison on **real** GitHub scripts.
  - `leak-report.md` / `leak-report-jvm.md` — memorization-leak quantification.
  - `leak-curve.md` — leak vs. model scale (0.5B/1.5B/3B).
  - `paper-negative-result-findings.md` — the full write-up.
  - `ADJUDICATION.md` — the human-gold labeling convention (Convention A) + provenance.

## Note on real scripts

The **real-evaluation gold** (`realeval/gold/*.jsonl`) contains third-party GitHub script
content and is **not** included here by default (license/redistribution care). It is uploaded
only when the publisher passes `--include-real-gold` after license review.
"""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", default="out/run1/merged")
    ap.add_argument("--data-dir", default="data/out")
    ap.add_argument("--version", default="v2")
    ap.add_argument("--include-real-gold", action="store_true",
                    help="额外上传真实集金标（含第三方脚本，需自负 license 合规）")
    ap.add_argument("--dry-run", action="store_true", help="只生成卡片到本地，不联网上传")
    args = ap.parse_args()

    root = Path(__file__).parent

    # 报告清单：合成 + 真实 + 泄漏 + 曲线 + findings + 裁决约定
    report_files = [
        root / "out" / "eval-report-v2.md",
        root / "out" / "eval-real.md",
        root / "out" / "eval-real-jvm.md",
        root / "out" / "leak-report.md",
        root / "out" / "leak-report-jvm.md",
        root / "out" / "leak-curve.md",
        root / "realeval" / "ADJUDICATION.md",
    ]
    findings = (root / ".." / ".." / "specs" / "041-script-lineage-extraction"
                / "paper-negative-result-findings.md").resolve()

    # 本地落卡（模型 dir 的 README + 数据集卡草稿）
    Path(args.model_dir, "README.md").write_text(_MODEL_CARD, encoding="utf-8")
    (root / "out" / "DATASET_CARD.md").write_text(_DATASET_CARD, encoding="utf-8")
    print(f"[card] 模型卡 → {args.model_dir}/README.md（诚实负结果定位）")
    print(f"[card] 数据集卡 → out/DATASET_CARD.md")

    if args.dry_run:
        print("[dry-run] 未联网上传。")
        return

    api = HfApi()
    api.create_repo(MODEL_REPO, repo_type="model", private=False, exist_ok=True)
    api.create_repo(DATA_REPO, repo_type="dataset", private=False, exist_ok=True)
    # create_repo(exist_ok=True) 不改已存在 repo 的可见性 → 显式强制公开。
    for rid, rt in ((MODEL_REPO, "model"), (DATA_REPO, "dataset")):
        api.update_repo_settings(repo_id=rid, repo_type=rt, private=False)

    # ① 模型权重 + 卡
    api.upload_folder(folder_path=args.model_dir, repo_id=MODEL_REPO, repo_type="model",
                      commit_message=f"041-R {args.version}: weights + honest negative-result card")
    api.create_tag(MODEL_REPO, tag=args.version, repo_type="model", exist_ok=True)

    # ② 数据集：合成 jsonl + 报告证据 + 数据集卡
    api.upload_file(path_or_fileobj=str(root / "out" / "DATASET_CARD.md"),
                    path_in_repo="README.md", repo_id=DATA_REPO, repo_type="dataset")
    api.upload_folder(folder_path=str(root / "data" / "out"), repo_id=DATA_REPO,
                      repo_type="dataset", allow_patterns=["*.jsonl"],
                      commit_message=f"041-R {args.version}: synthetic train/heldout")
    jvm_dir = root / "data" / "out-jvm"
    if jvm_dir.exists():
        api.upload_folder(folder_path=str(jvm_dir), repo_id=DATA_REPO, repo_type="dataset",
                          path_in_repo="out-jvm", allow_patterns=["*.jsonl"],
                          commit_message=f"041-R {args.version}: JVM-augmented synthetic")
    for rf in report_files + [findings]:
        if rf.exists():
            api.upload_file(path_or_fileobj=str(rf), path_in_repo=f"reports/{rf.name}",
                            repo_id=DATA_REPO, repo_type="dataset")

    if args.include_real_gold:
        gold = root / "realeval" / "gold"
        if gold.exists():
            print("[warn] 上传真实集金标（含第三方脚本）——确认已过 license 审查。")
            api.upload_folder(folder_path=str(gold), repo_id=DATA_REPO, repo_type="dataset",
                              path_in_repo="realeval-gold", allow_patterns=["*.jsonl"],
                              commit_message=f"041-R {args.version}: real gold (license-reviewed)")

    api.create_tag(DATA_REPO, tag=args.version, repo_type="dataset", exist_ok=True)
    print(f"published (public): {MODEL_REPO}@{args.version} + {DATA_REPO}@{args.version}")


if __name__ == "__main__":
    main()
