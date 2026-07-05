"""047 T004：leak_analysis.py --train-pool 泛化 + 向后兼容单测（无 GPU）。"""
from __future__ import annotations

import json

from realeval.leak_analysis import analyze, load_own_pool


def _row(tables_out):
    """构造一条：pred 吐 tables_out（全不在金标、不在 content）→ 全算幻觉。"""
    return {
        "labels": {"reads": [], "writes": []},
        "content": "df = compute()\nprint('done')",
        "meta": {"template_id": "fake/t1"},
    }


def _predict_factory(pred_tables):
    def predict_fn(_row):
        return {"reads": [{"table": t} for t in pred_tables], "writes": []}
    return predict_fn


# 名字刻意不含仓库 base 词，避免 _synthetic_shaped 干扰计数
OWN = "own_tbl_x"
SYNTH = "syn_tbl_y"
DYN = "dyn_tbl_z"


def test_backward_compat_no_train_pool():
    """不传 own_pool：结果结构与基线一致，无 verbatim_own 字段。"""
    rows = [_row(None)]
    res = analyze("m", _predict_factory([OWN, SYNTH, DYN]), rows,
                  train_pool={SYNTH}, own_pool=None)
    assert res["hallucinations"] == 3
    assert res["verbatim_train_names"] == 1          # 仅 SYNTH 命中合成池
    assert res["verbatim_rate"] == round(1 / 3, 3)
    assert "verbatim_own_names" not in res           # 向后兼容：无新字段
    assert "verbatim_own_rate" not in res
    for ex in res["examples"]:
        assert "verbatim_own" not in ex


def test_own_pool_counted():
    """传 own_pool：自有池逐字命中被单独统计，防换名字假性归零。"""
    rows = [_row(None)]
    res = analyze("m", _predict_factory([OWN, SYNTH, DYN]), rows,
                  train_pool={SYNTH}, own_pool={OWN})
    assert res["hallucinations"] == 3
    assert res["verbatim_train_names"] == 1          # 合成池对照列仍在
    assert res["verbatim_own_names"] == 1            # 自有池命中 OWN
    assert res["verbatim_own_rate"] == round(1 / 3, 3)
    assert any(ex.get("verbatim_own") for ex in res["examples"])


def test_b1_scenario_no_false_zero():
    """B1 情形：模型只吐自有真实训练名、不吐合成名 → 合成池列=0 但自有池列>0（不假性归零）。"""
    rows = [_row(None)]
    res = analyze("b1", _predict_factory([OWN]), rows,
                  train_pool={SYNTH}, own_pool={OWN})
    assert res["verbatim_train_names"] == 0          # 只查合成池会误判"无泄漏"
    assert res["verbatim_own_names"] == 1            # 自有池揭示：仍在背训练分布


def test_load_own_pool(tmp_path):
    p = tmp_path / "pool.json"
    p.write_text(json.dumps({"variant": "b1", "seed": 1,
                             "train_table_names": [OWN, "a.b"],
                             "synth_generated_subset": []}), encoding="utf-8")
    pool = load_own_pool(str(p))
    assert pool == {OWN, "a.b"}
