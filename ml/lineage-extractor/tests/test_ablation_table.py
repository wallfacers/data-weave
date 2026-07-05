"""047 T018：消融表汇编单测（无 GPU）——诚实性硬约束 + 自动告警。"""
from __future__ import annotations

import json

import pytest

from realeval.ablation_table import FROZEN, build_report, load_variant


def _variant(label, **over):
    base = dict(label=label, real_prec=0.30, real_hall=0.15, real_recall=0.60,
                real_dir=0.50, leak=0.20, shaped=0.20, synth_prec=0.99)
    base.update(over)
    return base


def _baseline():
    return dict(label="baseline-1.5b", **FROZEN["baseline-1.5b"])


def _3b():
    return dict(label="3b", **FROZEN["3b"])


def test_frozen_constants_match_paper():
    """基线/3B 冻结值与论文 §5 一致（防漂移）。"""
    assert FROZEN["baseline-1.5b"]["leak"] == 0.224
    assert FROZEN["baseline-1.5b"]["real_dir"] == 0.496
    assert FROZEN["3b"]["leak"] == 0.109
    assert FROZEN["3b"]["synth_prec"] is None   # 论文未单列 → 渲染 "—"


def test_missing_synth_col_errors(tmp_path):
    """给了 eval/leak 但缺 synth → 报错（禁单边汇报）。"""
    p = tmp_path / "e.json"
    p.write_text(json.dumps([{"name": "sft-1.5b", "full": {"precision": 0.2, "hallucination": 0.1},
                              "nonempty": {"recall": 0.5, "direction_acc": 0.4}}]), encoding="utf-8")
    with pytest.raises(SystemExit):
        load_variant("b1", str(p), str(p), None)


def test_leak_needs_train_pool(tmp_path):
    """leak json 缺 verbatim_own_rate（未用 --train-pool）→ 报错。"""
    ev = tmp_path / "ev.json"
    ev.write_text(json.dumps([{"name": "sft-1.5b", "full": {"precision": 0.2, "hallucination": 0.1},
                               "nonempty": {"recall": 0.5, "direction_acc": 0.4}}]), encoding="utf-8")
    lk = tmp_path / "lk.json"
    lk.write_text(json.dumps([{"name": "sft-1.5b", "shaped_rate": 0.1}]), encoding="utf-8")  # 无 verbatim_own_rate
    sy = tmp_path / "sy.json"
    sy.write_text(json.dumps({"overall": {"precision": 0.99}}), encoding="utf-8")
    with pytest.raises(SystemExit):
        load_variant("b1", str(ev), str(lk), str(sy))


def test_load_variant_ok(tmp_path):
    ev = tmp_path / "ev.json"
    ev.write_text(json.dumps([{"name": "regex"}, {"name": "sft-1.5b",
                  "full": {"precision": 0.28, "hallucination": 0.12},
                  "nonempty": {"recall": 0.55, "direction_acc": 0.47}}]), encoding="utf-8")
    lk = tmp_path / "lk.json"
    lk.write_text(json.dumps([{"name": "sft-1.5b", "verbatim_own_rate": 0.18, "shaped_rate": 0.2}]),
                  encoding="utf-8")
    sy = tmp_path / "sy.json"
    sy.write_text(json.dumps({"overall": {"precision": 0.985}}), encoding="utf-8")
    v = load_variant("b1", str(ev), str(lk), str(sy))
    assert v["real_prec"] == 0.28 and v["leak"] == 0.18 and v["synth_prec"] == 0.985


def test_warn_on_recall_collapse():
    """B2 恒弃权：recall 相对基线跌 >20% → 自动告警。"""
    b2 = _variant("b2", real_recall=0.10)   # 基线 0.618 → 跌 >20%
    md, data = build_report([_baseline(), b2, _3b()])
    assert any("recall" in w for w in data["warnings"])
    assert "自动告警" in md


def test_warn_on_synth_regression():
    b1 = _variant("b1", synth_prec=0.90)    # 基线 0.9954 → 跌 >5pt
    md, data = build_report([_baseline(), b1, _3b()])
    assert any("合成 held-out" in w for w in data["warnings"])


def test_no_warn_when_healthy():
    v = _variant("b1", real_recall=0.62, synth_prec=0.99)
    md, data = build_report([_baseline(), v, _3b()])
    assert data["warnings"] == []
    assert "无自动告警触发" in md
