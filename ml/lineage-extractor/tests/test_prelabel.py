import json

from realeval.prelabel import cross_prelabel, prelabel_pool


def test_agreement_and_review_flag():
    preds = {
      "m1": lambda r: {"reads":[{"table":"a"}],"writes":[{"table":"b"}]},
      "m2": lambda r: {"reads":[{"table":"a"}],"writes":[{"table":"b"}]},
      "rule": lambda r: {"reads":[{"table":"a"}],"writes":[{"table":"b"}]},
    }
    out = cross_prelabel(preds, {"task_type":"PYTHON","content":"a b"})
    assert out["agreement"]["reads"] and out["agreement"]["writes"]
    assert out["needs_review"] is False


def test_disagreement_flags_review():
    preds = {
      "m1": lambda r: {"reads":[{"table":"a"}],"writes":[]},
      "m2": lambda r: {"reads":[],"writes":[{"table":"a"}]},
    }
    out = cross_prelabel(preds, {"task_type":"PYTHON","content":"a"})
    assert out["needs_review"] is True


def test_llm_consensus_overrides_rule_noise():
    # M1==M2 一致时，规则基线的噪声（把注释英文词当表名）不应把该条误标为 needs_review。
    preds = {
      "m1": lambda r: {"reads": [], "writes": []},
      "m2": lambda r: {"reads": [], "writes": []},
      "rule": lambda r: {"reads": [{"table": "the"}, {"table": "standard"}], "writes": []},
    }
    out = cross_prelabel(preds, {"task_type": "SHELL", "content": "# FROM the standard SELECT"})
    assert out["llm_consensus"] is True
    assert out["needs_review"] is False          # 复审队列不被规则噪声灌水
    assert out["agreement"]["reads"] is False     # 但全体一致仍如实标注（规则确实不同）
    assert out["predictions"]["rule"]["reads"]    # 规则预标仍保留展示


def test_single_llm_falls_back_to_all_voters():
    # 只有 1 个 LLM（另一个 client 缺失）时，退回全体投票者判分歧，避免漏标真歧义。
    preds = {
      "m1": lambda r: {"reads": [{"table": "a"}], "writes": []},
      "rule": lambda r: {"reads": [], "writes": [{"table": "a"}]},
    }
    out = cross_prelabel(preds, {"task_type": "PYTHON", "content": "a"})
    assert out["needs_review"] is True


def test_job_only_skips_library_source(tmp_path):
    # meta.looks_like_job==False 的候选被跳过、不入终审队列；无 meta 的旧记录保留
    pool = tmp_path / "pool"
    pool.mkdir()
    (pool / "lib.json").write_text(json.dumps(
        {"content": "x", "meta": {"looks_like_job": False}}), encoding="utf-8")
    (pool / "job.json").write_text(json.dumps(
        {"content": "y", "meta": {"looks_like_job": True}}), encoding="utf-8")
    (pool / "legacy.json").write_text(json.dumps(
        {"content": "z"}), encoding="utf-8")  # 试水旧记录无 meta
    preds = {"p1": lambda r: {"reads": [], "writes": []},
             "p2": lambda r: {"reads": [], "writes": []}}
    written = prelabel_pool(preds, pool, tmp_path / "out")
    assert written == 2  # job + legacy，lib 被跳过
    stems = {json.loads((tmp_path / "out" / f.name).read_text())["content"]
             for f in (tmp_path / "out").glob("*.json") if not f.name.startswith("_")}
    assert stems == {"y", "z"}


def test_tolabel_disagreements_ranked_first(tmp_path):
    pool = tmp_path / "pool"
    pool.mkdir()
    # 一条一致、一条分歧；文件名故意让 agree 在字母序上排前面，
    # 以验证磁盘落盘顺序按 needs_review 重排而非沿用输入文件名顺序。
    (pool / "a_agree.json").write_text(
        json.dumps({"content": "read a write b", "task_type": "PYTHON"}), encoding="utf-8")
    (pool / "z_disagree.json").write_text(
        json.dumps({"content": "x", "task_type": "PYTHON"}), encoding="utf-8")
    out = tmp_path / "tolabel"

    preds = {
        "p1": lambda r: {"reads": [{"table": "a"}], "writes": [{"table": "b"}]}
        if "read a" in r["content"] else {"reads": [{"table": "a"}], "writes": []},
        "p2": lambda r: {"reads": [{"table": "a"}], "writes": [{"table": "b"}]}
        if "read a" in r["content"] else {"reads": [], "writes": [{"table": "a"}]},
    }

    written = prelabel_pool(preds, pool, out)
    assert written == 2

    files = sorted(p.name for p in out.glob("*.json") if not p.name.startswith("_"))
    # 落盘文件名带 rank 前缀，使字母序 = triage 顺序（分歧在前）
    assert files[0].startswith("0000_")
    assert files[1].startswith("0001_")

    first = json.loads((out / files[0]).read_text(encoding="utf-8"))
    assert first["prelabel"]["needs_review"] is True

    second = json.loads((out / files[1]).read_text(encoding="utf-8"))
    assert second["prelabel"]["needs_review"] is False

    # 结构化清单同步写出，供工具消费
    index_path = out / "_index.json"
    assert index_path.exists()
    index = json.loads(index_path.read_text(encoding="utf-8"))
    assert index[0]["needs_review"] is True
    assert index[0]["rank"] == 0
    assert index[0]["filename"] == files[0]
