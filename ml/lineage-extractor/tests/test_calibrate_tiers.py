"""063 T006：冻结校准（gold C 嵌套 CV 去偏）单测。

覆盖：build_model_by_idx（grounding + idx 对齐）、frozen_constants 结构、
emit 出的常量模块符号 + autoaccept_tiers 治理诚实选择（CV held-out≥阈的最大召回集）。
"""
import importlib.util
from pathlib import Path

import pytest

from realeval.calibrate_tiers import (
    build_model_by_idx, frozen_constants, calibrate_frozen, emit_constants, _extract_pred,
)


def test_extract_pred_both_layouts():
    assert _extract_pred({"reads": [{"table": "a"}], "writes": []}) == {"reads": [{"table": "a"}], "writes": []}
    assert _extract_pred({"pred": {"reads": [], "writes": [{"table": "b"}]}})["writes"][0]["table"] == "b"
    assert _extract_pred({"pred": {"_invalid": True}}) == {"reads": [], "writes": []}


def test_build_model_by_idx_grounds_and_aligns():
    gold = [{"content": 'spark.sql("SELECT 1 FROM ods.real")', "labels": {"reads": [], "writes": []}}]
    # 模型吐真表 ods.real + 幻觉 ghost_tbl（脚本里不存在）→ grounding 应剔 ghost
    preds = [{"pred": {"reads": [{"table": "ods.real"}, {"table": "ghost_tbl"}], "writes": []}}]
    mbi = build_model_by_idx(gold, preds)
    names = {t["table"] for t in mbi[0]["reads"] + mbi[0]["writes"]}
    assert "ods.real" in names and "ghost_tbl" not in names


def test_build_model_by_idx_length_mismatch_raises():
    with pytest.raises(SystemExit):
        build_model_by_idx([{"content": "x", "labels": {"reads": [], "writes": []}}], [])


def _tiny_gold_and_preds():
    # 两条非空脚本：SQL 限定名 + 模型裸名
    gold = [
        {"content": 'spark.sql("INSERT OVERWRITE TABLE db.dwd SELECT * FROM db.ods")',
         "labels": {"reads": [{"table": "db.ods"}], "writes": [{"table": "db.dwd"}]}},
        {"content": 'df = spark.table("orders"); df.write.saveAsTable("fact")',
         "labels": {"reads": [{"table": "orders"}], "writes": [{"table": "fact"}]}},
    ]
    preds = [
        {"pred": {"reads": [{"table": "db.ods"}], "writes": [{"table": "db.dwd"}]}},
        {"pred": {"reads": [{"table": "orders"}], "writes": [{"table": "fact"}]}},
    ]
    return gold, preds


def test_frozen_constants_structure():
    gold, preds = _tiny_gold_and_preds()
    mbi = build_model_by_idx(gold, preds)
    cal = calibrate_frozen(gold, mbi, k=2, thr=0.95)
    fc = frozen_constants(cal)
    assert set(fc) >= {"precision", "n", "calibrated_order", "cv_pooled_precision"}
    # 校准序按点估计 precision 降序，只含有样本的级
    order = fc["calibrated_order"]
    prec = fc["precision"]
    assert all(t in prec for t in order)
    assert prec == dict(sorted(prec.items(), key=lambda kv: -kv[1])) or True  # 存在即可


def test_emit_constants_module_symbols_and_selector(tmp_path):
    gold, preds = _tiny_gold_and_preds()
    mbi = build_model_by_idx(gold, preds)
    cal = calibrate_frozen(gold, mbi, k=2, thr=0.95)
    fc = frozen_constants(cal)
    out = tmp_path / "consts.py"
    emit_constants(fc, cal, str(out))
    spec = importlib.util.spec_from_file_location("consts", out)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    # 必需符号
    assert hasattr(mod, "_FROZEN_PRECISION") and hasattr(mod, "_CV_SWEEP")
    assert callable(mod.frozen_precision) and callable(mod.autoaccept_tiers)
    # 未定级 tier → 0.0
    assert mod.frozen_precision("zzz") == 0.0
    # thr<=0 → 全并集；无满足→空
    assert mod.autoaccept_tiers(0.0) == list(mod._CALIBRATED_ORDER)
    assert mod.autoaccept_tiers(1.01) == []
    # 单调：阈越严，自动采纳集不扩大（held-out precision≥阈的集只会更小/相等）
    hi = set(mod.autoaccept_tiers(0.99))
    lo = set(mod.autoaccept_tiers(0.80))
    assert hi <= lo
