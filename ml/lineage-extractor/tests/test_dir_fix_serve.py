"""sidecar postprocess 单测（dir_fix / 语义 grounding / 063 置信度分层）。

只测纯函数 postprocess——不加载模型/GPU（serve.app 顶部 import torch 但 postprocess 不用它）。
既有 grounding/dir_fix 测试用 `tiering=False` 隔离旧管线语义；063 分层测试单列。
"""
from serve.app import postprocess


def _names(out, key):
    return {i["table"] for i in out[key]}


# ---- grounding + dir_fix（旧管线，tiering=False 隔离） ----

def test_dir_fix_not_override():
    content = 'spark.sql("SELECT a FROM ods.orders")\ntarget = "dwd_extra"'
    text = '{"reads": [{"table": "ods.orders", "columns": null}, {"table": "dwd_extra", "columns": null}], "writes": []}'
    out = postprocess(text, content, tiering=False)
    names = {i["table"] for i in out["reads"] + out["writes"]}
    assert "dwd_extra" in names  # grounded 的模型独有表未丢


def test_dir_fix_corrects_direction():
    content = 'spark.sql("INSERT OVERWRITE TABLE dwd.clean SELECT * FROM ods.orders")'
    text = '{"reads": [{"table": "dwd.clean"}, {"table": "ods.orders"}], "writes": []}'
    out = postprocess(text, content, tiering=False)
    assert "dwd.clean" in {i["table"] for i in out["writes"]}  # AST 纠为写
    assert out["dir_fixed"] is True


def test_abstain_empty():
    out = postprocess('{"reads": [], "writes": []}', "echo hi; python x.py", tiering=False)
    assert out["reads"] == [] and out["writes"] == []
    assert out["dir_fixed"] is False


def test_malformed_model_output():
    out = postprocess("sorry I cannot help", "SELECT * FROM t", tiering=False)
    assert out["reads"] == [] and out["writes"] == []


def test_huge_malformed_script_no_hang():
    content = 'x = "SELECT * FROM {{ p.t }} WHERE $CONDITIONS"\n' + ("y" * 60000) + ";"
    text = '{"reads": [{"table": "real.t"}], "writes": []}'
    out = postprocess(text, content, tiering=False)
    assert {i["table"] for i in out["reads"]} == {"real.t"}


def test_deterministic_same_input_same_output():
    content = 'spark.sql("INSERT INTO dwd.t SELECT * FROM ods.s")'
    text = '{"reads": [{"table": "ods.s"}], "writes": [{"table": "dwd.t"}]}'
    a = postprocess(text, content)
    b = postprocess(text, content)
    assert a == b


def test_grounding_drops_hallucination():
    content = 'spark.sql("SELECT a FROM ods.orders")'
    text = '{"reads": [{"table": "ods.orders"}, {"table": "phantom_tbl"}], "writes": []}'
    out = postprocess(text, content, tiering=False)
    names = {i["table"] for i in out["reads"] + out["writes"]}
    assert "phantom_tbl" not in names
    assert "ods.orders" in names
    assert out["grounded"] is True


def test_grounding_drops_comment_only_table():
    content = '# insert into audit_log\nspark.sql("SELECT 1 FROM ods.real")'
    text = '{"reads": [{"table": "ods.real"}], "writes": [{"table": "audit_log"}]}'
    out = postprocess(text, content, tiering=False)
    names = {i["table"] for i in out["reads"] + out["writes"]}
    assert "audit_log" not in names
    assert "ods.real" in names


def test_grounding_keeps_real_table():
    content = 'spark.sql("SELECT * FROM ods.real_tbl")'
    text = '{"reads": [{"table": "ods.real_tbl"}], "writes": []}'
    out = postprocess(text, content, tiering=False)
    assert "ods.real_tbl" in {i["table"] for i in out["reads"]}
    assert out["grounded"] is False


def test_grounding_can_be_disabled():
    content = 'spark.sql("SELECT a FROM ods.orders")'
    text = '{"reads": [{"table": "ods.orders"}, {"table": "phantom_tbl"}], "writes": []}'
    out = postprocess(text, content, ground=False, tiering=False)
    assert "phantom_tbl" in {i["table"] for i in out["reads"]}
    assert out["grounded"] is False


# ---- 063 US1：置信度分层接进 serving（复核层召回回收） ----

def test_tiering_response_has_review_lists():
    # 分层默认开：响应含 review_* 键
    content = 'spark.sql("SELECT * FROM ods.orders")'
    text = '{"reads": [{"table": "ods.orders"}], "writes": []}'
    out = postprocess(text, content)
    assert "review_reads" in out and "review_writes" in out and "tiered" in out


