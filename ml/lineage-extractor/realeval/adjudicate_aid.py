"""041-R 加强实验 A：真实集扩样裁决辅助（确定性，不调 LLM）。

对 tolabel 里**不在既有 gold** 的净新候选，套用约定 A（语句字面）的保守字面门，
自动提议 gold（reads/writes），并产出人工细审工作表。目的：把 91 条净新候选的
机械抽取/过滤交给确定性规则，人只对"非空提议 / 模型分歧 / 裸词命中"做证伪细审。

约定 A 过滤（对 M1∪M2 预测的每个 token）：
  1) 必须字面出现在脚本文本（大小写不敏感子串），否则丢——防幻觉/示例名；
  2) 含 `$`/`{`/`}` → 动态拼接，丢（规则 1）；
  3) 路径形态（含 `/`、文件扩展名、hdfs/s3/gs/file 前缀）→ 非表，丢；
  4) 临时视图（createOrReplaceTempView/registerTempTable/CREATE TEMP VIEW 定义名）→ 丢（规则 2）；
  5) 最长限定名优先：`a.b` 在则去裸 `b`（规则 6）；
  6) 方向：落在写候选集→writes，读候选集→reads，两者皆在→自环（都留）。
裸词（无 schema 点）保留但**标记 need_manual**——函数名/属性名假阳高发区（曾逮 sus2/self.args.output）。

用法：PYTHONPATH=. python3 realeval/adjudicate_aid.py \
        --gold realeval/gold/real.jsonl --tolabel realeval/tolabel \
        --worksheet <out.md> --proposal <out.jsonl>
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from eval.metrics import tables

_DYNAMIC = re.compile(r"[${}]")
_PATH_EXT = re.compile(r"\.(csv|parquet|json|txt|orc|avro|tsv|gz|log|dat|xml)$", re.I)
_SCHEME = re.compile(r"^(?:hdfs|s3|s3a|gs|file|wasb|abfss?|oss|dbfs)://", re.I)
_TEMPVIEW_TPL = [
    r"createOrReplaceTempView\(\s*['\"]{t}['\"]",
    r"createGlobalTempView\(\s*['\"]{t}['\"]",
    r"createTempView\(\s*['\"]{t}['\"]",
    r"registerTempTable\(\s*['\"]{t}['\"]",
    r"CREATE\s+(?:OR\s+REPLACE\s+)?(?:GLOBAL\s+)?TEMP(?:ORARY)?\s+(?:VIEW|TABLE)\s+{t}\b",
    r"WITH\s+{t}\s+AS\b",
]


def _is_path(tok: str) -> bool:
    return "/" in tok or bool(_PATH_EXT.search(tok)) or bool(_SCHEME.search(tok))


def _is_tempview(tok: str, content: str) -> bool:
    esc = re.escape(tok)
    for tpl in _TEMPVIEW_TPL:
        if re.search(tpl.format(t=esc), content, re.I):
            return True
    return False


def _literal(tok: str, content: str) -> bool:
    return tok.lower() in content.lower()


def _reject_reason(tok: str, content: str) -> str | None:
    """返回丢弃原因；None 表示通过约定 A 字面门。"""
    if not tok or not tok.strip():
        return "空"
    if _DYNAMIC.search(tok):
        return "动态拼接($/{})"
    if _is_path(tok):
        return "路径/文件非表"
    if not _literal(tok, content):
        return "非字面(幻觉/示例名)"
    if _is_tempview(tok, content):
        return "临时视图/CTE"
    return None


def _dedup_longest(toks: set[str]) -> set[str]:
    """规则 6：`a.b` 在则去裸叶 `b`（同物理表更长限定名优先）。"""
    out = set(toks)
    leaves = {t.split(".")[-1]: t for t in toks if "." in t}
    for t in list(out):
        if "." not in t and t in leaves:
            out.discard(t)
    return out


def _pred_tokens(pred: dict, side: str) -> set[str]:
    return set(tables(pred.get(side)))


def _statement_lines(tok: str, content: str, limit: int = 3) -> list[str]:
    esc = re.escape(tok)
    hits = []
    for ln in content.splitlines():
        if re.search(esc, ln, re.I):
            hits.append(ln.strip()[:160])
            if len(hits) >= limit:
                break
    return hits


def adjudicate(rec: dict) -> dict:
    content = rec["content"]
    preds = rec["prelabel"]["predictions"]
    m1, m2 = preds.get("m1", {}), preds.get("m2", {})

    read_cand = _pred_tokens(m1, "reads") | _pred_tokens(m2, "reads")
    write_cand = _pred_tokens(m1, "writes") | _pred_tokens(m2, "writes")

    kept_reads, kept_writes, rejected = {}, {}, {}
    for tok in read_cand | write_cand:
        reason = _reject_reason(tok, content)
        if reason:
            rejected[tok] = reason
            continue
        if tok in write_cand:
            kept_writes[tok] = _statement_lines(tok, content)
        if tok in read_cand:
            kept_reads[tok] = _statement_lines(tok, content)

    kr = _dedup_longest(set(kept_reads))
    kw = _dedup_longest(set(kept_writes))

    bareword = sorted(t for t in kr | kw if "." not in t)
    disagree = (_pred_tokens(m1, "reads") != _pred_tokens(m2, "reads")
                or _pred_tokens(m1, "writes") != _pred_tokens(m2, "writes"))
    nonempty = bool(kr or kw)
    need_manual = nonempty or disagree or bool(bareword)

    return {
        "path": rec.get("source", {}).get("path", "?"),
        "repo": rec.get("source", {}).get("repo", "?"),
        "density": rec.get("meta", {}).get("literal_density"),
        "content": content,
        "m1": {"reads": sorted(_pred_tokens(m1, "reads")), "writes": sorted(_pred_tokens(m1, "writes"))},
        "m2": {"reads": sorted(_pred_tokens(m2, "reads")), "writes": sorted(_pred_tokens(m2, "writes"))},
        "proposal": {
            "reads": sorted(kr),
            "writes": sorted(kw),
            "read_stmts": {t: kept_reads[t] for t in kr},
            "write_stmts": {t: kept_writes[t] for t in kw},
        },
        "rejected": rejected,
        "bareword": bareword,
        "disagree": disagree,
        "nonempty": nonempty,
        "need_manual": need_manual,
    }


def _gold_row(a: dict) -> dict:
    """把裁决提议转成 gold 行格式（columns 一律 null；密集列级不在真实集范围）。"""
    return {
        "content": a["content"],
        "task_type": "python" if str(a["path"]).endswith(".py") else "shell",
        "labels": {
            "reads": [{"table": t, "columns": None} for t in a["proposal"]["reads"]],
            "writes": [{"table": t, "columns": None} for t in a["proposal"]["writes"]],
        },
        "meta": {
            "template_id": a["path"],
            "form_family": "real",
            "source_dataset": "real",
            "rule_covered": True,
            "literal_density": a["density"],
            "needs_review": a["need_manual"],
        },
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real.jsonl")
    ap.add_argument("--tolabel", default="realeval/tolabel")
    ap.add_argument("--worksheet", required=True)
    ap.add_argument("--proposal", required=True)
    args = ap.parse_args(argv)

    gold = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    gold_contents = {g["content"] for g in gold}

    idx = json.loads((Path(args.tolabel) / "_index.json").read_text(encoding="utf-8"))
    new = []
    for e in idx:
        rec = json.loads((Path(args.tolabel) / e["filename"]).read_text(encoding="utf-8"))
        if rec["content"] in gold_contents:
            continue
        new.append(adjudicate(rec))

    # 提议 gold（净新）落 jsonl
    Path(args.proposal).write_text(
        "\n".join(json.dumps(_gold_row(a), ensure_ascii=False) for a in new) + "\n",
        encoding="utf-8")

    manual = [a for a in new if a["need_manual"]]
    auto_empty = [a for a in new if not a["need_manual"]]

    lines = [
        "# 041-R 扩样裁决工作表（净新候选，约定 A 自动提议）", "",
        f"- 净新候选：{len(new)}（其中 need_manual={len(manual)}，auto-∅={len(auto_empty)}）",
        f"- 自动提议非空：{sum(1 for a in new if a['nonempty'])}",
        "- 人工只需对下方 need_manual 逐条证伪：核字面语句、剔函数名/属性假阳、定方向。",
        "- 提议 gold 已落 `%s`（need_manual 行 needs_review=true）。" % args.proposal,
        "", "---", "",
        "## 需人工细审（need_manual）", "",
    ]
    for i, a in enumerate(manual):
        lines.append(f"### M{i:02d} `{a['path']}` @ {a['repo']} · density={a['density']}"
                     + ("  ⚠︎裸词" if a["bareword"] else "")
                     + ("  ⚠︎M1≠M2" if a["disagree"] else ""))
        lines.append(f"- M1 R={a['m1']['reads']} W={a['m1']['writes']}")
        lines.append(f"- M2 R={a['m2']['reads']} W={a['m2']['writes']}")
        lines.append(f"- **提议 R={a['proposal']['reads']} W={a['proposal']['writes']}**")
        for t, ss in a["proposal"]["read_stmts"].items():
            lines.append(f"  - R `{t}`: {ss}")
        for t, ss in a["proposal"]["write_stmts"].items():
            lines.append(f"  - W `{t}`: {ss}")
        if a["rejected"]:
            lines.append(f"- 已弃：{a['rejected']}")
        lines.append("")

    lines += ["---", "", "## 自动判 ∅（模型一致且无字面表，抽查即可）", ""]
    for a in auto_empty:
        lines.append(f"- `{a['path']}` @ {a['repo']} · density={a['density']}"
                     + (f" · 已弃 {list(a['rejected'])}" if a["rejected"] else ""))

    Path(args.worksheet).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"净新 {len(new)}：need_manual {len(manual)} / auto-∅ {len(auto_empty)}；"
          f"提议非空 {sum(1 for a in new if a['nonempty'])}")
    print(f"工作表 → {args.worksheet}")
    print(f"提议 gold → {args.proposal}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
