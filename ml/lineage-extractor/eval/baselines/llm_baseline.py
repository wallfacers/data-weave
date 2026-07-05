"""041-R M1/M2 大模型基线：把 llm.clients.LlmClient 包成统一 predict(row) 接口。"""
from __future__ import annotations


def make_predict(client):
    def predict(row: dict) -> dict:
        return client.extract(row["task_type"], row["content"])
    return predict
