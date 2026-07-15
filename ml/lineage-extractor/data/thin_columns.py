"""068 frontier 列密度扫描：保留全部表边，按 keep 比例保留列监督、其余设 None(弃权)。
释放 LoRA 容量给表覆盖，找表/列平衡甜点。确定性(seed=hash(content|table|side|idx))可复现。
用法: python3 data/thin_columns.py --in data/out/train-tri.jsonl --keep 0.5 --out data/out/train-tri-col50.jsonl
"""
from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path


def _keep_edge(content: str, table: str, side: str, idx: int, keep: float) -> bool:
    h = hashlib.sha256(f"{content[:200]}|{table}|{side}|{idx}".encode()).hexdigest()
    return (int(h[:8], 16) % 10000) < keep * 10000


def thin(rows, keep: float):
    out = []
    kept = dropped = 0
    for r in rows:
        lab = r.get("labels") or {}
        new = {"reads": [], "writes": []}
        for side in ("reads", "writes"):
            for i, e in enumerate(lab.get(side) or []):
                if isinstance(e, dict) and e.get("columns"):
                    if _keep_edge(r.get("content", ""), e.get("table", ""), side, i, keep):
                        new[side].append(e); kept += 1
                    else:
                        new[side].append({**e, "columns": None}); dropped += 1
                else:
                    new[side].append(e)
        out.append({**r, "labels": new})
    return out, kept, dropped


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", default="data/out/train-tri.jsonl")
    ap.add_argument("--keep", type=float, required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    rows = [json.loads(l) for l in Path(a.inp).read_text(encoding="utf-8").splitlines() if l.strip()]
    out, kept, dropped = thin(rows, a.keep)
    Path(a.out).write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in out) + "\n", encoding="utf-8")
    print(f"thin keep={a.keep}: {len(out)} rows, cols kept {kept} dropped {dropped} "
          f"(-> {kept/(kept+dropped):.0%} of col-bearing edges retain columns)")


if __name__ == "__main__":
    main()
