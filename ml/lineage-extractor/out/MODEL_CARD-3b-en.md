---
license: other
license_name: weft-research
license_link: https://github.com/wallfacers/data-weave
pipeline_tag: text-generation
library_name: transformers
base_model: Qwen/Qwen2.5-Coder-3B-Instruct
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

# weft-lineage-extractor-3b

The **efficient tier** of the Weft lineage-extractor family: a 3B code model (LoRA
fine-tuned, merged) that extracts **table- and column-level data lineage** from ETL scripts
as structured JSON. Deployable on a single 12 GB consumer GPU. Trained on real-world GitHub
ETL scripts with tri-vendor consensus silver labels.

This repository ships **three branches** covering the table↔column trade-off frontier:

| Branch | Variant | Table P / R / F1 | Column P / R / F1 | Positioning |
|---|---|---|---|---|
| **`main`** | loss-weighted W=3 (`run-tri-3b-lw3`) | 0.834 / 0.734 / **0.781** | 0.910 / 0.755 / **0.825** | Best balanced 3B single model |
| `tri-column-specialist` | plain full-column (`run-tri-3b`) | 0.822 / 0.645 / 0.723 | 0.914 / 0.949 / **0.931** | Best column F1 |
| `tri-table-specialist` | 31% column density (`run-tri-3b-col50`) | 0.825 / **0.776** / 0.800 | 0.912 / 0.423 / 0.578 | Best table recall |

*Benchmark: 129 non-empty real GitHub scripts, tri-vendor consensus gold, greedy decoding at
1024 max new tokens. Column metrics are conditional on matched tables.*

**Which branch?** One model for both tables and columns → `main`. Maximum column quality
(pair it with a table specialist via inference-time fusion) → `tri-column-specialist`.
Maximum table recall → `tri-table-specialist`.

## Method highlight: table-token loss weighting

The 3B table↔column frontier is driven by **gradient imbalance, not just capacity**: in the
answer JSON, high-entropy column tokens outnumber table tokens **4.39 : 1**, drowning the
table-name gradient. Re-weighting the loss on table-structure tokens (W=3) lifts table recall
0.645 → **0.734** with no data removal and no extra capacity, at table-precision parity
(0.834, McNemar n.s.). Loss weighting **strictly dominates column-density dilution**: at
equal table recall it keeps ~**+0.15 column F1** that dilution would destroy.

## Label credibility (tri-vendor consensus)

Gold and silver labels are **2-of-3 consensus across three independent vendors**
(qwen-max ∩ deepseek-v4-pro ∩ GPT-5.6). GPT-5.6, which never participated in constructing
the earlier two-vendor labels, independently agrees with them at **0.976 (table) / 0.958
(column)**; models trained only on the two-vendor subset reach 0.782 table recall on edges
only GPT-5.6 confirms — evidence the model learns real lineage, not one vendor's labeling
habits. Total teacher-labeling cost: **$2.42**.

## Honest boundaries

- **Reduced, not eliminated, circularity**: labels remain LLM-consensus silver; no human gold.
- **Governance routing**: 3-of-3 vendor-unanimous cases (~70%) are candidates for an
  auto-adopt layer; vendor-disagreement cases (~30%) route to human review. The model narrows
  the review queue; it does not eliminate review.
- **Convention A exclusions** (dynamically-built table names, commented-out/printed SQL, temp
  views) are deliberate scope boundaries of static extraction, not bugs.
