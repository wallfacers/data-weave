"""068 US6 双专家 serving 融合单测（TDD 先行）。

绕开 3B/LoRA 容量墙：推理期把表专家(run-tri-3b-col50, 表 R 0.776)与列专家(run-tri-3b,
列 F1 0.93)组合——表集取自表专家(或并集)，每表列从列专家嫁接(命中则)否则回退表专家。

门①（表/列打分物理隔离）在评测侧由 eval.metrics 保证；融合发生在打分前，故本模块只重排
reads/writes 的表与列，不触碰 metrics。核心不变量：
  · strategy='table' 表集逐字节 = 表专家表集（表级 counts 不受融合扰动）；
  · fuse(X, X) 恒等复现 X（幂等自反）；
  · 列从列专家嫁接（含 canon 限定名匹配），缺则回退，皆无 → null。
"""
import json
from pathlib import Path

from realeval.specialist_fusion import fuse
from eval.metrics import aggregate, score_row, tables


# ── 小工具：从 {reads/writes} 取某表某 role 的列 ──
def _cols(pred, role, table):
    for it in pred[role]:
        if it["table"].lower() == table.lower():
            return it.get("columns")
    raise KeyError(table)


def _tset(pred, role):
    return {it["table"].lower() for it in pred[role]}


# ── 策略 table(A)：表集 = 表专家 ──
def test_table_strategy_keeps_table_specialist_tables():
    tbl = {"reads": [{"table": "src", "columns": ["a"]}], "writes": [{"table": "dst", "columns": None}]}
    col = {"reads": [{"table": "src", "columns": ["a", "b"]}, {"table": "extra", "columns": ["z"]}], "writes": []}
    out = fuse(tbl, col, strategy="table")
    assert _tset(out, "reads") == {"src"}          # 不引入列专家的 extra
    assert _tset(out, "writes") == {"dst"}


def test_union_strategy_unions_both_table_sets():
    tbl = {"reads": [{"table": "src", "columns": None}], "writes": []}
    col = {"reads": [{"table": "src", "columns": ["a"]}, {"table": "extra", "columns": ["z"]}], "writes": []}
    out = fuse(tbl, col, strategy="union")
    assert _tset(out, "reads") == {"src", "extra"}  # 并集，含列专家独有的 extra


# ── 列嫁接 ──
def test_grafts_columns_from_column_specialist_when_both_predict():
    tbl = {"reads": [{"table": "orders", "columns": ["id"]}], "writes": []}
    col = {"reads": [{"table": "orders", "columns": ["id", "amount", "ts"]}], "writes": []}
    out = fuse(tbl, col, strategy="table")
    assert set(_cols(out, "reads", "orders")) == {"id", "amount", "ts"}  # 用列专家的富列


def test_falls_back_to_table_cols_when_column_specialist_missing_table():
    tbl = {"reads": [{"table": "orders", "columns": ["id"]}, {"table": "lonely", "columns": ["x"]}], "writes": []}
    col = {"reads": [{"table": "orders", "columns": ["id", "amount"]}], "writes": []}
    out = fuse(tbl, col, strategy="table")
    assert set(_cols(out, "reads", "orders")) == {"id", "amount"}  # 命中 → 列专家
    assert _cols(out, "reads", "lonely") == ["x"]                  # 未命中 → 回退表专家


def test_columns_null_when_neither_specialist_has_columns():
    tbl = {"reads": [{"table": "t", "columns": None}], "writes": []}
    col = {"reads": [{"table": "t", "columns": None}], "writes": []}
    out = fuse(tbl, col, strategy="table")
    assert _cols(out, "reads", "t") is None


def test_column_graft_matches_qualified_name_via_canon():
    # 表专家用短名 orders，列专家用限定名 db.orders → canon 尾段匹配，列仍嫁接
    tbl = {"reads": [{"table": "orders", "columns": None}], "writes": []}
    col = {"reads": [{"table": "db.orders", "columns": ["amount"]}], "writes": []}
    out = fuse(tbl, col, strategy="table")
    assert set(_cols(out, "reads", "orders")) == {"amount"}


