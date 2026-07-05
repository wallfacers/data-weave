from data.review_data import self_consistency_flags, review_sample


def test_flags_table_not_in_content():
    row = {"content": "read ods.a", "labels": {"reads": [{"table": "ghost.tbl"}], "writes": []}}
    assert "read_table_absent:ghost.tbl" in self_consistency_flags(row)


def test_clean_sample_no_flags():
    row = {
        "content": "INSERT INTO dws.b SELECT * FROM ods.a",
        "labels": {"reads": [{"table": "ods.a"}], "writes": [{"table": "dws.b"}]},
    }
    assert self_consistency_flags(row) == []


def test_leaf_substring_not_false_negative():
    # ghost.tbl 真缺失，但 leaf 'tbl' 是 'subtblx' 子串——必须仍报缺失
    row = {"content": "select * from subtblx where x=1",
           "labels": {"reads": [{"table": "ghost.tbl"}], "writes": []}}
    assert "read_table_absent:ghost.tbl" in self_consistency_flags(row)


def test_read_write_overlap_flagged():
    row = {"content": "INSERT INTO ods.a SELECT * FROM ods.a",
           "labels": {"reads": [{"table": "ods.a"}], "writes": [{"table": "ods.a"}]}}
    assert any(f.startswith("read_write_overlap:") for f in self_consistency_flags(row))


def test_review_sample_without_client_no_network():
    row = {
        "task_type": "sql",
        "content": "read ods.a",
        "labels": {"reads": [{"table": "ghost.tbl"}], "writes": []},
        "meta": {"template_id": "t1"},
    }
    result = review_sample(None, row)
    assert result["template_id"] == "t1"
    assert result["issues"] == ["read_table_absent:ghost.tbl"]
    assert result["llm_verdict"] is None
