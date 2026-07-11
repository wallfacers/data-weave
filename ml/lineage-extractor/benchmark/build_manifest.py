"""065 T011（US3）：从仲裁后 gold C 产可复现发布清单（标签+指针，无源码正文）。

产出 manifest.json（LabelRecord + SourcePointer）+ labels.jsonl。硬约束（FR-007）：记录**不含
`content` 源码正文**、`columns` 恒 None（列级 out-of-scope）、`disclosure`/`credential_free` 标志置真。
契约见 `specs/065-lineage-paper/contracts/benchmark-manifest.schema.json`。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.subset import classify

_REQUIRED_REC = ("repo", "commit", "path", "reads", "writes", "subset")
# 复现头条所需最小脚本集（不含训练/合成相关）
_DEFAULT_EVAL_SCRIPTS = [
    "eval/metrics.py", "eval/subset.py", "eval/significance.py",
    "eval/baselines/regex_baseline.py", "eval/baselines/sqllineage_baseline.py",
    "realeval/counts_adapter.py", "realeval/significance_report.py",
    "realeval/eval_baselines_c.py", "benchmark/fetch.py",
]


def _tables(items):
    return [{"table": str(t.get("table")), "columns": None}
            for t in (items or []) if t.get("table")]


def build_manifest(gold_rows, *, version: str, eval_scripts=None) -> dict:
    records = []
    for r in gold_rows:
        rec = {
            "repo": str(r.get("repo") or r.get("repository") or ""),
            "commit": str(r.get("commit") or r.get("sha") or ""),
            "path": str(r.get("path") or r.get("file") or ""),
            "reads": _tables(r["labels"].get("reads")),
            "writes": _tables(r["labels"].get("writes")),
            "subset": classify(r),
        }
        if "arbitrated" in r:
            rec["arbitrated"] = bool(r["arbitrated"])
        records.append(rec)
    return {
        "version": version,
        "records": records,
        "eval_scripts": list(eval_scripts or _DEFAULT_EVAL_SCRIPTS),
        "disclosure": {"no_source_bodies": True, "no_synthetic_train": True},
        "credential_free": True,
    }


def validate_manifest(m: dict) -> None:
    """强制 FR-007 硬约束——不合格即抛。"""
    assert m.get("credential_free") is True, "credential_free 必须为 true"
    d = m.get("disclosure", {})
    assert d.get("no_source_bodies") is True, "必须声明不含源码正文"
    assert d.get("no_synthetic_train") is True, "必须声明不含合成训练集"
    assert isinstance(m.get("records"), list) and m["records"], "records 不能为空"
    for rec in m["records"]:
        assert "content" not in rec, "记录不得含源码正文（content）"
        for k in _REQUIRED_REC:
            assert k in rec, f"记录缺字段 {k}"
        assert rec["subset"] in ("sql", "script")
        for t in rec["reads"] + rec["writes"]:
            assert t.get("columns") is None, "列级 out-of-scope，columns 必须为 null"


def _load_jsonl(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real-c-arbitrated.jsonl")
    ap.add_argument("--version", default="0.1.0")
    ap.add_argument("--out", default="dist/benchmark")
    args = ap.parse_args(argv)

    gold = _load_jsonl(args.gold)
    manifest = build_manifest(gold, version=args.version)
    validate_manifest(manifest)

    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2),
                                       encoding="utf-8")
    with (out / "labels.jsonl").open("w", encoding="utf-8") as f:
        for rec in manifest["records"]:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"[benchmark] {len(manifest['records'])} 条 → {out}/manifest.json (+labels.jsonl)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
