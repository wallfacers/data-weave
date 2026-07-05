"""047 T006/T012：B1/B2 训练集变体构造器单测（无 GPU、无网络，注入真实名）。"""
from __future__ import annotations

import json
import random

from data.antileak import (SEED, b1_pools, build_b1, build_b2, build_negative_row,
                            variant_pool_json)
from data.synth_pipeline import synth_table_names
from eval.metrics import tables

# 注入的"真实名"池（无网络）：形态不同于合成流形
REAL = [f"prod_db.customer_fact_{i:04d}" for i in range(3000)]
COLUMNS = ["id", "amount", "status", "biz_date", "channel", "price"]


def _rows_bytes(rows):
    return "\n".join(json.dumps(r, ensure_ascii=False) for r in rows)


# ── B1 ──────────────────────────────────────────────────────────────────

def test_b1_split_isolation_and_ratio():
    train_t, heldout_t, synth_sub = b1_pools(random.Random(SEED), REAL, synth_keep=40)
    assert set(train_t) & set(heldout_t) == set()          # 名池不相交
    synth_all = set(synth_table_names(random.Random(SEED), 40))
    synth_in_train = set(train_t) & synth_all
    assert len(synth_in_train) / len(train_t) <= 0.15      # 合成名占比 ≤15%
    assert set(synth_sub) == synth_in_train


def test_b1_determinism_and_schema():
    a = build_b1(random.Random(SEED), REAL, COLUMNS, train_size=60, heldout_size=30,
                 synth_keep=40, hf_degraded=False)
    b = build_b1(random.Random(SEED), REAL, COLUMNS, train_size=60, heldout_size=30,
                 synth_keep=40, hf_degraded=False)
    assert _rows_bytes(a[0]) == _rows_bytes(b[0])          # 同 SEED byte-identical
    assert a[2] == b[2]                                     # pool.json 一致
    # 行 schema 与基线同构
    r = a[0][0]
    assert set(r) == {"task_type", "content", "labels", "meta"}
    assert set(r["labels"]) == {"reads", "writes"}
    assert r["meta"]["source_dataset"].startswith("synth-b1-realname")


def test_b1_pool_json_structure():
    _, _, pool = build_b1(random.Random(SEED), REAL, COLUMNS, 60, 30, 40, False)
    assert pool["variant"] == "b1" and pool["seed"] == SEED
    # synth 子集 ⊆ 合成生成名
    assert set(pool["synth_generated_subset"]) <= set(synth_table_names(random.Random(SEED), 400))
    # train_table_names ⊇ 训练行所有 labels 表名（pool 是采样来源的超集）
    train_rows, _, _ = build_b1(random.Random(SEED), REAL, COLUMNS, 60, 30, 40, False)
    seen = set()
    for row in train_rows:
        seen |= set(tables(row["labels"]["reads"])) | set(tables(row["labels"]["writes"]))
    assert seen <= set(pool["train_table_names"])


# ── B2 ──────────────────────────────────────────────────────────────────

def _b2(train_size=100, neg_frac=0.20):
    # hermetic：直接注入固定合成池（不走 build_pools 的 HF 网络收割）
    rng = random.Random(SEED)
    train_t = synth_table_names(random.Random(SEED), 200)
    heldout_t = synth_table_names(random.Random(SEED + 1), 80)
    return build_b2(rng, train_t, heldout_t, COLUMNS, train_size, 30, neg_frac), train_t


def test_b2_negative_frac_and_empty_labels():
    (train_rows, _, _), _ = _b2(train_size=100, neg_frac=0.20)
    negs = [r for r in train_rows if r["meta"]["form_family"] == "b2-abstain"]
    assert abs(len(negs) / len(train_rows) - 0.20) <= 0.01      # 20%±1%
    for r in negs:
        assert r["labels"] == {"reads": [], "writes": []}       # 负样本全空
        assert r["content"]                                      # 有内容


def test_b2_only_delta_is_negatives():
    """消融纯净：neg_frac=0 无负样本；正样本来源仍是基线（唯一增量 = 弃权负样本）。"""
    (rows0, _, _), _ = _b2(train_size=100, neg_frac=0.0)
    assert all(r["meta"]["form_family"] != "b2-abstain" for r in rows0)
    (rows2, _, _), _ = _b2(train_size=100, neg_frac=0.20)
    pos = [r for r in rows2 if r["meta"]["form_family"] != "b2-abstain"]
    assert pos and all(r["meta"]["source_dataset"] != "synth-b2-abstain" for r in pos)


def test_b2_determinism():
    (a, _, _), _ = _b2()
    (b, _, _), _ = _b2()
    assert _rows_bytes(a) == _rows_bytes(b)


def test_b2_negative_row_kinds_all_empty():
    rng = random.Random(SEED)
    for i in range(3):   # 覆盖三型
        row = build_negative_row(rng, ["ods.ods_orders_di"], COLUMNS, i)
        assert row["labels"] == {"reads": [], "writes": []}
        assert row["meta"]["form_family"] == "b2-abstain"