def test_tiering_agree_table_goes_review_at_governance():
    # 模型∩SQL=agree，thr=0.95 治理阈只采纳 sql_qual → agree 表进复核层（不静默丢）
    content = 'spark.sql("SELECT * FROM ods.orders")'
    text = '{"reads": [{"table": "ods.orders"}], "writes": []}'
    out = postprocess(text, content, thr=0.95)
    all_names = _names(out, "reads") | _names(out, "writes") | \
        _names(out, "review_reads") | _names(out, "review_writes")
    assert "ods.orders" in all_names  # 召回回收：surface 到 review，未丢
    assert out["tiered"] is True


def test_tiering_recovers_sql_missed_qualified_to_auto():
    # 模型漏抽、SQL-AST 解析的限定名真表 → sql_qual（治理阈内可信）→ 进 auto，召回回收
    content = 'spark.sql("SELECT * FROM warehouse.orders")'
    out = postprocess('{"reads": [], "writes": []}', content, thr=0.95)
    assert "warehouse.orders" in _names(out, "reads")  # sql_qual 直接自动采纳


def test_tiering_recovers_sql_missed_bare_to_review():
    # 模型漏抽、SQL-AST 解析的裸名 → sql_bare（低置信）→ 进复核层，召回回收进人工队列
    content = 'spark.sql("SELECT * FROM lonely_orders")'
    out = postprocess('{"reads": [], "writes": []}', content, thr=0.95)
    review_names = _names(out, "review_reads") | _names(out, "review_writes")
    assert "lonely_orders" in review_names


def test_tiering_review_sorted_by_confidence():
    content = 'spark.sql("SELECT a FROM db.one JOIN db.two")'
    text = '{"reads": [{"table": "db.one"}, {"table": "db.two"}], "writes": []}'
    out = postprocess(text, content, thr=0.99)  # 仅 sql_qual 进 auto，agree 进复核
    confs = [i["confidence"] for i in out["review_reads"]]
    assert confs == sorted(confs, reverse=True)


def test_tiering_off_equals_old_flat_output():
    # LINEAGE_TIERING=0 回滚：review 空、tiered False、reads/writes=grounded+dir_fix flat
    content = 'spark.sql("INSERT OVERWRITE TABLE dwd.clean SELECT * FROM ods.orders")'
    text = '{"reads": [{"table": "dwd.clean"}, {"table": "ods.orders"}], "writes": []}'
    off = postprocess(text, content, tiering=False)
    assert off["review_reads"] == [] and off["review_writes"] == []
    assert off["tiered"] is False
    assert "dwd.clean" in {i["table"] for i in off["writes"]}  # 旧 dir_fix 语义不变


# ---- 063 US2：自动入库只收高置信（治理安全） ----

def test_auto_tier_only_governance_accepted_tiers():
    # 自动层的每项 tier 必在治理阈采纳集内（thr=0.95 → 仅 sql_qual）
    from realeval.tier_classify_constants import autoaccept_tiers
    content = 'spark.sql("INSERT OVERWRITE TABLE db.dwd SELECT * FROM db.ods JOIN raw_bare")'
    text = '{"reads": [{"table": "db.ods"}], "writes": []}'
    out = postprocess(text, content, thr=0.95)
    accept = set(autoaccept_tiers(0.95))
    for i in out["reads"] + out["writes"]:
        assert i["tier"] in accept  # 低置信级不进自动层


# ---- 063 US3：治理阈可调 + 回滚 ----

def test_thr_relaxation_expands_auto_tier():
    # 阈放宽 0.95→0.85：自动采纳集扩大（sql_qual → +model_bare+agree+model_qual）
    content = 'spark.sql("INSERT OVERWRITE TABLE db.dwd SELECT * FROM db.ods")'
    text = '{"reads": [{"table": "db.ods"}], "writes": []}'
    strict = postprocess(text, content, thr=0.95)
    relaxed = postprocess(text, content, thr=0.85)
    strict_auto = {i["table"] for i in strict["reads"] + strict["writes"]}
    relaxed_auto = {i["table"] for i in relaxed["reads"] + relaxed["writes"]}
    assert strict_auto <= relaxed_auto            # 放宽只会扩大自动层
    assert "db.ods" in relaxed_auto and "db.ods" not in strict_auto  # agree 在 0.85 进 auto


def test_union_recall_invariant_across_thr():
    # 复核层召回天花板不随阈变化：auto∪review 恒等（US3-AS3）
    content = 'spark.sql("INSERT OVERWRITE TABLE db.dwd SELECT * FROM db.ods")'
    text = '{"reads": [{"table": "db.ods"}], "writes": []}'
    def union(thr):
        o = postprocess(text, content, thr=thr)
        return {i["table"] for i in o["reads"] + o["writes"] + o["review_reads"] + o["review_writes"]}
    assert union(0.95) == union(0.85) == union(0.0)
