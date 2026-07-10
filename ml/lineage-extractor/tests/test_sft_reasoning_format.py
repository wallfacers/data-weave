"""059 sft_qlora 想后答格式单测：锁定 </think> 契约（训练格式 ↔ eval 解析不漂移）。"""
import pytest

pytest.importorskip("trl")  # 训练重依赖缺失则跳过

from train.sft_qlora import to_messages, to_messages_reasoning, THINK_CLOSE, _answer_json


def _row(reads=(), writes=(), reasoning="step1 step2"):
    return {"task_type": "PYTHON", "content": "print(1)", "reasoning": reasoning,
            "labels": {"reads": [{"table": t, "columns": None} for t in reads],
                       "writes": [{"table": t, "columns": None} for t in writes]}}


def test_reasoning_target_think_then_answer():
    m = to_messages_reasoning(_row(reads=["a"]))["messages"][-1]["content"]
    assert m.startswith("<think>")
    assert THINK_CLOSE in m
    head, tail = m.split(THINK_CLOSE, 1)
    assert "step1" in head                 # 思维链在 </think> 前
    assert '"reads"' in tail and '"a"' in tail   # 最终 JSON 在 </think> 后


def test_plain_and_reasoning_share_answer_json():
    r = _row(writes=["o"])
    assert _answer_json(r) in to_messages(r)["messages"][-1]["content"]
    assert _answer_json(r) in to_messages_reasoning(r)["messages"][-1]["content"]


def test_answer_json_sorted_deterministic():
    r = {"task_type": "SQL", "content": "x",
         "labels": {"reads": [{"table": "z", "columns": None}, {"table": "a", "columns": None}],
                    "writes": []}}
    js = _answer_json(r)
    assert js.index('"a"') < js.index('"z"')   # 表名排序稳定
