---
license: other
license_name: weft-research
license_link: https://github.com/wallfacers/data-weave
pipeline_tag: text-generation
library_name: transformers
base_model: Qwen/Qwen2.5-Coder-14B-Instruct
tags:
- data-lineage
- etl
- sql
- code
- lora
- research-artifact
language:
- en
datasets:
- wallfacers/weft-script-lineage-synth
---

# weft-lineage-extractor-14b

**The strongest single model in the Weft lineage-extractor family.** A 14B code model
(LoRA fine-tuned, merged) that reads an ETL script (Python / Shell / SQL / Spark) and emits
**table-level and column-level data lineage** as structured JSON. Trained on **1,154
real-world GitHub ETL scripts** with tri-vendor consensus silver labels — not on synthetic
templates — and evaluated on a held-out real-world benchmark.

This repository ships **three branches** covering the table↔column trade-off frontier:

| Branch | Variant | Table P / R / F1 | Column P / R / F1 | Direction | Positioning |
|---|---|---|---|---|---|
| **`main`** | loss-weighted W=3 (`lw3`) | 0.887 / 0.727 / **0.799** | 0.936 / 0.789 / **0.856** | 0.727 | **Best balanced single model** (best in family, any size) |
| `tri-table-specialist` | loss-weighted W=4 (`lw4`) | 0.896 / **0.761** / **0.823** | 0.925 / 0.660 / 0.771 | 0.759 | Best table recall / table F1 |
| `tri-column-specialist` | plain full-column | 0.880 / 0.679 / 0.766 | 0.923 / 0.919 / **0.921** | 0.677 | Best column F1 |

*Benchmark: 129 non-empty real GitHub scripts, tri-vendor consensus gold, greedy decoding,
`max_new_tokens=512`. Column metrics are conditional on matched tables (family convention).*

---

## Why this model exists (honest research context)

