"""059 build_reasoning_corpus 单测：pro 思维链拒绝采样（离线纯函数）。"""
import json

from realeval.build_reasoning_corpus import matches, build


def _silver(reads=(), writes=(), is_empty=False):
    return {"reads": [{"table": t, "columns": None} for t in reads],
            "writes": [{"table": t, "columns": None} for t in writes],
            "is_empty": is_empty}


def _pro(reads=(), writes=(), reasoning="think...", error=None):
    return {"reads": [{"table": t, "columns": None} for t in reads],
            "writes": [{"table": t, "columns": None} for t in writes],
            "reasoning": reasoning, "error": error}


def test_matches_strict_requires_exact_rolemap():
    s = _silver(reads=["raw.a"], writes=["out.b"])
    assert matches(s, _pro(reads=["raw.a"], writes=["out.b"]), "strict")
    # pro 多出一张表 → strict 不通过
    assert not matches(s, _pro(reads=["raw.a", "extra.c"], writes=["out.b"]), "strict")
    # 方向翻转 → 不通过
    assert not matches(s, _pro(writes=["raw.a", "out.b"]), "strict")


def test_matches_superset_allows_pro_extra():
    s = _silver(reads=["raw.a"], writes=["out.b"])
    # pro 多找到一张表但银标的表都以正确方向命中 → superset 放行
    assert matches(s, _pro(reads=["raw.a", "extra.c"], writes=["out.b"]), "superset")
    # 银标某表 pro 漏 → superset 也不通过
    assert not matches(s, _pro(reads=["raw.a"]), "superset")


def test_build_keeps_only_agreeing_with_reasoning(tmp_path):
    silver = tmp_path / "silver.jsonl"
    pro = tmp_path / "m3.jsonl"
    rows_s = [
        {"chash": "h1", "task_type": "PYTHON", "content": "c1", **_silver(reads=["raw.a"], writes=["out.b"])},
        {"chash": "h2", "task_type": "PYTHON", "content": "c2", **_silver(writes=["out.z"])},   # pro 分歧
        {"chash": "h3", "task_type": "PYTHON", "content": "c3", **_silver(reads=["raw.x"])},     # pro 无思维链
        {"chash": "h4", "task_type": "PYTHON", "content": "c4", **_silver(is_empty=True)},        # 空样本跳过
    ]
    rows_p = [
        {"chash": "h1", **_pro(reads=["raw.a"], writes=["out.b"], reasoning="因为 INSERT ... FROM")},
        {"chash": "h2", **_pro(reads=["out.z"], reasoning="方向不同")},        # 方向翻转 → mismatch
        {"chash": "h3", **_pro(reads=["raw.x"], reasoning=None)},             # 无思维链 → 剔
    ]
    silver.write_text("\n".join(json.dumps(r) for r in rows_s), encoding="utf-8")
    pro.write_text("\n".join(json.dumps(r) for r in rows_p), encoding="utf-8")

    rows, stats = build(silver, pro, mode="strict")
    kept = {r["content"] for r in rows}
    assert kept == {"c1"}                      # 只有 h1 三方一致 + 有思维链
    assert stats["kept"] == 1
    assert stats["mismatch"] == 1              # h2
    assert stats["no_reasoning"] == 1          # h3
    assert stats["silver_nonempty"] == 3       # h4 空样本不计
    assert rows[0]["reasoning"] == "因为 INSERT ... FROM"


def test_build_pro_missing_counted(tmp_path):
    silver = tmp_path / "silver.jsonl"
    pro = tmp_path / "m3.jsonl"
    silver.write_text(json.dumps(
        {"chash": "hX", "task_type": "SHELL", "content": "cX", **_silver(writes=["t.out"])}), encoding="utf-8")
    pro.write_text("", encoding="utf-8")       # pro 对 hX 无记录
    rows, stats = build(silver, pro)
    assert rows == []
    assert stats["pro_missing"] == 1
