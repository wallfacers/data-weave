"""059：按 token 预算截断脚本，保证训练目标(答案/思维链)永不被右截断丢失。

the-stack 脚本可长达 6000 token，+ pro 思维链 → 序列超 max_len，SFTTrainer 右截断会吃掉
末尾的答案 JSON（55% 行中招），毁掉训练信号。本 prep 对每行**只截脚本**（保系统提示+
思维链+答案），使 apply_chat_template 后总长 ≤ max_len。plain / reasoning 两路都处理。

用法: PYTHONPATH=. python3 train/prep_fit.py --in data/reasoning-corpus-059.jsonl \
        --kind reasoning --max-len 2048 --out data/out/reason-fit.jsonl
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def fit_row(row, tok, max_len, kind, sys_prompt):
    from train.sft_qlora import to_messages, to_messages_reasoning
    builder = to_messages_reasoning if kind == "reasoning" else to_messages
    # 二分/线性收缩脚本，直到总长 ≤ max_len-8（留 EOS 余量）。
    content = row["content"]
    for _ in range(24):
        r2 = dict(row); r2["content"] = content
        text = tok.apply_chat_template(builder(r2)["messages"], tokenize=False)
        n = len(tok(text)["input_ids"])
        if n <= max_len - 8:
            return r2, n
        # 超出量按脚本 token 比例砍（保底砍 64 token）。
        script_ids = tok(content)["input_ids"]
        drop = max(64, n - (max_len - 8))
        if drop >= len(script_ids):
            content = tok.decode(script_ids[: max(16, len(script_ids)//2)])
        else:
            content = tok.decode(script_ids[: len(script_ids) - drop])
    return r2, n


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", required=True)
    ap.add_argument("--kind", choices=["plain", "reasoning"], required=True)
    ap.add_argument("--max-len", type=int, default=2048)
    ap.add_argument("--base-model", default="Qwen/Qwen2.5-Coder-3B-Instruct")
    ap.add_argument("--out", required=True)
    args = ap.parse_args(argv)

    from transformers import AutoTokenizer
    from train.sft_qlora import SYSTEM_PROMPT
    tok = AutoTokenizer.from_pretrained(args.base_model)

    rows = [json.loads(l) for l in Path(args.inp).read_text(encoding="utf-8").splitlines() if l.strip()]
    out = Path(args.out); out.parent.mkdir(parents=True, exist_ok=True)
    capped = 0
    with out.open("w", encoding="utf-8") as f:
        for r in rows:
            r2, n = fit_row(r, tok, args.max_len, args.kind, SYSTEM_PROMPT)
            if r2["content"] != r["content"]:
                capped += 1
            f.write(json.dumps(r2, ensure_ascii=False) + "\n")
    print(f"prep_fit: {len(rows)} rows, capped {capped} scripts → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
