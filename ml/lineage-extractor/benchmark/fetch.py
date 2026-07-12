"""065 T012（US3）：按 repo@commit:path 无凭据抓公开源，重建评测输入。

用 GitHub raw 公开端点（无 token）。失败逐条如实记缺失并跳过（不中断）。抓回的源文件仅供本地
重算指标，不随发布物分发（守 FR-007）。
"""
from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.request
from pathlib import Path

_RAW = "https://raw.githubusercontent.com/{repo}/{commit}/{path}"


def source_url(rec: dict) -> str:
    """纯函数：由记录指针构造公开 raw URL（可单测，无网络）。"""
    return _RAW.format(repo=rec["repo"], commit=rec["commit"], path=rec["path"])


def _local_path(rec: dict, out_dir: Path) -> Path:
    safe = f"{rec['repo']}/{rec['commit'][:12]}/{rec['path']}".replace("..", "_")
    return out_dir / safe


def fetch_one(rec: dict, out_dir: Path, *, timeout: float = 15.0) -> bool:
    if not (rec.get("repo") and rec.get("commit") and rec.get("path")):
        return False
    dest = _local_path(rec, out_dir)
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        with urllib.request.urlopen(source_url(rec), timeout=timeout) as resp:
            dest.write_bytes(resp.read())
        return True
    except (urllib.error.URLError, OSError):
        return False


def fetch_all(manifest: dict, out_dir: str) -> dict:
    out = Path(out_dir)
    ok, missing = 0, []
    for rec in manifest.get("records", []):
        if fetch_one(rec, out):
            ok += 1
        else:
            missing.append(f"{rec.get('repo')}@{rec.get('commit', '')[:8]}:{rec.get('path')}")
    return {"fetched": ok, "missing": missing, "total": len(manifest.get("records", []))}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default="dist/benchmark/manifest.json")
    ap.add_argument("--out", default="dist/benchmark/src")
    args = ap.parse_args(argv)
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    res = fetch_all(manifest, args.out)
    print(f"[fetch] {res['fetched']}/{res['total']} 抓回；缺失 {len(res['missing'])}")
    for m in res["missing"]:
        print(f"  MISSING {m}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
