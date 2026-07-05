from eval.metrics import score_row, aggregate, GATE


def test_direction_confusion_counted():
    # gold: 读 a 写 b；pred 把 a 也塞进 writes（方向混淆）
    gold = {"reads": [{"table": "a"}], "writes": [{"table": "b"}]}
    pred = {"reads": [], "writes": [{"table": "a"}, {"table": "b"}]}
    c = score_row(gold, pred, "a b")
    # 表 a 出现在错误方向 → 计入方向错误
    assert c["dir_total"] >= 1
    assert c["dir_correct"] < c["dir_total"]


def test_perfect_row_full_direction():
    gold = {"reads": [{"table": "a"}], "writes": [{"table": "b"}]}
    pred = {"reads": [{"table": "a"}], "writes": [{"table": "b"}]}
    c = score_row(gold, pred, "a b")
    assert c["dir_correct"] == c["dir_total"]
    agg = aggregate([c])
    assert agg["direction_acc"] == 1.0


def test_gate_has_direction_threshold():
    assert GATE["direction_acc"] == 0.95


def test_direction_read_write_same_table():
    gold = {"reads": [{"table": "t"}], "writes": [{"table": "t"}]}
    # pred 双向正确 → 记对
    both = score_row(gold, {"reads": [{"table": "t"}], "writes": [{"table": "t"}]}, "t")
    assert both["dir_correct"] == both["dir_total"] == 1
    # pred 只给一个方向 → 记错（不完整）
    partial = score_row(gold, {"reads": [{"table": "t"}], "writes": []}, "t")
    assert partial["dir_correct"] == 0 and partial["dir_total"] == 1
