"""068 T007/T013：三厂商共识裁决（gold 2-of-3 + 3-of-3 子集 + silver 2-of-3 多数）。

见 specs/068-tri-vendor-gold/contracts/consensus.md。复用 067 已测的 decide_tables
（表 min-agree + 列交集弃权优先），新增：每边一致票数 agree + unanimous 子集过滤。
"""
from __future__ import annotations

from realeval.build_gold_b import decide_tables, filter_unanimous
from realeval.build_silver import build_record_consensus


def _rec(reads=None, writes=None):
    def _mk(xs):
        out = []
        for x in xs or []:
            if isinstance(x, tuple):
                out.append({"table": x[0], "columns": x[1]})
            else:
                out.append({"table": x, "columns": None})
        return out
    return {"reads": _mk(reads), "writes": _mk(writes)}


# ── gold：表级 2-of-3 / 3-of-3 一致票数 ──

def test_all_three_agree_table_in_gold_with_agree3():
    recs = [_rec(reads=["ods.a"]), _rec(reads=["ods.a"]), _rec(reads=["ods.a"])]
    g = decide_tables("select * from ods.a", recs, min_agree=2)
    assert [x["table"] for x in g["reads"]] == ["ods.a"]
    assert g["agree"]["ods.a"] == 3


def test_two_of_three_in_2of3_not_in_unanimous():
    # 两家有 ods.a、一家没有 → 2-of-3 入 gold（agree=2），3-of-3 子集剔除
    recs = [_rec(reads=["ods.a"]), _rec(reads=["ods.a"]), _rec(reads=["ods.b"])]
    g = decide_tables("select * from ods.a join ods.b", recs, min_agree=2)
    assert g["agree"].get("ods.a") == 2
    unan = filter_unanimous({"labels": {"reads": g["reads"], "writes": g["writes"]},
                             "consensus": {"agree": g["agree"]}}, n_teachers=3)
    assert [x["table"] for x in unan["labels"]["reads"]] == []  # ods.a agree=2 被剔


def test_single_teacher_table_excluded():
    recs = [_rec(reads=["ods.a"]), _rec(reads=[]), _rec(reads=[])]
    g = decide_tables("select * from ods.a", recs, min_agree=2)
    assert g["reads"] == [] and g["writes"] == []


def test_unanimous_keeps_only_agree3_edges():
    recs = [_rec(reads=["ods.a", "ods.b"]), _rec(reads=["ods.a", "ods.b"]), _rec(reads=["ods.a"])]
    g = decide_tables("select * from ods.a join ods.b", recs, min_agree=2)
    assert g["agree"]["ods.a"] == 3 and g["agree"]["ods.b"] == 2
    row = {"labels": {"reads": g["reads"], "writes": g["writes"]}, "consensus": {"agree": g["agree"]}}
    unan = filter_unanimous(row, n_teachers=3)
    assert [x["table"] for x in unan["labels"]["reads"]] == ["ods.a"]


# ── gold：列级三厂商交集弃权优先（延续 067） ──

def test_column_intersection_three_teachers():
    recs = [_rec(reads=[("ods.a", ["id", "amount"])]),
            _rec(reads=[("ods.a", ["id", "amount"])]),
            _rec(reads=[("ods.a", ["id", "ts"])])]
    g = decide_tables("select id from ods.a", recs, min_agree=2, columns=True)
    cols = next(x["columns"] for x in g["reads"] if x["table"] == "ods.a")
    assert cols == ["id"]  # {id,amount}∩{id,amount}∩{id,ts} = {id}


def test_column_abstain_when_one_null():
    # 表 2-of-3 入 gold，但只有一家给具体列 → <2 present → 弃权 None
    recs = [_rec(reads=[("ods.a", ["id"])]),
            _rec(reads=[("ods.a", None)]),
            _rec(reads=[("ods.a", None)])]
    g = decide_tables("select id from ods.a", recs, min_agree=2, columns=True)
    cols = next(x["columns"] for x in g["reads"] if x["table"] == "ods.a")
    assert cols is None


# ── silver：2-of-3 多数共识（无单 teacher 救回，守≥2 厂商） ──

def test_silver_two_of_three_majority_includes_table():
    # ods.a 被 2 家命名（第三家没有）→ 2-of-3 多数入 silver
    recs = [_rec(reads=["ods.a"]), _rec(reads=["ods.a"]), _rec(reads=[])]
    rec = build_record_consensus("h", "select * from ods.a", "SQL", recs, min_agree=2)
    assert [x["table"] for x in rec["reads"]] == ["ods.a"]


def test_silver_single_teacher_not_rescued():
    # 仅 1 家命名 ods.a（脚本字面有、AST 可定向）→ 068 共识**不**单家救回（区别 067 pair 路径）
    recs = [_rec(reads=["ods.a"]), _rec(reads=[]), _rec(reads=[])]
    rec = build_record_consensus("h", "select * from ods.a", "SQL", recs, min_agree=2)
    assert rec["reads"] == [] and rec["writes"] == [] and rec["is_empty"]


def test_silver_errored_teacher_abstains_still_forms_consensus():
    # 一家 error 弃权，另两家一致 → 仍成 2-of-3
    recs = [_rec(reads=["ods.a"]), _rec(reads=["ods.a"]), {"reads": [], "writes": [], "error": "no_json"}]
    rec = build_record_consensus("h", "select * from ods.a", "SQL", recs, min_agree=2)
    assert [x["table"] for x in rec["reads"]] == ["ods.a"]


def test_silver_columns_intersection():
    recs = [_rec(reads=[("ods.a", ["id", "amount"])]),
            _rec(reads=[("ods.a", ["id", "ts"])]),
            _rec(reads=[("ods.a", ["id", "amount"])])]
    rec = build_record_consensus("h", "select id from ods.a", "SQL", recs, min_agree=2, keep_columns=True)
    cols = next(x["columns"] for x in rec["reads"] if x["table"] == "ods.a")
    assert cols == ["id"]  # {id,amount}∩{id,ts}∩{id,amount} = {id}
