"""054 build_gold_b.decide_tables 单测：多 teacher 一致 auto-gold 裁决（离线纯函数）。"""
from realeval.build_gold_b import decide_tables, _role_map


def _rec(reads=(), writes=()):
    return {"reads": [{"table": t, "columns": None} for t in reads],
            "writes": [{"table": t, "columns": None} for t in writes]}


def test_unanimous_included_with_ast_direction():
    content = "INSERT INTO analytics.daily SELECT * FROM raw.users"
    recs = [_rec(reads=["raw.users"], writes=["analytics.daily"])] * 3
    out = decide_tables(content, recs, min_agree=3)
    reads = {e["table"] for e in out["reads"]}
    writes = {e["table"] for e in out["writes"]}
    assert "raw.users" in reads          # AST 锚定方向：读
    assert "analytics.daily" in writes   # AST 锚定方向：写
    assert out["n_agree_edges"] == 2


def test_min_agree_excludes_below_threshold():
    content = "INSERT INTO analytics.daily SELECT * FROM raw.users"
    # 仅 2 个 teacher 提到 raw.users，第 3 个漏。
    recs = [_rec(reads=["raw.users"], writes=["analytics.daily"]),
            _rec(reads=["raw.users"], writes=["analytics.daily"]),
            _rec(reads=[], writes=["analytics.daily"])]
    strict = decide_tables(content, recs, min_agree=3)
    assert "raw.users" not in {e["table"] for e in strict["reads"]}   # 全体一致门挡下
    assert "analytics.daily" in {e["table"] for e in strict["writes"]}  # 三家都有 → 保留
    relaxed = decide_tables(content, recs, min_agree=2)
    assert "raw.users" in {e["table"] for e in relaxed["reads"]}      # 2/3 门放行


def test_convention_a_gate_rejects_nonliteral():
    content = "INSERT INTO analytics.daily SELECT * FROM raw.users"
    # 三家都幻觉一个脚本里不存在的表名 → 约定 A 字面门应剔除。
    recs = [_rec(reads=["ghost.table"], writes=["analytics.daily"])] * 3
    out = decide_tables(content, recs, min_agree=3)
    names = {e["table"] for e in out["reads"] + out["writes"]}
    assert "ghost.table" not in names
    assert "analytics.daily" in names


def test_direction_disagreement_without_ast_dropped():
    # 表字面出现但不在可解析 SQL 语句里 → sql_direction 无该表；teacher 方向分歧 → 弃边。
    content = 'df.write.saveAsTable("mydb.out")\n# ref mydb.out again literally'
    recs = [_rec(reads=["mydb.out"]),
            _rec(writes=["mydb.out"]),
            _rec(reads=["mydb.out"])]
    out = decide_tables(content, recs, min_agree=3)
    names = {e["table"] for e in out["reads"] + out["writes"]}
    assert "mydb.out" not in names        # 方向分歧且 AST 定不了 → 宁缺毋滥


def test_role_map_write_priority():
    rm = _role_map(_rec(reads=["t"], writes=["t"]))
    assert rm["t"] == "w"                 # 同表读写并存 → 写优先
