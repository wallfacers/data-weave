"""050 Tier 2.1：B3 硬负例变体单测（无 GPU、无网络，注入合成池）。"""
from __future__ import annotations

import json
import random

from data.b3_hardneg import (B3_NEGATIVE_FRAC, _B3_NEG_KINDS, build_b3,
                             build_negative_row_b3)
from data.synth_pipeline import SEED, synth_table_names

COLUMNS = ["id", "amount", "status", "biz_date", "channel", "price"]


def _pools():
    train_t = synth_table_names(random.Random(SEED), 200)
    heldout_t = synth_table_names(random.Random(SEED + 1), 80)
    return train_t, heldout_t


def _rows_bytes(rows):
    return "\n".join(json.dumps(r, ensure_ascii=False) for r in rows)


def test_b3_negative_frac_and_empty_labels():
    train_t, heldout_t = _pools()
    train_rows, _, _ = build_b3(random.Random(SEED), train_t, heldout_t, COLUMNS,
                                train_size=200, heldout_size=30, neg_frac=0.45)
    negs = [r for r in train_rows if r["meta"]["form_family"] == "b3-abstain"]
    assert abs(len(negs) / len(train_rows) - 0.45) <= 0.01      # 45%±1%
    for r in negs:
        assert r["labels"] == {"reads": [], "writes": []}       # 全空标签
        assert r["content"]                                     # 有内容


def test_b3_covers_six_kinds_incl_three_hard():
    """轮转覆盖全 6 型；3 个硬型（照真实失败分布）确在其中。"""
    assert len(_B3_NEG_KINDS) == 6
    rng = random.Random(SEED)
    seen = set()
    for i in range(24):   # 6 型 × 4 task_type
        r = build_negative_row_b3(rng, ["ods.ods_orders_di"], COLUMNS, i)
        seen.add(r["meta"]["template_id"].split("/")[-1])
    for hard in ("neg_license_boilerplate", "neg_shell_config", "neg_metadata_introspection"):
        assert hard in seen


def test_b3_hard_negatives_have_no_literal_table_leak():
    """3 个新硬型（license/config/metadata）正文不得字面含合成训练表名——它们靠"无表名"
    直击"陌生样板→回退吐训练名"的假阳；B2 复用型（注释SQL/动态名）故意含名字于注释/拼接
    上下文（考模型忽略），不在此约束内。"""
    hard = {"neg_license_boilerplate", "neg_shell_config", "neg_metadata_introspection"}
    rng = random.Random(SEED)
    synth = set(synth_table_names(random.Random(SEED), 400))
    checked = 0
    for i in range(24):
        r = build_negative_row_b3(rng, list(synth), COLUMNS, i)
        if r["meta"]["template_id"].split("/")[-1] not in hard:
            continue
        checked += 1
        for name in synth:
            assert name not in r["content"], f"硬负例 {r['meta']['template_id']} 泄漏了训练名 {name}"
    assert checked >= 3   # 三个硬型都被覆盖到


def test_b3_determinism():
    train_t, heldout_t = _pools()
    a, _, pa = build_b3(random.Random(SEED), train_t, heldout_t, COLUMNS, 200, 30, 0.45)
    b, _, pb = build_b3(random.Random(SEED), train_t, heldout_t, COLUMNS, 200, 30, 0.45)
    assert _rows_bytes(a) == _rows_bytes(b)                     # 同 SEED byte-identical
    assert pa == pb


def test_b3_pool_same_convention_as_b2():
    """B3 pool 口径同 B2：train_table_names 由正样本行导出，供 --train-pool 同源可比。"""
    train_t, heldout_t = _pools()
    _, _, pool = build_b3(random.Random(SEED), train_t, heldout_t, COLUMNS, 200, 30, 0.45)
    assert pool["variant"] == "b3" and pool["seed"] == SEED
    assert pool["train_table_names"]                            # 非空（正样本贡献）


def test_b3_default_frac_is_heavier_than_b2():
    assert B3_NEGATIVE_FRAC == 0.45                             # 明显重于 B2 的 0.20
