"""050 Tier 1：合并策略 merge 纯函数单测（无 GPU）。锁住 dir_fix = 模型定表、AST 定方向。"""
from __future__ import annotations

from realeval.offline_hybrid import merge


def _p(reads, writes):
    return {"reads": [{"table": t} for t in reads], "writes": [{"table": t} for t in writes]}


def _names(out, k):
    return sorted(t["table"] for t in out[k])


def test_dir_fix_corrects_model_direction_with_ast():
    """模型把 t 判成读、s 判成写（方向反）；SQL 通道正确（t=写 s=读）→ dir_fix 纠正之。"""
    model = _p(["t"], ["s"])          # 反了
    sql = _p(["s"], ["t"])            # AST 正确
    out = merge("dir_fix", model, sql)
    assert _names(out, "writes") == ["t"] and _names(out, "reads") == ["s"]


def test_dir_fix_keeps_model_only_tables():
    """SQL 通道没识别到的表，dir_fix 保留模型的判定（不丢召回）。"""
    model = _p(["a", "b"], ["c"])
    sql = _p([], ["c"])              # 只识别到 c
    out = merge("dir_fix", model, sql)
    assert _names(out, "reads") == ["a", "b"] and _names(out, "writes") == ["c"]


def test_override_replaces_entirely():
    model = _p(["a", "b"], ["c"])
    sql = _p(["x"], ["y"])
    out = merge("override", model, sql)
    assert _names(out, "reads") == ["x"] and _names(out, "writes") == ["y"]


def test_union_sql_direction_precedence():
    """并集：模型把 t 当读、SQL 当写 → 并集里 t 归写（SQL 方向优先）。"""
    model = _p(["t", "m"], [])
    sql = _p([], ["t"])
    out = merge("union", model, sql)
    assert "t" in _names(out, "writes") and "t" not in _names(out, "reads")
    assert "m" in _names(out, "reads")


def test_falls_to_model_when_no_sql():
    model = _p(["a"], ["b"])
    for strat in ("override", "union", "dir_fix"):
        out = merge(strat, model, _p([], []))
        assert _names(out, "reads") == ["a"] and _names(out, "writes") == ["b"]
