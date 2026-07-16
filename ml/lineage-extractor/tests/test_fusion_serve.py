"""068 US6 serving 融合接线单测（monkeypatch，无 GPU）。

只验接线：融合开→extract 用两专家预测喂 fuse(表专家第一参/列专家第二参/策略透传)；
融合关→单模型路径与旧 postprocess(text) 逐字节等价。fuse/postprocess 各自已另测。
"""
import json

import serve.app as app
from serve.app import ExtractRequest, postprocess, _parse_model_json


def test_fusion_wiring_passes_both_specialist_preds(monkeypatch):
    captured = {}

    def spy_fuse(tp, cp, strategy):
        captured.update(tp=tp, cp=cp, strategy=strategy)
        return {"reads": [], "writes": []}

    def fake_gen(model, req):
        return {"reads": [{"table": model, "columns": None}], "writes": []}

    monkeypatch.setattr(app, "FUSION_ENABLED", True)
    monkeypatch.setattr(app, "FUSION_STRATEGY", "union")
    monkeypatch.setattr(app, "_generate_pred", fake_gen)
    monkeypatch.setattr(app, "fuse", spy_fuse)
    app.state["model_table"], app.state["model_col"] = "TBL", "COL"

    app.extract(ExtractRequest(taskType="python", content="x"))
    assert captured["tp"]["reads"][0]["table"] == "TBL"   # 表专家 → fuse 第一参
    assert captured["cp"]["reads"][0]["table"] == "COL"   # 列专家 → fuse 第二参
    assert captured["strategy"] == "union"                 # 策略透传


def test_nonfusion_path_byte_equivalent_to_postprocess(monkeypatch):
    pred = {"reads": [{"table": "orders", "columns": ["id"]}], "writes": []}
    content = "select id from orders"
    monkeypatch.setattr(app, "FUSION_ENABLED", False)
    monkeypatch.setattr(app, "_generate_pred", lambda m, r: pred)
    app.state["model"] = "M"

    resp = app.extract(ExtractRequest(taskType="sql", content=content))
    exp = postprocess(json.dumps(pred), content)
    assert [r.table for r in resp.reads] == [t["table"] for t in exp["reads"]]
    assert [r.table for r in resp.writes] == [t["table"] for t in exp["writes"]]


def test_postprocess_idempotent_over_reparse():
    # 非融合改用 postprocess(json.dumps(parsed)) 的等价性根据：_parse_model_json 幂等。
    text = 'noise {"reads": [{"table": "orders", "columns": ["id"]}], "writes": []} tail'
    content = "select id from orders"
    a = postprocess(text, content)
    b = postprocess(json.dumps(_parse_model_json(text)), content)
    assert a == b
