"""041-R 记忆泄漏分析（负结果论文立论基石）。

论点：合成-only 训练的 1.5B 小模型在真实 OOD 脚本上，其**幻觉表名**（输出里既不在
金标、也不字面出现在脚本文本中的名字）显著比例是**逐字的合成训练表名**——即模型在
看不懂的真实脚本上"背诵"训练分布，而非泛化抽取。对照：未经合成训练的大模型（M2）的
幻觉不含合成池名字（其错误是过度抽取真实动态名，性质不同）。

产出（`out/leak-report.md/.json`）：每个抽取器的幻觉总数、其中"合成池逐字命中"数与占比、
以及示例。运行需 sft 权重 + 可选 M2 client（缺则只析 sft）。

用法：PYTHONPATH=. python realeval/leak_analysis.py --model out/run3/merged --gold realeval/gold/real.jsonl
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

from eval.metrics import tables


def train_table_pool() -> set[str]:
    """确定性重放**合成生成**表名池（`synth_table_names`，同 SEED，无网络）。

    刻意只取合成生成名、不含 HF 收割的真实 SQL 名——HF 名本就是真实库表名，可能
    合法出现在真实脚本里而非泄漏；真实脚本吐出合成生成名（如 `dws.dws_member_point_di`）
    只可能来自背诵合成生成器，是干净的记忆泄漏证据。取 400+，覆盖训练所见分布。"""
    from data.synth_pipeline import SEED, synth_table_names
    return set(synth_table_names(random.Random(SEED), 400))


def _synthetic_shaped(name: str) -> bool:
    """形态像合成池名（schema∈仓库 schema 且 base∈仓库 base），即便非逐字命中也算"训练分布形"。"""
    from data.synth_pipeline import WAREHOUSE_BASES, WAREHOUSE_SCHEMAS
    n = name.lower()
    schema = n.split(".")[0] if "." in n else ""
    leaf = n.split(".")[-1]
    if schema and schema not in [s.lower() for s in WAREHOUSE_SCHEMAS]:
        return False
    return any(b in leaf for b in WAREHOUSE_BASES)


def hallucinations(pred: dict, gold: dict, content: str) -> list[str]:
    """幻觉表名 = 预测里既不在金标、也不字面出现在脚本文本中的名字。"""
    gold_set = set(tables(gold["reads"])) | set(tables(gold["writes"]))
    out = []
    for t in set(tables(pred.get("reads"))) | set(tables(pred.get("writes"))):
        if t and t not in gold_set and t not in content:
            out.append(t)
    return out


def analyze(name: str, predict_fn, rows, train_pool: set[str]) -> dict:
    total_hall, verbatim, shaped, examples = 0, 0, 0, []
    for r in rows:
        for t in hallucinations(predict_fn(r), r["labels"], r["content"]):
            total_hall += 1
            v = t in train_pool
            s = _synthetic_shaped(t)
            verbatim += int(v)
            shaped += int(s)
            if len(examples) < 15 and (v or s):
                examples.append({"table": t, "verbatim_train": v, "synthetic_shaped": s,
                                 "script": r["meta"]["template_id"].split("/")[-1]})
    return {"name": name, "hallucinations": total_hall,
            "verbatim_train_names": verbatim,
            "synthetic_shaped": shaped,
            "verbatim_rate": round(verbatim / total_hall, 3) if total_hall else 0.0,
            "shaped_rate": round(shaped / total_hall, 3) if total_hall else 0.0,
            "examples": examples}


def build_predictors(model_dir: str):
    preds = {}
    try:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        from eval.evaluate import predict as sft_predict
        tok = AutoTokenizer.from_pretrained(model_dir)
        model = AutoModelForCausalLM.from_pretrained(
            model_dir, dtype=torch.bfloat16,
            device_map="cuda" if torch.cuda.is_available() else "cpu").eval()
        preds["sft-1.5b"] = lambda row: sft_predict(model, tok, row)
    except Exception as e:  # noqa
        print(f"[warn] sft 加载失败：{e}")
    from eval.baselines import llm_baseline
    from llm.clients import load_clients
    c = load_clients().get("m2")
    if c is not None:
        preds["m2-anthropic"] = llm_baseline.make_predict(c)
    else:
        print("[warn] m2 client 缺失，跳过对照列")
    return preds


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="out/run3/merged")
    ap.add_argument("--gold", default="realeval/gold/real.jsonl")
    ap.add_argument("--report", default="out/leak-report.md")
    args = ap.parse_args(argv)

    rows = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    pool = train_table_pool()
    print(f"训练表池 {len(pool)} 个名字；真实集 {len(rows)} 条")

    results = [analyze(n, fn, rows, pool) for n, fn in build_predictors(args.model).items()]

    lines = ["# 041-R 记忆泄漏分析（负结果论文立论基石）", "",
             "幻觉表名 = 预测里既不在金标、也不字面出现在脚本文本中的名字。",
             "**verbatim_train** = 逐字命中训练表池；**synthetic_shaped** = 形态像合成池（schema+base）。", "",
             "| 抽取器 | 幻觉数 | 逐字训练名 | 占比 | 合成形态 | 占比 |",
             "| --- | --- | --- | --- | --- | --- |"]
    for r in results:
        lines.append(f"| {r['name']} | {r['hallucinations']} | {r['verbatim_train_names']} "
                     f"| {r['verbatim_rate']:.3f} | {r['synthetic_shaped']} | {r['shaped_rate']:.3f} |")
    lines += ["", "## 示例（逐字/形态命中）"]
    for r in results:
        lines.append(f"\n### {r['name']}")
        for ex in r["examples"]:
            tag = "逐字训练名" if ex["verbatim_train"] else "合成形态"
            lines.append(f"- `{ex['table']}` [{tag}] @ {ex['script']}")

    report = "\n".join(lines) + "\n"
    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    out.with_suffix(".json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
