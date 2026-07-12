"""065 T014（US4，可选/稳健性）：auto-gold 适度扩容 gold C → 收窄 US1 CI（验 SC-006）。

编排既有流水线（**不新增打标逻辑**）：
  collect_stack（the-stack，HF_TOKEN）→ teacher_label（m1+m3，与 gold C′ **同构造**可合并）
  → build_gold_b（min-agree=2，`--exclude-gold real-c.jsonl` 按 content-hash 排已在 gold C 的样本）
  → 按 059 协议保持 empty_ratio≈0.20 下采样新空脚本 → **打 `robustness_only=true`** 合入 gold C。

★诚实边界（FR-009/SC-006）：扩容集**仍系 teacher（m1∩m3）派生、非独立真值**，仅作稳健性证据；
每条新样本带 `robustness_only=true`，论文须显式标注。与 gold C′ 同源同构造 → 属"因样本更多而
收窄"而非"分布漂移移动"（Edge Case：来源同 the-stack、同 ETL 习语门、同 teacher 对 → 同分布）。

纯编排 + 确定性下采样（seed 固定），联网步骤在子进程里跑。GPU 预测与 significance 重跑见
`--after` 提示（模型 preds 需 GPU，故拆出）。

用法: set -a; . ./.env; set +a; PYTHONPATH=. python3 realeval/expand_gold_c.py \
        --target 260 --langs python,shell,sql \
        --base-gold realeval/gold/real-c.jsonl --out realeval/gold/real-c-expanded.jsonl
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

_EMPTY_RATIO = 0.20  # 059 协议：非空:空 = 0.80:0.20


def _load(path):
    p = Path(path)
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()] if p.exists() else []


def _run(argv):
    """子进程跑既有脚本（共享当前 env，含 .env 凭据）。"""
    print(f"  $ {' '.join(argv)}", flush=True)
    r = subprocess.run([sys.executable, *argv], env={**__import__("os").environ, "PYTHONPATH": "."})
    if r.returncode != 0:
        raise SystemExit(f"子步骤失败（exit {r.returncode}）: {argv}")


def downsample_empties(new_rows, base_empty, base_nonempty, seed):
    """确定性：只保留足量新空脚本，使合并后 empty_ratio 维持 0.20。按 chash 排序取前 k（可复现）。"""
    ne = [r for r in new_rows if not r.get("is_empty")]
    em = [r for r in new_rows if r.get("is_empty")]
    # 合并后总空 = base_empty + keep；总量 = base_nonempty + base_empty + len(ne) + keep
    # 令 (base_empty+keep)/(总量) = 0.20 → keep = 0.25*(base_nonempty+len(ne)) - base_empty
    target_keep = round(_EMPTY_RATIO / (1 - _EMPTY_RATIO) * (base_nonempty + len(ne))) - base_empty
    target_keep = max(0, min(len(em), target_keep))
    em_sorted = sorted(em, key=lambda r: r.get("chash", ""))
    return ne + em_sorted[:target_keep]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=int, default=260, help="collect_stack 候选目标数")
    ap.add_argument("--langs", default="python,shell,sql")
    ap.add_argument("--teachers", default="m1,m3", help="须与 gold C′ 同构造")
    ap.add_argument("--pool", default="realeval/pool-c-exp")
    ap.add_argument("--labels", default="realeval/teacher_labels-c-exp")
    ap.add_argument("--base-gold", default="realeval/gold/real-c.jsonl")
    ap.add_argument("--raw-out", default="realeval/gold/real-c-exp-raw.jsonl")
    ap.add_argument("--out", default="realeval/gold/real-c-expanded.jsonl")
    ap.add_argument("--report", default="out/expand-gold.md")
    ap.add_argument("--seed", type=int, default=20260712)
    ap.add_argument("--workers", type=int, default=14)
    ap.add_argument("--skip-collect", action="store_true", help="池已就绪则跳过采集")
    ap.add_argument("--skip-label", action="store_true", help="标注已就绪则跳过打标")
    args = ap.parse_args(argv)

    base = _load(args.base_gold)
    base_ne = sum(1 for r in base if not r.get("is_empty"))
    base_em = len(base) - base_ne
    print(f"[expand] base gold C: {len(base)} 行（非空 {base_ne} / 空 {base_em}）", flush=True)

    # A. 采集更多候选（the-stack 流式）
    if not args.skip_collect:
        _run(["realeval/collect_stack.py", "--langs", args.langs,
              "--target", str(args.target), "--out", args.pool])

    # B. m1+m3 双 teacher 打标（resume 续跑，重跑不重复烧配额）
    if not args.skip_label:
        _run(["realeval/teacher_label.py", "--pool", args.pool, "--out", args.labels,
              "--teachers", args.teachers, "--workers", str(args.workers)])

    # C. 一致裁决 + 排除已在 gold C 的样本（content-hash dedup 防污染）
    _run(["realeval/build_gold_b.py", "--pool", args.pool, "--labels", args.labels,
          "--teachers", args.teachers, "--min-agree", "2",
          "--exclude-gold", args.base_gold, "--out", args.raw_out])

    raw = _load(args.raw_out)
    raw_ne = sum(1 for r in raw if not r.get("is_empty"))
    print(f"[expand] 裁决后新样本: {len(raw)}（非空 {raw_ne} / 空 {len(raw) - raw_ne}）", flush=True)

    # D. 下采样新空脚本维持 0.20 + 打 robustness_only 标注 + 合并
    kept = downsample_empties(raw, base_em, base_ne, args.seed)
    for r in kept:
        r["robustness_only"] = True
        r["provenance"] = (r.get("provenance", "") + "|expand|robustness_only").lstrip("|")
    merged = base + kept
    m_ne = sum(1 for r in merged if not r.get("is_empty"))
    Path(args.out).write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in merged) + "\n", encoding="utf-8")

    L = ["# 065 T014：auto-gold 扩容（US4，稳健性 / teacher 派生）", "",
         f"- base gold C′：{len(base)} 行（非空 {base_ne} / 空 {base_em}）",
         f"- 新采集裁决（m1∩m3，min-agree=2，已排除 gold C content-hash 重合）：{len(raw)} 行（非空 {raw_ne}）",
         f"- 维持 empty_ratio≈0.20 保留新样本：{len(kept)}（非空 {sum(1 for r in kept if not r.get('is_empty'))} / 空 {sum(1 for r in kept if r.get('is_empty'))}）",
         f"- **扩容后合并集**：{len(merged)} 行（**非空 {m_ne}** / 空 {len(merged) - m_ne}） → `{args.out}`",
         "",
         "> **诚实标注（FR-009）**：新增样本全部带 `robustness_only=true`，系 m1∩m3 teacher 派生、"
         "**非独立真值**；与 gold C′ 同源（the-stack）同构造（同 ETL 习语门 + 同 teacher 对 + min-agree=2），"
         "故 CI 收窄属\"样本更多\"而非分布漂移。仅作稳健性证据，论文中须显式声明。",
         "",
         "## SC-006 验证（下一步，需 GPU）",
         "```",
         "# 1) 对新增样本 dump model preds（GPU），与既有 base preds 拼成扩容 preds 目录",
         "# 2) PYTHONPATH=. python3 realeval/significance_report.py \\",
         f"#      --gold {args.out} --preds-dir out/preds-exp --report out/significance-c-expanded.md",
         "# 3) 比较关键指标 95%CI 宽度：扩容后 < 扩容前 → SC-006 ✅",
         "```"]
    Path(args.report).write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
