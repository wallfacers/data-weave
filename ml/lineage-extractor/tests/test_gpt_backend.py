"""068 T004（Foundational）：GPT-5.6 teacher httpx 裸 POST 后端契约。

见 specs/068-tri-vendor-gold/contracts/gpt-client.md。核心：不注入 x-stainless-* 头
（触发中转站 WAF）、解析血缘 JSON+usage、非 200/解析失败弃权不抛、无 key 不注册。
"""
from __future__ import annotations
import json
import httpx
import pytest

import llm.clients as clients


@pytest.fixture(autouse=True)
def _gpt_env(monkeypatch):
    monkeypatch.setenv("GPT_BASE_URL", "https://relay.test/v1")
    monkeypatch.setenv("GPT_API_KEY", "sk-test-not-real")
    monkeypatch.setenv("GPT_MODEL", "gpt-5.6-sol")


def _install_fake_client(monkeypatch, *, status=200, payload=None, capture=None):
    class _Resp:
        status_code = status

        def json(self):
            return payload

    class _Client:
        def __init__(self, *a, **k):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def post(self, url, headers=None, json=None):
            if capture is not None:
                capture["url"] = url
                capture["headers"] = headers or {}
                capture["body"] = json
            return _Resp()

    monkeypatch.setattr(httpx, "Client", _Client)


def _ok_payload(content, usage=None):
    return {"choices": [{"message": {"content": content}}],
            "usage": usage or {"prompt_tokens": 264, "completion_tokens": 155}}


def test_no_stainless_headers_and_bearer_present(monkeypatch):
    cap = {}
    _install_fake_client(monkeypatch, payload=_ok_payload('{"reads":[],"writes":[]}'), capture=cap)
    backend = clients._openai_raw_backend("GPT_BASE_URL", "GPT_API_KEY")
    backend("gpt-5.6-sol", "task_type: SQL\nscript:\nselect 1")
    hdrs = {k.lower(): v for k, v in cap["headers"].items()}
    assert not any(k.startswith("x-stainless") for k in hdrs), f"stainless 头泄漏: {hdrs}"
    assert hdrs.get("authorization") == "Bearer sk-test-not-real"
    assert cap["url"].endswith("/chat/completions")


def test_parses_lineage_json_and_usage(monkeypatch):
    content = '{"reads":[{"table":"raw.orders","columns":["amount","user_id"]}],"writes":[{"table":"analytics.daily","columns":["rev"]}]}'
    _install_fake_client(monkeypatch, payload=_ok_payload(content, {"prompt_tokens": 300, "completion_tokens": 120}))
    backend = clients._openai_raw_backend("GPT_BASE_URL", "GPT_API_KEY")
    out = backend("gpt-5.6-sol", "u")
    assert out["reads"] == [{"table": "raw.orders", "columns": ["amount", "user_id"]}]
    assert out["writes"] == [{"table": "analytics.daily", "columns": ["rev"]}]
    assert out["_usage"] == {"in": 300, "out": 120}


def test_non_200_abstains_no_raise(monkeypatch):
    _install_fake_client(monkeypatch, status=403, payload={"error": "blocked"})
    backend = clients._openai_raw_backend("GPT_BASE_URL", "GPT_API_KEY")
    out = backend("gpt-5.6-sol", "u")
    assert out["reads"] == [] and out["writes"] == []
    assert "_error" in out


def test_unparseable_content_abstains(monkeypatch):
    _install_fake_client(monkeypatch, payload=_ok_payload("sorry, I cannot help with that"))
    backend = clients._openai_raw_backend("GPT_BASE_URL", "GPT_API_KEY")
    out = backend("gpt-5.6-sol", "u")
    assert out["reads"] == [] and out["writes"] == []


def test_not_registered_without_key(monkeypatch):
    monkeypatch.delenv("GPT_API_KEY", raising=False)
    reg = clients.load_clients()
    assert "m_gpt" not in reg and "m_gpt_bulk" not in reg


def test_registered_with_key(monkeypatch):
    reg = clients.load_clients()
    assert "m_gpt" in reg
    assert reg["m_gpt"]._model == "gpt-5.6-sol"
    assert reg["m_gpt_bulk"]._model == "gpt-5.6-luna"
