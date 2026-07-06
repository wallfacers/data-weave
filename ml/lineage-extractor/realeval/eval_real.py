"""041-R 真实集四方对比评测：小模型(SFT) / M1(Qwen-DashScope) / M2(Ali-Anthropic) / regex 基线。

复用 `eval.evaluate.run_eval`（解耦评估器）与各 predictor，对同一批人工金标
（`realeval/gold/real.jsonl`，约定 A：语句字面、忽略动态/路径/临时视图/注释）分别评测，
产出对比表：全集看 precision/hallucination（真实集稀疏、主考"参数化噪声下不乱抽"），
非空子集看 recall/direction（少量字面脚本考真抽取）。

用法：PYTHONPATH=. python realeval/eval_real.py \
        --gold realeval/gold/real.jsonl --model out/run3/merged --report out/eval-real.md
缺失的大模型 client（.env 未配）优雅跳过该列，不报错退出。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.evaluate import predict as sft_predict
from eval.metrics import aggregate, score_row, tables
from eval.evaluate import run_eval


def _nonempty_metrics(predict_fn, rows):
    """仅在非空金标子集上评（recall/direction 才有意义）；复用同一 predict_fn。"""
    sel = [r for r in rows if tables(r["labels"]["reads"]) or tables(r["labels"]["writes"])]
    counts = [score_row(r["labels"], predict_fn(r), r["content"]) for r in sel]
    return aggregate(counts), len(sel)


def evaluate_predictor(name: str, predict_fn, rows) -> dict:
    full = run_eval(predict_fn, rows)["overall"]
    ne, ne_n = _nonempty_metrics(predict_fn, rows)
    return {"name": name, "full": full, "nonempty": ne, "nonempty_n": ne_n}


def _row(r: dict) -> str:
    f, ne = r["full"], r["nonempty"]
    return (f"| {r['name']} | {f['precision']:.3f} | {f['hallucination']:.3f} | {f['invalid']} "
            f"| {ne['recall']:.3f} | {ne['direction_acc']:.3f} | {ne['f1']:.3f} |")


def build_predictors(model_dir: str, dir_fix: bool = False, sft_label: str = "sft"):
    """小模型(SFT) + m1/m2 大模型 + regex。缺失 client 优雅跳过。

    dir_fix=True（052）：额外加一列「{sft_label} (dir_fix)」= 模型表集 + AST 方向修正，
    与「{sft_label} (model)」模型独跑列并列，一次报告同出系统数与模型独跑数（FR-017）。
    """
    preds = {}
    # 小模型（惰性加载 transformers）
    try:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        tok = AutoTokenizer.from_pretrained(model_dir)
        model = AutoModelForCausalLM.from_pretrained(
            model_dir, dtype=torch.bfloat16,
            device_map="cuda" if torch.cuda.is_available() else "cpu")
        model.eval()
        base = lambda row: sft_predict(model, tok, row)  # noqa: E731
        preds[f"{sft_label} (model)"] = base
        if dir_fix:
            from realeval.dir_fix import apply_dir_fix
            preds[f"{sft_label} (dir_fix)"] = lambda row: apply_dir_fix(base(row), row["content"])
    except Exception as e:  # noqa
        print(f"[warn] 小模型加载失败，跳过 sft 列：{e}")

    from eval.baselines import llm_baseline, regex_baseline
    from llm.clients import load_clients
    clients = load_clients()
    for name, disp in (("m1", "m1-qwen"), ("m2", "m2-anthropic")):
        c = clients.get(name)
        if c is not None:
            preds[disp] = llm_baseline.make_predict(c)
        else:
            print(f"[warn] {name} client 缺失，跳过该基线列")
    preds["regex"] = regex_baseline.predict
    return preds


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real.jsonl")
    ap.add_argument("--model", default="out/run3/merged")
    ap.add_argument("--report", default="out/eval-real.md")
    ap.add_argument("--dir-fix", action="store_true", help="额外加 sft+dir_fix 系统列（052）")
    ap.add_argument("--sft-label", default="sft", help="sft 列显示名（如 distill-3b）")
    args = ap.parse_args(argv)

    rows = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    n_ne = sum(1 for r in rows if tables(r["labels"]["reads"]) or tables(r["labels"]["writes"]))
    print(f"真实集 gold：{len(rows)} 条（非空 {n_ne} / ∅ {len(rows) - n_ne}）")

    preds = build_predictors(args.model, dir_fix=args.dir_fix, sft_label=args.sft_label)
    results = [evaluate_predictor(name, fn, rows) for name, fn in preds.items()]

    lines = [
        "# 041-R 真实集四方对比评测（人工金标，约定 A）",
        "",
        f"- 样本：{len(rows)} 条真实 GitHub ETL 脚本（作业筛后），非空金标 {n_ne} / 空金标 {len(rows) - n_ne}",
        "- 全集看 precision / 幻觉率（真实 ETL 稀疏，主考参数化噪声下不乱抽）；",
        "  非空子集看 recall / 方向准确率（少量字面脚本考真抽取）。",
        "- 金标约定 A：仅标可执行语句里字面出现的表；忽略动态名/文件路径/临时视图/注释/配置驱动。",
        "",
        "| 抽取器 | precision(全) | 幻觉率(全) | 非法 | recall(非空) | 方向(非空) | f1(非空) |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    lines += [_row(r) for r in results]
    report = "\n".join(lines) + "\n"

    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    out.with_suffix(".json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
