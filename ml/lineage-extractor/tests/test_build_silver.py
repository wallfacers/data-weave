"""T013: 银标构建单测（contracts/silver-label 5 不变量）。"""
import json

from realeval.build_silver import build, build_record

SYNTH = {"dws.dws_member_point_di", "ads.ads_payments_delta"}


def _rec(reads=None, writes=None, error=None):
    return {"reads": [{"table": t} for t in (reads or [])],
            "writes": [{"table": t} for t in (writes or [])], "error": error}


def test_intersection_kept_with_ast_direction():
    content = 'spark.sql("INSERT OVERWRITE TABLE dwd.clean SELECT * FROM ods.orders")'
    m1 = _rec(reads=["ods.orders", "dwd.clean"])          # m1 方向抽反
    m2 = _rec(reads=["ods.orders"], writes=["dwd.clean"])  # m2 正确
    rec = build_record("h1", content, "PYTHON", m1, m2, SYNTH)
    w = {i["table"] for i in rec["writes"]}
    r = {i["table"] for i in rec["reads"]}
    assert "dwd.clean" in w and "ods.orders" in r          # AST 定方向
    assert rec["dir_source"] == "ast"


def test_literal_gate_drops_absent_table():
    content = 'spark.sql("SELECT * FROM ods.orders")'
    m1 = _rec(reads=["ods.orders", "dyn.not_in_script"])
    m2 = _rec(reads=["ods.orders", "dyn.not_in_script"])
    rec = build_record("h", content, "PYTHON", m1, m2, SYNTH)
    names = {i["table"] for i in rec["reads"] + rec["writes"]}
    assert "ods.orders" in names
    assert "dyn.not_in_script" not in names                # 字面门滤除


def test_zero_synth_names():
    content = "SELECT * FROM dws.dws_member_point_di"      # 合成名即便字面出现也剔
    m1 = _rec(reads=["dws.dws_member_point_di"])
    m2 = _rec(reads=["dws.dws_member_point_di"])
    rec = build_record("h", content, "PYTHON", m1, m2, SYNTH)
    names = {i["table"] for i in rec["reads"] + rec["writes"]}
    assert names == set()                                  # 零合成名
    assert rec["is_empty"] is True


def test_disagreement_rescued_only_with_ast():
    content = 'spark.sql("INSERT INTO dwd.t SELECT * FROM ods.s")\n# only m1 mentions ods.s'
    m1 = _rec(reads=["ods.s"], writes=["dwd.t"])
    m2 = _rec(writes=["dwd.t"])                             # m2 漏了 ods.s（分歧）
    rec = build_record("h", content, "PYTHON", m1, m2, SYNTH)
    r = {i["table"] for i in rec["reads"]}
    assert "ods.s" in r                                    # AST 可定方向 → 救回
    assert rec["provenance"] == "disagreement_rescued"


def test_teacher_error_skipped():
    assert build_record("h", "x", "PY", _rec(error="call:timeout"), _rec(), SYNTH) is None


def test_empty_ratio_and_contamination(tmp_path):
    # 造 pool：8 非空 + 20 空；金标含 1 条 → 污染剔除
    pool = tmp_path / "pool"; pool.mkdir()
    labels = tmp_path / "labels"; labels.mkdir()
    m1f = (labels / "m1.jsonl").open("w"); m2f = (labels / "m2.jsonl").open("w")
    from realeval.hashutil import content_hash
    contaminated = 'SELECT * FROM ods.contam'
    for i in range(8):
        c = f'spark.sql("INSERT INTO dwd.t{i} SELECT * FROM ods.s{i}")'
        (pool / f"n{i}.json").write_text(json.dumps({"content": c, "source": "{'path':'j.py'}"}))
        ch = content_hash(c)
        rec = {"chash": ch, "reads": [{"table": f"ods.s{i}"}], "writes": [{"table": f"dwd.t{i}"}], "error": None}
        m1f.write(json.dumps(rec) + "\n"); m2f.write(json.dumps(rec) + "\n")
    for i in range(20):
        c = f"echo nothing here {i}; python run.py"
        (pool / f"e{i}.json").write_text(json.dumps({"content": c, "source": "{'path':'j.sh'}"}))
        ch = content_hash(c)
        rec = {"chash": ch, "reads": [], "writes": [], "error": None}
        m1f.write(json.dumps(rec) + "\n"); m2f.write(json.dumps(rec) + "\n")
    # 污染样本
    (pool / "contam.json").write_text(json.dumps({"content": contaminated, "source": "{'path':'j.py'}"}))
    chc = content_hash(contaminated)
    m1f.write(json.dumps({"chash": chc, "reads": [{"table": "ods.contam"}], "writes": [], "error": None}) + "\n")
    m2f.write(json.dumps({"chash": chc, "reads": [{"table": "ods.contam"}], "writes": [], "error": None}) + "\n")
    m1f.close(); m2f.close()
    gold = tmp_path / "gold.jsonl"
    gold.write_text(json.dumps({"content": contaminated, "reads": [{"table": "ods.contam"}], "writes": []}) + "\n")

    recs = build(pool, labels, [str(gold)], SYNTH, empty_ratio=0.20)
    names = {i["table"] for r in recs for i in r["reads"] + r["writes"]}
    assert "ods.contam" not in names                       # 污染护栏
    ne = sum(1 for r in recs if not r["is_empty"])
    ratio = (len(recs) - ne) / len(recs)
    assert 0.15 <= ratio <= 0.25                            # 配比
