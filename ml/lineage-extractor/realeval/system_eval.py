"""050 Tier 1：系统级评测——模型独跑 vs 通道分工混合路由（同 canon 口径）。

量化"把含 SQL 的脚本路由给 sqlglot、残差留给模型"相对"模型独跑"的系统级增益。
一次 GPU 推理（残差模型），对 139 真实脚本各算两套预测：
- model：模型独跑（现状）
- hybrid：SQL 通道命中则以其为准，否则落模型（通道分工）
两套均按 canon 口径评（eval.metrics，Tier 0），并统计混合路由里 SQL/model 各接了多少脚本。

诚实披露：SQL 通道是启发式提取的**下界**（隔离嵌入 SQL 本身是未解难题），故 hybrid 增益
是通道分工的保守估计；`channel_router` 的条件方向（0.597>模型 0.553）另见 §8。

用法：PYTHONPATH=. python realeval/system_eval.py --model <merged> --gold realeval/gold/real.jsonl
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import aggregate, score_row, tables
from realeval.channel_router import extract_sql_lineage, route_predict


def _agg(rows, pred_of, canon):
    return aggregate([score_row(r["labels"], pred_of(r), r["content"], canon=canon) for r in rows])


def system_metrics(rows, model_fn, canon: bool = True) -> dict:
    """返回 model / hybrid 两套的全集+非空指标 + 路由分布（纯函数，model_fn 注入以便测）。"""
    ne = [r for r in rows if tables(r["labels"]["reads"]) or tables(r["labels"]["writes"])]

    def model_pred(r):
        return model_fn(r)

    def hybrid_pred(r):
        return route_predict(r["content"], lambda: model_fn(r))

    routed = [route_predict(r["content"], lambda: {"reads": [], "writes": []})["_channel"] for r in rows]
    return {
        "n": len(rows), "nonempty_n": len(ne),
        "routed_sql": routed.count("sql"), "routed_model": routed.count("model"),
        "model": {"full": _agg(rows, model_pred, canon), "nonempty": _agg(ne, model_pred, canon)},
        "hybrid": {"full": _agg(rows, hybrid_pred, canon), "nonempty": _agg(ne, hybrid_pred, canon)},
    }


def _load_model(model_dir: str):
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from eval.evaluate import predict as sft_predict
    tok = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForCausalLM.from_pretrained(
        model_dir, dtype=torch.bfloat16,
        device_map="cuda" if torch.cuda.is_available() else "cpu").eval()
    cache: dict[int, dict] = {}

    def fn(row):
        key = id(row)
        if key not in cache:
            cache[key] = sft_predict(model, tok, row)
        return cache[key]
    return fn


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real.jsonl")
    ap.add_argument("--model", required=True)
    ap.add_argument("--name", default="b3")
    ap.add_argument("--report", default="out/system-eval.md")
    args = ap.parse_args(argv)

    rows = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    res = system_metrics(rows, _load_model(args.model), canon=True)

    def _line(tag, m):
        f, ne = m["full"], m["nonempty"]
        return (f"| {tag} | {f['precision']:.3f} | {f['hallucination']:.3f} | "
                f"{ne['recall']:.3f} | {ne['direction_acc']:.3f} | {ne['f1']:.3f} |")

    md = [f"# 050 Tier 1 系统级评测：模型独跑 vs 通道分工（canon 口径，残差模型={args.name}）", "",
          f"- 路由分布：SQL 通道接 {res['routed_sql']}/{res['n']} 脚本，模型接 {res['routed_model']}/{res['n']}。",
          "- SQL 通道 = 启发式提取（下界）；hybrid = SQL 命中则以其为准，否则落模型。", "",
          "| 系统 | precision(全) | 幻觉率(全) | recall(非空) | 方向(非空) | f1(非空) |",
          "| --- | --- | --- | --- | --- | --- |",
          _line("model 独跑", res["model"]), _line("hybrid 通道分工", res["hybrid"]), ""]
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text("\n".join(md) + "\n", encoding="utf-8")
    Path(args.report).with_suffix(".json").write_text(
        json.dumps(res, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n".join(md))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
