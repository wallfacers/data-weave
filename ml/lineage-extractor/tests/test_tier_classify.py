"""063 T009：置信度分层 classify_tiers 单测（真实夹具，纯函数无 GPU）。

覆盖 contracts/tier-classify.md 断言点：sql_qual 进 auto、sql_bare 进 review、
model-only 漏抽经 SQL 补回进 review、canon 去重、空输入 tiered=False、thr 边界、
review 降序、agree 方向取 SQL-AST。
"""
from realeval.tier_classify import classify_tiers


def _names(bucket):
    return {i["table"] for i in bucket["reads"] + bucket["writes"]}


def test_empty_input_not_tiered():
    out = classify_tiers({"reads": [], "writes": []}, "echo hi; python x.py")
    assert out["auto"] == {"reads": [], "writes": []}
    assert out["review"] == {"reads": [], "writes": []}
    assert out["tiered"] is False


def test_governance_thr_only_sql_qual_auto():
    # 治理诚实（CV 去偏）：thr=0.95 采纳集只有 sql_qual；agree 点估计 0.87、CV 更差 → 复核
    content = 'spark.sql("INSERT OVERWRITE TABLE db.dwd SELECT * FROM db.ods")'
    out = classify_tiers({"reads": [{"table": "db.ods"}], "writes": []}, content, thr=0.95)
    # db.dwd = SQL-only 限定名 = sql_qual → auto（写方向，INSERT target）
    assert any(i["table"] == "db.dwd" for i in out["auto"]["writes"])
    # db.ods = 模型∩SQL = agree → 不达 0.95 治理阈 → 复核层（召回靠人工兜）
    assert "db.ods" in _names(out["review"])
    assert "db.ods" not in _names(out["auto"])


def test_model_only_missed_table_recovered_to_review():
    # SQL 通道抽出模型漏掉的限定名真表 → model_qual 不在 0.95 采纳集 → 进复核（召回回收）
    content = 'spark.sql("SELECT * FROM warehouse.orders")\n# model missed it'
    out = classify_tiers({"reads": [], "writes": []}, content, thr=0.95)
    all_names = _names(out["auto"]) | _names(out["review"])
    assert "warehouse.orders" in all_names  # 被 SQL 通道 surface，未静默丢


def test_sql_bare_goes_review():
    # 裸名 SQL（低置信 sql_bare）→ 不进 0.95 auto
    content = 'spark.sql("SELECT * FROM orders")'
    out = classify_tiers({"reads": [], "writes": []}, content, thr=0.95)
    assert "orders" in _names(out["review"])
    assert "orders" not in _names(out["auto"])


def test_canon_dedup_qualified_and_bare():
    # 模型 'orders' + SQL 'db.orders' → canon 合并为一条 agree，不重复计数
    content = 'spark.sql("SELECT * FROM db.orders")'
    out = classify_tiers({"reads": [{"table": "orders"}], "writes": []}, content, thr=0.0)
    names = _names(out["auto"]) | _names(out["review"])
    # 只出现一次（canon 合并）：总候选数 == 1
    total = len(out["auto"]["reads"]) + len(out["auto"]["writes"]) + \
        len(out["review"]["reads"]) + len(out["review"]["writes"])
    assert total == 1


def test_thr_zero_all_auto():
    content = 'spark.sql("SELECT * FROM orders")'
    out = classify_tiers({"reads": [], "writes": []}, content, thr=0.0)
    assert "orders" in _names(out["auto"])
    assert out["review"] == {"reads": [], "writes": []}


def test_review_sorted_by_confidence_desc():
    # 多级候选进复核：agree(0.87) 应排在 model_qual(0.815) 前
    content = 'spark.sql("SELECT a FROM db.agreed JOIN db.modelonly")'
    # 模型抽 db.agreed（→与 SQL agree）+ db.extra（model_qual，SQL 未命中）
    out = classify_tiers(
        {"reads": [{"table": "db.agreed"}, {"table": "db.extra"}], "writes": []},
        content, thr=0.99)  # thr=0.99 → 仅 sql_qual 进 auto，其余进复核
    confs = [i["confidence"] for i in out["review"]["reads"]]
    assert confs == sorted(confs, reverse=True)


def test_agree_direction_from_sql_ast():
    # 模型说 db.t 是读，SQL AST 说是写（INSERT target）→ agree 取 SQL-AST=写
    content = 'spark.sql("INSERT OVERWRITE TABLE db.t SELECT * FROM db.src")'
    out = classify_tiers({"reads": [{"table": "db.t"}], "writes": []}, content, thr=0.0)
    # db.t 应在 writes（SQL-AST target 锚定），非 reads
    assert any(i["table"] == "db.t" for i in out["auto"]["writes"])
    assert not any(i["table"] == "db.t" for i in out["auto"]["reads"])


def test_deterministic():
    content = 'spark.sql("INSERT INTO db.t SELECT * FROM db.s")'
    a = classify_tiers({"reads": [{"table": "db.s"}], "writes": []}, content)
    b = classify_tiers({"reads": [{"table": "db.s"}], "writes": []}, content)
    assert a == b
