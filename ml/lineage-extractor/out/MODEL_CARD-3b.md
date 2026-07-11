---
license: other
license_name: weft-research
license_link: https://github.com/wallfacers/data-weave
pipeline_tag: text-generation
library_name: transformers
base_model: Qwen/Qwen2.5-Coder-3B-Instruct
datasets:
- bigcode/the-stack-dedup
language:
- en
tags:
- data-lineage
- etl
- code
- information-extraction
- lora
- text2json
- weft
model-index:
- name: weft-lineage-extractor-3b
  results:
  - task:
      type: table-level-lineage-extraction
      name: ETL table-level data-lineage extraction
    dataset:
      type: real-github-etl
      name: gold C (real GitHub ETL, held-out, source-isolated)
    metrics:
    - type: precision
      value: 0.642
      name: Table precision (all, calibrated gold + grounding filter)
    - type: precision
      value: 0.742
      name: Table precision (non-empty scripts)
    - type: precision
      value: 0.457
      name: Table precision (all, raw teacher gold, no filter)
    - type: recall
      value: 0.633
      name: Table recall (all)
widget:
- example_title: Literal SQL read/write
  text: |
    task_type: PYTHON
    script:
    spark.sql("INSERT INTO ods.users SELECT id, name FROM stg.users_raw")
---

# weft-lineage-extractor-3b

**Self-hostable 3B model that extracts table-level data lineage (reads/writes) from ETL
scripts as JSON.** Fine-tuned (LoRA) on **real** open-source ETL scripts. On a held-out,
source-isolated real-world benchmark it reaches **table precision 0.64** (all scripts,
calibrated gold + grounding filter) / **0.74** on non-empty scripts — matching a single
pass of a frontier LLM teacher, at a size you can run on a single 12 GB GPU.

