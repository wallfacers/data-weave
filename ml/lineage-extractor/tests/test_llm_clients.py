from llm.clients import _parse_lineage_json, load_clients

def test_parse_extracts_json_from_noisy_text():
    raw = 'Sure!\n```json\n{"reads":[{"table":"a","columns":null}],"writes":[]}\n```'
    got = _parse_lineage_json(raw)
    assert got == {"reads":[{"table":"a","columns":None}],"writes":[]}

def test_parse_returns_empty_on_garbage():
    assert _parse_lineage_json("no json here") == {"reads":[],"writes":[],"_error":"no_json"}

def test_load_clients_skips_m2_when_base_url_missing(monkeypatch):
    # M2 只有 TOKEN、缺 BASE_URL：load_clients 不应崩溃，应跳过 m2 并 warn
    monkeypatch.setenv("ALI_ANTHROPIC_TOKEN", "sk-fake-token")
    monkeypatch.delenv("ALI_ANTHROPIC_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    clients = load_clients()
    assert "m2" not in clients