This family is a **negative-result research line turned usable**. Key findings, all
reproducible from the [evidence ledger](https://github.com/wallfacers/data-weave):

1. **Synthetic benchmarks are dangerously misleading.** A 14B trained on 10,000 *synthetic*
   scripts scores **0.996 table F1 on the synthetic held-out set** — and collapses to
   **0.505 on real scripts** (recall 0.36). Same base, same recipe. The 0.996 is *the
   misleading number*; never trust synthetic-only scores for structured extraction.
2. **Data beats scale.** Swapping the training set to 1,154 *real* silver-labeled scripts
   (9× less data) lifts real-world table F1 from 0.505 to **0.766–0.823**.
3. **Scale is non-linear.** 7B ≈ 3B on this task (a "capacity valley"); the scale dividend
   only materializes at 14B. See
   [weft-lineage-extractor-7b](https://huggingface.co/wallfacers/weft-lineage-extractor-7b).
4. **The table↔column Pareto frontier survives scale.** Sweeping the table-token loss weight
   (W = 1 / 3 / 4) traces a clean monotonic frontier — table R 0.679 → 0.727 → 0.761 vs
   column F1 0.921 → 0.856 → 0.771 (≈2.5 pt column F1 per 1 pt table R). **No single model
   passes the strict dual gate** (table R ≥ 0.75 **and** column F1 ≥ 0.85); `main` misses it
   by 0.023 table R. Scale pushes the frontier outward but does not remove it. The
   engineering answer is inference-time **dual-expert fusion** (`tri-table-specialist`
   defines the table set, `tri-column-specialist` grafts the columns).
5. **Loss weighting needs capacity.** The same W=3 that lifts table R by +0.048 on 14B moves
   7B by only +0.004. The lever only works on models big enough to use it.

## Label credibility

Gold and silver labels are **2-of-3 consensus across three independent vendors**
(qwen-max ∩ deepseek-v4-pro ∩ GPT-5.6). GPT-5.6 — a third party that never participated in
constructing the earlier two-vendor labels — independently agrees with them at **0.976
(table) / 0.958 (column)**, and models trained only on the two-vendor subset generalize at
0.782 table recall to edges only GPT-5.6 confirms. This lowers (but does not eliminate) the
teacher-circularity concern: labels remain LLM-derived silver, with no human gold.

## Training details

| | |
|---|---|
| Base model | [Qwen/Qwen2.5-Coder-14B-Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct) |
| Method | LoRA r=32, α=64, bf16 (merged into this checkpoint) |
| Data | 1,154 real GitHub ETL scripts, tri-vendor consensus silver labels (tables + columns) |
| Schedule | 3 epochs (219 steps), effective batch 16, lr 2e-4 cosine, max seq 2048 |
| Branch deltas | `main`: answer table-token loss ×3 · `tri-table-specialist`: ×4 · `tri-column-specialist`: ×1 |
| Hardware | 1× RTX 6000D 84 GB, ~65 min per variant |

## Usage

The model expects the family system prompt and returns a single JSON object.

```python
from transformers import AutoModelForCausalLM, AutoTokenizer

REPO, REV = "wallfacers/weft-lineage-extractor-14b", "main"   # or a specialist branch
tok = AutoTokenizer.from_pretrained(REPO, revision=REV)
model = AutoModelForCausalLM.from_pretrained(REPO, revision=REV,
                                             dtype="bfloat16", device_map="cuda")

SYSTEM_PROMPT = (
    'You are a data lineage extractor for ETL scripts. Given a PYTHON, SHELL, SCALA or '
    'JAVA task script (Spark/Flink jobs included), output ONLY a JSON object '
    '{"reads": [...], "writes": [...]} where each '
    'item is {"table": str, "columns": [str] or null}. Rules: include a table only if '
    'its literal name appears in the script text; ignore dynamically-built table names, '
    'commented-out SQL, and SQL that is merely printed or logged; if nothing is read or '
    'written, output {"reads": [], "writes": []}.'
)

msgs = [{"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "task_type: PYTHON\nscript:\n" + script}]
ids = tok.apply_chat_template(msgs, add_generation_prompt=True, return_tensors="pt").to(model.device)
out = model.generate(ids, max_new_tokens=512, do_sample=False)
print(tok.decode(out[0, ids.shape[1]:], skip_special_tokens=True))
```

High-throughput serving (OpenAI-compatible, continuous batching):

```bash
pip install vllm
vllm serve wallfacers/weft-lineage-extractor-14b --revision main \
  --dtype bfloat16 --max-model-len 2048 --gpu-memory-utilization 0.9 --port 8000
```

**Output schema**: `{"reads": [{"table": str, "columns": [str] | null}], "writes": [...]}`.
`columns: null` means the model abstains on columns for that table.

**Extraction convention ("Convention A")**: a table is labeled only when its literal name
appears in an executable read/write statement. Dynamically-built table names, commented-out
SQL, printed/logged SQL, and temp views are intentionally out of scope.

## Intended use & limitations

- **Intended**: data-platform lineage bootstrapping and governance review queues — pair
  automatic adoption (high-precision layer) with human review of the rest. Precision is the
  strong axis (table P 0.89, column P 0.93, hallucination ≤ 0.02).
- **Not intended**: fully-automatic lineage without review (table recall 0.68–0.76 means
  real misses); non-ETL code; scripts whose lineage is entirely config- or variable-driven
  (out of scope by convention).
- Labels are LLM-consensus silver, not human gold; metrics inherit that ceiling.

## Model family

| Model | Role |
|---|---|
| [weft-lineage-extractor-14b](https://huggingface.co/wallfacers/weft-lineage-extractor-14b) | **this repo — best single models** |
| [weft-lineage-extractor-7b](https://huggingface.co/wallfacers/weft-lineage-extractor-7b) | scale-curve point (capacity-valley negative result) |
| [weft-lineage-extractor-3b](https://huggingface.co/wallfacers/weft-lineage-extractor-3b) | efficient family (12 GB-GPU deployable), 3 branches |
| [weft-lineage-extractor-1.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-1.5b) | synthetic-only negative-result artifact |
| [weft-lineage-extractor-0.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-0.5b) | scale-curve point (synthetic-only) |
| [weft-lineage-extractor-jvm-1.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-jvm-1.5b) | cross-language (Scala/Java) negative result |
| [weft-script-lineage-synth](https://huggingface.co/datasets/wallfacers/weft-script-lineage-synth) | synthetic corpus + evidence reports |

Full experiment ledger, metrics scripts and deployment cost analysis:
[github.com/wallfacers/data-weave](https://github.com/wallfacers/data-weave)
(`ml/lineage-extractor/out/FINDINGS-14b-cloud.md`, `PAPER-EVIDENCE-068.md`).