# ── role 隔离 ──
def test_roles_are_independent():
    tbl = {"reads": [{"table": "r", "columns": None}], "writes": [{"table": "w", "columns": None}]}
    col = {"reads": [{"table": "w", "columns": ["c"]}], "writes": [{"table": "r", "columns": ["c"]}]}
    out = fuse(tbl, col, strategy="table")
    # 表专家把 r 判读、w 判写；列专家 role 相反 → table 策略下不跨 role 嫁接
    assert _tset(out, "reads") == {"r"} and _tset(out, "writes") == {"w"}
    assert _cols(out, "reads", "r") is None      # 列专家的 r 在 writes，不跨 role
    assert _cols(out, "writes", "w") is None


# ── 幂等自反：fuse(X, X, table) 表集与列都还原 X ──
def test_fuse_self_is_identity_for_tables():
    x = {"reads": [{"table": "a", "columns": ["c1"]}, {"table": "b", "columns": None}],
         "writes": [{"table": "d", "columns": ["c2"]}]}
    out = fuse(x, x, strategy="table")
    for role in ("reads", "writes"):
        assert _tset(out, role) == _tset(x, role)
        for it in x[role]:
            assert _cols(out, role, it["table"]) == it["columns"] or (
                _cols(out, role, it["table"]) is None and it["columns"] is None)


# ── 确定性输出顺序（可复现门）──
def test_output_is_deterministically_ordered():
    tbl = {"reads": [{"table": "z", "columns": None}, {"table": "a", "columns": None}], "writes": []}
    col = {"reads": [], "writes": []}
    o1 = fuse(tbl, col, strategy="table")
    o2 = fuse(tbl, col, strategy="table")
    assert o1 == o2
    assert [it["table"] for it in o1["reads"]] == sorted(it["table"] for it in o1["reads"])


# ── 门①：融合不扰表级 counts（table 策略下表 tp/fp/fn 恒 = 表专家 solo）──
def test_gate1_table_counts_unchanged_by_fusion():
    gold = {"reads": [{"table": "orders", "columns": ["id"]}], "writes": []}
    content = "select id from orders"
    tbl = {"reads": [{"table": "orders", "columns": None}], "writes": []}
    col = {"reads": [{"table": "orders", "columns": ["id"]}, {"table": "ghost", "columns": ["q"]}], "writes": []}
    solo = score_row(gold, tbl, content)
    fused = score_row(gold, fuse(tbl, col, strategy="table"), content)
    for k in ("tp", "fp", "fn", "halluc"):   # 表级 counts 逐字节相等
        assert solo[k] == fused[k], k


# ── 与真实 preds 端到端：fuse 表策略下表指标 = 表专家、且列 F1 应≥表专家（嫁接不会更差）──
def test_end_to_end_fusion_lifts_columns_without_hurting_tables():
    root = Path(__file__).resolve().parent.parent
    gold_p = root / "realeval/gold/real-c-tri.jsonl"
    tp = root / "out/preds-tri/run-tri-3b-col50.jsonl"   # 表专家
    cp = root / "out/preds-tri/run-tri-3b.jsonl"          # 列专家
    if not (gold_p.exists() and tp.exists() and cp.exists()):
        import pytest
        pytest.skip("preds/gold 不在此环境（gitignored）")
    gold = [json.loads(l) for l in gold_p.read_text().splitlines() if l.strip()]
    tpreds = [json.loads(l) for l in tp.read_text().splitlines() if l.strip()]
    cpreds = [json.loads(l) for l in cp.read_text().splitlines() if l.strip()]
    ne = [(g, t, c) for g, t, c in zip(gold, tpreds, cpreds)
          if tables(g["labels"]["reads"]) or tables(g["labels"]["writes"])]
    solo_tbl = aggregate([score_row(g["labels"], {"reads": t["reads"], "writes": t["writes"]}, g["content"]) for g, t, _ in ne])
    fused = aggregate([score_row(g["labels"], fuse(t, c, strategy="table"), g["content"]) for g, t, c in ne])
    # 表级不受损（table 策略）
    assert abs(fused["recall"] - solo_tbl["recall"]) < 1e-9
    assert abs(fused["precision"] - solo_tbl["precision"]) < 1e-9
    # 列 F1 被列专家嫁接抬高（严格大于表专家 solo 的 0.578）
    assert fused["col_f1"] > solo_tbl["col_f1"] + 0.05
