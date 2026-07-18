---
license: other
license_name: weft-research
license_link: https://github.com/wallfacers/data-weave
pipeline_tag: text-generation
library_name: transformers
base_model: Qwen/Qwen2.5-Coder-7B-Instruct
tags:
- data-lineage
- etl
- sql
- code
- lora
- research-artifact
- negative-result
language:
- en
datasets:
- wallfacers/weft-script-lineage-synth
---

# weft-lineage-extractor-7b

A 7B code model (LoRA fine-tuned, merged) that extracts **table- and column-level data
lineage** from ETL scripts as structured JSON. Trained on **1,154 real-world GitHub ETL
scripts** with tri-vendor consensus silver labels.

> ## ⚠️ Published primarily as a scale-curve NEGATIVE RESULT: the 7B "capacity valley".
>
> On the real-world benchmark, **7B ≈ 3B** — its table F1 (0.706) is *slightly below* the
> 3B column specialist (0.723) while column F1 is comparable (0.924 vs 0.931). The scale
> dividend on this task only materializes at 14B. Additionally, **table-token loss weighting
> is inert at 7B** (+0.004 table R, vs +0.048 for the same W=3 at 14B): the lever that
> balances table/column at other sizes has a capacity threshold that 7B does not clear.
> **If you want quality, use
> [weft-lineage-extractor-14b](https://huggingface.co/wallfacers/weft-lineage-extractor-14b);
> if you want efficiency, use
> [weft-lineage-extractor-3b](https://huggingface.co/wallfacers/weft-lineage-extractor-3b).**
> This checkpoint is the evidence in between.

## Branches & evaluation

Real-world benchmark: 129 non-empty GitHub scripts, tri-vendor consensus gold (qwen-max ∩
deepseek-v4-pro ∩ GPT-5.6, 2-of-3), greedy decoding, `max_new_tokens=512`. Column metrics are
conditional on matched tables.

| Branch | Variant | Table P / R / F1 | Column P / R / F1 | Direction |
|---|---|---|---|---|
| **`main`** | plain full-column | 0.813 / 0.624 / 0.706 | 0.934 / 0.914 / **0.924** | 0.620 |
| `tri-lw3` | loss-weighted W=3 | 0.809 / 0.628 / 0.707 | 0.938 / 0.853 / 0.894 | 0.626 |

The two branches are nearly identical on tables — that near-identity *is* the finding
(loss weighting does nothing at 7B, unlike 3B and 14B).

For reference, the same recipe trained on 10,000 **synthetic** scripts scores 0.999 table F1
on the synthetic held-out set and **0.467 on real scripts** — synthetic-only scores are
systematically misleading (see the family's negative-result artifacts below).

## Training details

| | |
|---|---|
| Base model | [Qwen/Qwen2.5-Coder-7B-Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct) |
| Method | LoRA r=32, α=64, bf16 (merged into this checkpoint) |
| Data | 1,154 real GitHub ETL scripts, tri-vendor consensus silver labels (tables + columns) |
| Schedule | 3 epochs (219 steps), effective batch 16, lr 2e-4 cosine, max seq 2048 |
| Hardware | 1× RTX 6000D 84 GB, ~32 min per variant |

## Usage

Same interface as the whole family — system prompt in, one JSON object out.

```python
from transformers import AutoModelForCausalLM, AutoTokenizer

REPO = "wallfacers/weft-lineage-extractor-7b"
tok = AutoTokenizer.from_pretrained(REPO)
model = AutoModelForCausalLM.from_pretrained(REPO, dtype="bfloat16", device_map="cuda")

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

**Output schema**: `{"reads": [{"table": str, "columns": [str] | null}], "writes": [...]}`.
**Extraction convention ("Convention A")**: a table is labeled only when its literal name
appears in an executable read/write statement; dynamic names, commented-out/printed SQL and
temp views are out of scope by design.

## Intended use & limitations

- **Intended**: reproducing the scale curve; ablation baselines; research on capacity
  thresholds for structured extraction.
- **Not the deployment pick**: 14B strictly dominates it on quality and 3B matches it at a
  third of the cost. Table recall 0.62 means real misses; labels are LLM-consensus silver,
  not human gold.

## Model family

| Model | Role |
|---|---|
| [weft-lineage-extractor-14b](https://huggingface.co/wallfacers/weft-lineage-extractor-14b) | best single models (3 branches) |
| [weft-lineage-extractor-7b](https://huggingface.co/wallfacers/weft-lineage-extractor-7b) | **this repo — capacity-valley scale point** |
| [weft-lineage-extractor-3b](https://huggingface.co/wallfacers/weft-lineage-extractor-3b) | efficient family (12 GB-GPU deployable), 3 branches |
| [weft-lineage-extractor-1.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-1.5b) | synthetic-only negative-result artifact |
| [weft-lineage-extractor-0.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-0.5b) | scale-curve point (synthetic-only) |
| [weft-lineage-extractor-jvm-1.5b](https://huggingface.co/wallfacers/weft-lineage-extractor-jvm-1.5b) | cross-language (Scala/Java) negative result |
| [weft-script-lineage-synth](https://huggingface.co/datasets/wallfacers/weft-script-lineage-synth) | synthetic corpus + evidence reports |

Full experiment ledger:
[github.com/wallfacers/data-weave](https://github.com/wallfacers/data-weave)
(`ml/lineage-extractor/out/FINDINGS-14b-cloud.md`).