> This model **resolves** the negative result documented by its synthetic-trained siblings
> ([0.5B](https://huggingface.co/wallfacers/weft-lineage-extractor-0.5b) /
> [1.5B](https://huggingface.co/wallfacers/weft-lineage-extractor-1.5b) /
> [JVM 1.5B](https://huggingface.co/wallfacers/weft-lineage-extractor-jvm-1.5b)): training on
> **real** scripts instead of synthetic ones eliminates the memorization leak and lifts
> real-world precision from **0.33 → 0.64**.

- **Base:** [Qwen/Qwen2.5-Coder-3B-Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct)
- **Method:** LoRA SFT on ~1.8k real silver-labelled ETL scripts (Python/Shell/SQL).
- **Task:** given a script, output `{"reads": [...], "writes": [...]}`; abstain (`[]`) when there is no lineage.

---

## Intended uses & limitations

**Intended use**
- ✅ Self-hosted, cost-free table-level lineage for imperative/SQL-embedding ETL scripts, as the LLM channel of a routed lineage system (rule parsers for config jobs, a SQL parser for pure SQL, this model for free-form scripts).
- ✅ A precision-first extractor: pair it with the **grounding filter** below to enforce the task rule "a table counts only if its literal name appears in the script".

**Out of scope**
- ❌ Column-level lineage (schema field exists but is best-effort; evaluated claims are table-level).
- ❌ Dynamically-built table names (f-strings, shell/notebook vars, `format()`), commented/logged SQL, temp views — intentionally excluded.
- ❌ Ground-truth-critical governance without human review on ambiguous cases (see *Limitations*).

---

## Evaluation

**Benchmark — gold C:** 153 real GitHub ETL scripts (49 non-empty / 104 with no lineage),
human-relevant "Convention A" labels, **source-isolated** from the training corpus (training =
`the-stack`, benchmark = fresh GitHub) with content-hash de-contamination. Table-level metrics.

| Yardstick | ALL precision | non-empty precision | ALL recall | direction |
|---|---|---|---|---|
| Raw teacher gold, no filter | 0.457 | 0.745 | 0.658 | 0.642 |
| **Calibrated gold + grounding filter** | **0.642** | **0.742** | 0.633 | 0.633 |

- **Calibrated gold**: the raw teacher labels missed real lineage on 12 hard scripts (e.g. Databricks notebooks); a strongest-teacher (frontier LLM) blind re-adjudication flipped those false-empties. This is a *yardstick* correction, transparently reported alongside the raw number.
- **Grounding filter** (deterministic, ships in the recipe): drop any predicted table whose leaf name is not literally in the script, or that contains dynamic markers `$ { } %`. Enforces the task rule; +2 precision points at zero recall cost.

**How it compares to its synthetic-trained siblings (same real benchmark family):**

| model | training data | real ALL precision | real non-empty precision | verbatim memorization leak |
|---|---|---|---|---|
| 1.5B / 3B synthetic (041 study) | synthetic only | 0.27 / 0.33 | ~0.61 | 22% / 11% |
| **this model (3B, real corpus)** | **real the-stack** | **0.46 → 0.64** | **0.74** | **~0** |

---

## Recall & the tiered review envelope (deployment)

This model is tuned for **precision** — the deployment-critical axis for lineage governance —
which costs recall: on the non-empty benchmark it covers **~0.70** of the true tables on its
own. Frontier LLM teachers reach 0.77–0.81 by guessing dynamic / framework / config-driven
table names this model is deliberately conservative about.

The platform recovers recall **without paying for a teacher or retraining**, by pairing the
model with a deterministic **SQL-AST channel** (Apache Calcite) and shipping a **tiered review
envelope**:

- **Auto-accept tier** (`reads` / `writes`) — candidates whose calibrated confidence clears a
  governance precision bar; safe to ingest into a lineage store automatically.
- **Review tier** (`reviewReads` / `reviewWrites`) — the rest of the model∪SQL-AST union,
  surfaced to a human review queue ordered by confidence. Nothing is silently dropped.

Pooled table coverage on the non-empty benchmark (gold C):

| stage | coverage (recall) |
|---|---|
| model alone (grounded) | 0.703 |
| **model ∪ SQL-AST (free ceiling → review queue)** | **0.764** |
| frontier LLM teachers | 0.77–0.81 |

The free, deterministic ceiling is **0.764** — below the teacher band. The extra teacher recall
comes from table names no deterministic channel can see, and is honestly out of reach without
paid inference or retraining. So recall recovery targets **0.764 into a review queue**, not the
teachers' 0.81.

### Confidence tiers are calibrated honestly — a cautionary result

Each candidate is scored by *channel × name-qualification* (`agree`, `sql_qual`, `model_bare`,
…) and the auto-accept set is chosen by **nested cross-validation** on the benchmark. No
independent held-out calibration set exists — the natural candidates were either deleted,
turned out identical to the benchmark, or were seen in training — so CV de-bias is the honest
substitute. It exposes a sobering fact:

- The in-sample cumulative precision of the top tiers (**0.92**) **does not generalize** —
  held-out it is **0.79**.
- To hold **held-out precision ≥ 0.95**, only the `sql_qual` tier qualifies (7 tables,
  recall ≈ 0.05). Any governance bar ≥ 0.90 leaves the auto-accept tier tiny.
- The **real knee is at ≈ 0.85** (held-out precision 0.87, recall 0.72).

**Takeaway:** at strict governance thresholds the auto-ingest tier is *small but safe*; the
recall recovery lives almost entirely in the **human-review tier**. Sample-in confidence
numbers for this kind of extraction are optimistic — always CV-debias before trusting a
governance threshold.

The tiered envelope, together with a context-aware **semantic grounding filter** (which lifts
ALL-precision **0.642 → 0.684** at zero recall cost), ships in the platform's serving sidecar
(env-configurable governance threshold, one-flag rollback). See the platform link below.

---

## How to use

```python
import json, re, torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL = "wallfacers/weft-lineage-extractor-3b"
tok = AutoTokenizer.from_pretrained(MODEL)
model = AutoModelForCausalLM.from_pretrained(MODEL, torch_dtype=torch.bfloat16, device_map="auto").eval()

# System prompt — must match training verbatim.
SYSTEM = ("You are a data lineage extractor for ETL scripts. Given a PYTHON, SHELL, SCALA or "
          "JAVA task script (Spark/Flink jobs included), output ONLY a JSON object "
          "{\"reads\": [...], \"writes\": [...]} where each item is {\"table\": str, "
          "\"columns\": [str] or null}. Rules: include a table only if its literal name appears "
          "in the script text; ignore dynamically-built table names, commented-out SQL, and SQL "
          "that is merely printed or logged; if nothing is read or written, output "
          "{\"reads\": [], \"writes\": []}.")

def _ground(tables, script):
    """Deterministic grounding filter: keep a table only if its leaf name is literally
    in the script and it carries no dynamic markers. Enforces the task rule; lifts precision."""
    low = script.lower()
    out = []
    for it in tables or []:
        t = (it.get("table") or "").strip()
        leaf = t.lower().split(".")[-1]
        if leaf and leaf in low and not any(c in t for c in "${}%"):
            out.append(it)
    return out

def extract(task_type, script, max_new_tokens=512, ground=True):
    msgs = [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"task_type: {task_type}\nscript:\n{script}"}]
    inp = tok.apply_chat_template(msgs, add_generation_prompt=True, tokenize=True,
                                  return_dict=True, return_tensors="pt").to(model.device)
    with torch.no_grad():
        out = model.generate(**inp, max_new_tokens=max_new_tokens, do_sample=False,
                             pad_token_id=tok.pad_token_id or tok.eos_token_id)
    raw = tok.decode(out[0][inp["input_ids"].shape[1]:], skip_special_tokens=True).strip()
    m = re.search(r"\{.*\}", raw, re.DOTALL)
    obj = json.loads(m.group(0)) if m else {"reads": [], "writes": []}
    r, w = obj.get("reads") or [], obj.get("writes") or []
    if ground:
        r, w = _ground(r, script), _ground(w, script)
    return {"reads": r, "writes": w}

print(extract("PYTHON", 'spark.sql("INSERT INTO ods.users SELECT id FROM stg.users_raw")'))
# -> {"reads": [{"table": "stg.users_raw", ...}], "writes": [{"table": "ods.users", ...}]}
```

Decoding is deterministic (`do_sample=False`): same input → same output. The **grounding
filter is part of the recommended inference recipe** — the reported 0.64 precision is with it on.

`task_type` is one of `PYTHON | SHELL | SCALA | JAVA` (SQL scripts pass as their host language).

---

## Training

| Parameter | Value |
|---|---|
| Base model | Qwen/Qwen2.5-Coder-3B-Instruct |
| Method | LoRA (bf16), merged weights published |
| Training data | ~1,774 **real** ETL scripts (887 with lineage + 887 no-lineage), silver-labelled |
| Silver labels | cross-vendor agreement of two LLM teachers (deepseek-v4-flash ∩ qwen-max), rejection gate for dynamic/path/temp names, **zero synthetic names** |
| Corpus source | [bigcode/the-stack-dedup](https://huggingface.co/datasets/bigcode/the-stack-dedup) (permissive licenses), ETL-idiom filtered |
| Epochs / max len | 2 / 1024 |
| Precision / hardware | bfloat16 / single 12 GB GPU |
| Decontamination | content-hash exclusion of the benchmark from training |

**Why real data matters:** the synthetic-trained siblings memorize the generator's table
vocabulary and recite it on real inputs (22–40% of their hallucinations are verbatim training
names). Training on real scripts with a zero-synthetic-name silver pipeline removes that leak
by construction and is the single biggest driver of the precision jump.

---

## Limitations & honest disclosures

- **Precision/recall trade-off:** more no-lineage training examples make this model more
  conservative — recall on the real benchmark is ~0.63 (vs ~0.68 for a smaller-corpus variant).
  It is tuned for **precision** (the deployment-critical axis for lineage governance).
- **Yardstick honesty:** the headline 0.64 uses a teacher-calibrated gold + the grounding filter.
  The raw, unfiltered number against the original teacher gold is **0.457**; both are reported.
- **Label-ambiguity ceiling:** even the strongest teacher cannot cleanly decide whether a dotted
  token is a table, a file, or a variable on some real scripts. Pushing precision materially past
  this point requires human gold labels.
- **Teachers:** silver labels come from LLM teachers (deepseek / qwen); the model matches those
  teachers' agreed convention, disclosed rather than claimed as human ground truth.
- **Small benchmark:** gold C is 153 scripts (49 non-empty). Treat absolute numbers as indicative.

---

## Links & citation

- **Study of the negative result it resolves:** [weft-lineage-extractor-1.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-1.5b)
- **Platform:** [Weft (data-weave)](https://github.com/wallfacers/data-weave)
- **Base model:** [Qwen/Qwen2.5-Coder-3B-Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct)

```bibtex
@misc{weft-lineage-extractor-3b-2026,
  author       = {{Weft Contributors}},
  title        = {{Real-corpus training closes the synthetic-to-real gap for small-model
                   ETL data-lineage extraction}},
  year         = 2026,
  publisher    = {{Hugging Face}},
  howpublished = {{\url{https://huggingface.co/wallfacers/weft-lineage-extractor-3b}}},
}
```

Trained with [TRL](https://huggingface.co/docs/trl) + [PEFT](https://huggingface.co/docs/peft).
