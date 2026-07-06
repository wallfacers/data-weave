"""050 Tier 0：dump_and_score.score_preds 纯函数单测（无 GPU）。"""
from __future__ import annotations

from realeval.dump_and_score import score_preds


def _pair(gr, gw, pr, pw, content):
    return {"content": content,
            "gold": {"reads": [{"table": t} for t in gr], "writes": [{"table": t} for t in gw]},
            "pred": {"reads": [{"table": t} for t in pr], "writes": [{"table": t} for t in pw]}}


def test_canon_lifts_precision_over_plain():
    pairs = [
        _pair(["agg_events"], [], ["staging.agg_events"], [], "insert into staging.agg_events select 1"),
        _pair(["orders"], [], ["orders"], [], "select * from orders"),
    ]
    plain = score_preds(pairs, canon=False)
    canon = score_preds(pairs, canon=True)
    assert canon["full"]["precision"] > plain["full"]["precision"]   # 假象被修正 → precision 抬升
    assert canon["full"]["recall"] >= plain["full"]["recall"]


def test_nonempty_subset_excludes_empty_gold():
    pairs = [
        _pair([], [], ["x"], [], "print(1)"),          # 空 gold（模型过度抽取）
        _pair(["a"], [], ["a"], [], "from a"),         # 非空
    ]
    r = score_preds(pairs, canon=False)
    assert r["nonempty_n"] == 1                          # 只 1 条非空
    assert r["full"]["precision"] < 1.0                  # 空行的过度抽取拖累全集 precision


def test_plain_matches_exact_semantics():
    pairs = [_pair(["a"], ["b"], ["a"], ["b"], "insert into b select from a")]
    r = score_preds(pairs, canon=False)
    assert r["full"]["precision"] == 1.0 and r["full"]["recall"] == 1.0
    assert r["full"]["direction_acc"] == 1.0
