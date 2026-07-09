"""054 conf_calibration_apply.apply_frozen 单测：把 A 冻结前沿套到 B（离线纯函数）。"""
from realeval.conf_calibration_apply import apply_frozen

# A 冻结校准（精简版）：级序把 model_qual 提前，治理前沿 = {agree, model_qual}。
FROZEN = {
    "calibrated_order": ["agree", "model_qual", "model_bare", "sql_bare"],
    # 真实 conf-calibration-A.json 恒含先验序 cumulative（best_frontier 的默认回退键）。
    "cumulative": [{"upto": "agree", "accept_tiers": ["agree"], "n_accept": 10,
                    "precision": 1.0, "recall": 0.17, "review_per_script": 2.2}],
    "cumulative_calibrated": [
        {"upto": "agree", "accept_tiers": ["agree"], "n_accept": 10,
         "precision": 1.0, "recall": 0.17, "review_per_script": 2.2},
        {"upto": "model_qual", "accept_tiers": ["agree", "model_qual"], "n_accept": 20,
         "precision": 0.95, "recall": 0.41, "review_per_script": 1.6},
    ],
}


def _row(reads=(), writes=(), content="# pure python, no sql"):
    return {"task_type": "PYTHON", "content": content,
            "labels": {"reads": [{"table": t, "columns": None} for t in reads],
                       "writes": [{"table": t, "columns": None} for t in writes]}}


def test_frozen_frontier_applied_on_b():
    # 无可解析 SQL → SQL 通道空，全为 model-only 边；限定名 → model_qual 级。
    rows = [_row(reads=["db.users"]), _row(writes=["warehouse.sales"])]
    model = {0: {"reads": [{"table": "db.users"}], "writes": []},
             1: {"reads": [], "writes": [{"table": "warehouse.sales"}]}}
    res = apply_frozen(rows, model, FROZEN, thr=0.95)

    assert res["n_nonempty"] == 2
    assert res["gold_total"] == 2
    # 采纳集从 A 冻结 = {agree, model_qual}。
    assert set(res["frozen_accept_set"]) == {"agree", "model_qual"}
    bf = res["b_frontier"]
    assert bf is not None
    assert set(bf["accept_tiers"]) == {"agree", "model_qual"}
    # 两条 model_qual 边都命中金标 → B 前沿 precision 1.0 / recall 1.0。
    assert bf["precision"] == 1.0
    assert bf["recall"] == 1.0


def test_frozen_order_not_refit_on_b():
    # 即便 B 上 model_qual 全错，级序仍用 A 冻结序（不因 B 重排）。
    rows = [_row(reads=["db.users"])]
    model = {0: {"reads": [{"table": "wrong.table"}], "writes": []}}
    res = apply_frozen(rows, model, FROZEN, thr=0.95)
    assert res["frozen_order"] == FROZEN["calibrated_order"]   # 冻结序原样
    bf = res["b_frontier"]
    # 前沿采纳 model_qual（wrong.table 限定名），但金标不含 → precision 掉。
    assert bf is not None and bf["precision"] < 0.95


def test_agree_only_baseline_present():
    rows = [_row(reads=["db.users"])]
    model = {0: {"reads": [{"table": "db.users"}], "writes": []}}
    res = apply_frozen(rows, model, FROZEN, thr=0.95)
    bb = res["b_baseline_agree_only"]
    assert bb is not None and bb["accept_tiers"] == ["agree"]