- **The strict dual gate (table R ≥ 0.75 and column F1 ≥ 0.85) is unreachable at 3B** — shown
  twice independently (loss-weight sweep; r=64 capacity stack). It remains unreachable by any
  *single* model even at 14B; the quality path is
  [weft-lineage-extractor-14b](https://huggingface.co/wallfacers/weft-lineage-extractor-14b)
  (best balanced 0.799 / 0.856) plus inference-time dual-expert fusion.

## Training details

| | |
|---|---|
| Base model | [Qwen/Qwen2.5-Coder-3B-Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct) |
| Method | LoRA r=32, α=64, bf16 (merged into this checkpoint) |
| Data | 1,154 real GitHub ETL scripts, tri-vendor consensus silver labels (tables + columns) |
| Branch deltas | `main`: answer table-token loss ×3 · `tri-table-specialist`: 31% column density · `tri-column-specialist`: full columns |
| Schedule | 3 epochs, effective batch 16, lr 2e-4 cosine, max seq 2048 |

## Usage

### vLLM (recommended for batch extraction — continuous batching, OpenAI-compatible)

```bash
pip install vllm
vllm serve wallfacers/weft-lineage-extractor-3b --revision main \
  --dtype bfloat16 --max-model-len 2048 --gpu-memory-utilization 0.9 --port 8000
```

```python
from vllm import LLM, SamplingParams
llm = LLM(model="wallfacers/weft-lineage-extractor-3b", revision="main",
          dtype="bfloat16", max_model_len=2048)
sp = SamplingParams(temperature=0.0, max_tokens=256)      # deterministic decoding
outs = llm.chat([[{"role": "system", "content": SYSTEM_PROMPT},
                  {"role": "user", "content": "task_type: PYTHON\nscript:\n" + script}]], sp)
print(outs[0].outputs[0].text)
```

### transformers (single request / interactive, p50 ≈ 360 ms)

```python
from transformers import AutoModelForCausalLM, AutoTokenizer
REPO, REV = "wallfacers/weft-lineage-extractor-3b", "main"
tok = AutoTokenizer.from_pretrained(REPO, revision=REV)
model = AutoModelForCausalLM.from_pretrained(REPO, revision=REV,
                                             dtype="bfloat16", device_map="cuda")
```

System prompt (shared across the family):

```text
You are a data lineage extractor for ETL scripts. Given a PYTHON, SHELL, SCALA or JAVA task
script (Spark/Flink jobs included), output ONLY a JSON object {"reads": [...], "writes": [...]}
where each item is {"table": str, "columns": [str] or null}. Rules: include a table only if
its literal name appears in the script text; ignore dynamically-built table names,
commented-out SQL, and SQL that is merely printed or logged; if nothing is read or written,
output {"reads": [], "writes": []}.
```

**Output schema**: `{"reads": [{"table": str, "columns": [str] | null}], "writes": [...]}`.

**Cost note**: lineage answers are short (~22 output tokens/request, prefill-bound). Self-hosted
on a single consumer GPU the marginal cost approaches electricity (≈$0.004 / 1k requests) —
1–2 orders of magnitude below cloud LLM APIs for high-volume batch extraction. Full
throughput/cost ledger: `out/cost-analysis-068.md` in the GitHub repo.

## Model family

| Model | Role |
|---|---|
| [weft-lineage-extractor-14b](https://huggingface.co/wallfacers/weft-lineage-extractor-14b) | best single models (3 branches) |
| [weft-lineage-extractor-7b](https://huggingface.co/wallfacers/weft-lineage-extractor-7b) | scale-curve point (capacity-valley negative result) |
| [weft-lineage-extractor-3b](https://huggingface.co/wallfacers/weft-lineage-extractor-3b) | **this repo — efficient tier, 3 branches** |
| [weft-lineage-extractor-1.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-1.5b) | synthetic-only negative-result artifact |
| [weft-lineage-extractor-0.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-0.5b) | scale-curve point (synthetic-only) |
| [weft-lineage-extractor-jvm-1.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-jvm-1.5b) | cross-language (Scala/Java) negative result |
| [weft-script-lineage-synth](https://huggingface.co/datasets/wallfacers/weft-script-lineage-synth) | synthetic corpus + evidence reports |

Full evidence ledger (4.39:1 token measurement, frontier-dominance +0.15, McNemar parity,
dual-gate negative results):
[github.com/wallfacers/data-weave](https://github.com/wallfacers/data-weave)
(`ml/lineage-extractor/out/PAPER-EVIDENCE-068.md`).
