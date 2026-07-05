"""047 T019：抗泄漏消融表汇编（基线/B1/B2/3B 同口径）。

把 B1/B2 的评测产物（真实四方 eval-real / 合成 held-out eval-report / 泄漏 leak-report）
与基线/3B 冻结值汇成一张同口径消融表，供审读后并入论文 §B。

诚实性硬约束（contract ablation-table.md §3）：
- 每支必须同时有真实指标与合成 held-out（缺 --*-synth → 报错，防单边汇报）；
- 泄漏必须来自 --train-pool 统计（leak json 须含 verbatim_own_rate，防 B1 假性归零）；
- recall 相对基线跌 >20% 或合成 prec 跌 >5pt → 自动插显式告警行。

用法：
  PYTHONPATH=. python realeval/ablation_table.py \
      --b1-eval out/eval-real-b1.json --b1-leak out/leak-report-b1.json --b1-synth out/eval-report-b1.json \
      --b2-eval out/eval-real-b2.json --b2-leak out/leak-report-b2.json --b2-synth out/eval-report-b2.json \
      --report out/ablation-antileak.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

# 冻结常量（来源：specs/041-script-lineage-extraction/paper-negative-result-findings.md §5）。
# 不重跑、不读运行时；synth_prec 仅基线由 §1 给出（0.9954），3B 论文未单列 → None。
FROZEN = {
    "baseline-1.5b": dict(real_prec=0.270, real_hall=0.153, real_recall=0.618,
                          real_dir=0.496, leak=0.224, shaped=0.250, synth_prec=0.9954),
    "3b":            dict(real_prec=0.325, real_hall=0.107, real_recall=0.642,
                          real_dir=0.468, leak=0.109, shaped=0.109, synth_prec=None),
}

_COLS = ["real_prec", "real_hall", "real_recall", "real_dir", "leak", "shaped", "synth_prec"]
_HEADERS = ["真实 prec", "真实幻觉", "真实 recall(非空)", "真实方向(非空)",
            "逐字泄漏(自有池)", "合成形态率", "合成 held-out prec"]


def _sft(items: list[dict]) -> dict:
    """从评测 json 列表里取小模型行（name 以 sft 开头）。"""
    for it in items:
        if str(it.get("name", "")).startswith("sft"):
            return it
    raise SystemExit(f"[错误] 评测产物无 sft 行：{[i.get('name') for i in items]}")


def load_variant(label: str, eval_path: str | None, leak_path: str | None,
                 synth_path: str | None) -> dict | None:
    """读一支变体三产物 → 统一指标 dict；缺任一必需产物即报错（诚实性硬约束）。"""
    if not (eval_path or leak_path or synth_path):
        return None
    if not (eval_path and leak_path and synth_path):
        raise SystemExit(f"[错误] {label}: 需同时给 --{label}-eval/--{label}-leak/--{label}-synth"
                         "（合成 held-out + 泄漏缺一不可，禁单边汇报）")
    ev = _sft(json.loads(Path(eval_path).read_text(encoding="utf-8")))
    lk = _sft(json.loads(Path(leak_path).read_text(encoding="utf-8")))
    sy = json.loads(Path(synth_path).read_text(encoding="utf-8"))
    if "verbatim_own_rate" not in lk:
        raise SystemExit(f"[错误] {label}: leak json 缺 verbatim_own_rate → 泄漏须由 --train-pool 统计")
    if "overall" not in sy or "precision" not in sy["overall"]:
        raise SystemExit(f"[错误] {label}: 合成 held-out json 缺 overall.precision")
    return dict(label=label,
                real_prec=ev["full"]["precision"], real_hall=ev["full"]["hallucination"],
                real_recall=ev["nonempty"]["recall"], real_dir=ev["nonempty"]["direction_acc"],
                leak=lk["verbatim_own_rate"], shaped=lk["shaped_rate"],
                synth_prec=sy["overall"]["precision"])


def _fmt(v) -> str:
    return "—" if v is None else f"{v:.3f}"


def build_report(variants: list[dict]) -> tuple[str, dict]:
    """variants 有序 = [baseline, b1?, b2?, 3b]。返回 (md, json)。"""
    base = next(v for v in variants if v["label"] == "baseline-1.5b")
    warnings: list[str] = []
    for v in variants:
        if v["label"] in ("baseline-1.5b",):
            continue
        if base["real_recall"] and v["real_recall"] < base["real_recall"] * 0.8:
            warnings.append(f"⚠️ {v['label']}: 真实 recall {v['real_recall']:.3f} 较基线 "
                            f"{base['real_recall']:.3f} 跌 >20% —— 疑似过度弃权/召回崩塌，须判该补救是否可用。")
        if (v["synth_prec"] is not None and base["synth_prec"] is not None
                and v["synth_prec"] < base["synth_prec"] - 0.05):
            warnings.append(f"⚠️ {v['label']}: 合成 held-out prec {v['synth_prec']:.3f} 较基线 "
                            f"{base['synth_prec']:.3f} 跌 >5pt —— 以合成能力换真实数字的 trade-off，须显式披露。")

    head = "| 模型 | " + " | ".join(_HEADERS) + " |"
    sep = "| --- " * (len(_HEADERS) + 1) + "|"
    body = []
    for v in variants:
        cells = " | ".join(_fmt(v[c]) for c in _COLS)
        body.append(f"| {v['label']} | {cells} |")

    lines = ["# 047 抗泄漏消融表（041-R 方案 B，同口径）", "",
             "行=基线/B1/B2/3B；基线与 3B 为论文 §5 冻结值，B1/B2 由同一评测器同一金标产出。",
             "逐字泄漏按**被测模型自有训练池**统计（防 B1 换名字假性归零）。", "",
             head, sep, *body, "",
             "## 结论骨架（人工审读后并入论文 §B）", "",
             "- 补救是否奏效：___（对比 B1/B2 逐字泄漏 vs 基线 0.224、3B 0.109）",
             "- 仍未解的病：读写方向混淆（与规模无关）、domain-shift 整体退化 —— 须保留披露。",
             "- trade-off：合成 held-out 是否退化、recall 是否被弃权牺牲 —— 见下告警。", ""]
    if warnings:
        lines += ["### ⚠️ 自动告警（恶化/权衡，禁隐去）", "", *warnings, ""]
    else:
        lines += ["_（无自动告警触发：未检出 recall 崩塌 / 合成退化）_", ""]

    data = {"variants": variants, "warnings": warnings, "columns": _COLS}
    return "\n".join(lines) + "\n", data


def main() -> None:
    ap = argparse.ArgumentParser()
    for v in ("b1", "b2"):
        ap.add_argument(f"--{v}-eval")
        ap.add_argument(f"--{v}-leak")
        ap.add_argument(f"--{v}-synth")
    ap.add_argument("--report", default="out/ablation-antileak.md")
    args = ap.parse_args()

    variants = [dict(label="baseline-1.5b", **FROZEN["baseline-1.5b"])]
    b1 = load_variant("b1", args.b1_eval, args.b1_leak, args.b1_synth)
    b2 = load_variant("b2", args.b2_eval, args.b2_leak, args.b2_synth)
    if b1:
        variants.append(b1)
    if b2:
        variants.append(b2)
    variants.append(dict(label="3b", **FROZEN["3b"]))

    if not (b1 or b2):
        raise SystemExit("[错误] 至少给一支 B1 或 B2 的三产物；否则无消融可汇。")

    md, data = build_report(variants)
    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(md, encoding="utf-8")
    out.with_suffix(".json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(md)


if __name__ == "__main__":
    main()
