import json

from eval.evaluate import run_eval, write_report


def _rows():
    return [
      {"task_type":"PYTHON","content":"read a write b",
       "labels":{"reads":[{"table":"a"}],"writes":[{"table":"b"}]},
       "meta":{"template_id":"t1","form_family":"wrapper","rule_covered":False,"split_group":"heldout"}},
    ]


def test_run_eval_layers():
    perfect = lambda row: {"reads":[{"table":"a"}],"writes":[{"table":"b"}]}
    res = run_eval(perfect, _rows())
    assert res["overall"]["precision"] == 1.0
    assert res["overall"]["direction_acc"] == 1.0
    assert "wrapper" in res["by_family"]


def test_write_report_emits_md_and_json(tmp_path):
    rows = _rows()
    res = run_eval(lambda r: {"reads":[{"table":"a"}],"writes":[{"table":"b"}]}, rows)
    md = tmp_path / "eval-report.md"
    write_report(res, str(md))
    js = md.with_suffix(".json")
    assert md.exists() and js.exists()            # 双文件生成

    loaded = json.loads(js.read_text())            # json round-trip
    assert loaded["overall"]["precision"] == 1.0
    assert "wrapper" in loaded["by_family"]

    text = md.read_text()
    assert "方向准确率" in text or "direction" in text.lower()  # 分层内容渲染
