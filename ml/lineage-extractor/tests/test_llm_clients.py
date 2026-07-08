from llm.clients import _parse_lineage_json, load_clients

def test_parse_extracts_json_from_noisy_text():
    raw = 'Sure!\n```json\n{"reads":[{"table":"a","columns":null}],"writes":[]}\n```'
    got = _parse_lineage_json(raw)
    assert got == {"reads":[{"table":"a","columns":None}],"writes":[]}

def test_parse_returns_empty_on_garbage():
    assert _parse_lineage_json("no json here") == {"reads":[],"writes":[],"_error":"no_json"}

def test_load_clients_skips_m2_when_base_url_missing(monkeypatch):
    # M2 只有 TOKEN、缺 BASE_URL 且无 M2_MODEL 回退：load_clients 不崩溃，跳过 m2 并 warn
    monkeypatch.setenv("ALI_ANTHROPIC_TOKEN", "sk-fake-token")
    monkeypatch.delenv("ALI_ANTHROPIC_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    monkeypatch.delenv("M2_MODEL", raising=False)
    clients = load_clients()
    assert "m2" not in clients


def _mark_backends(monkeypatch):
    """把两个 backend 工厂换成带 _via 标记的哨兵，避免真调 API，可断言 m2 走哪条路。"""
    import llm.clients as C
    monkeypatch.setattr(C, "_dashscope_backend",
                        lambda: (lambda m, u: {"reads": [], "writes": [], "_via": "dashscope"}))
    monkeypatch.setattr(C, "_ali_anthropic_backend",
                        lambda: (lambda m, u: {"reads": [], "writes": [], "_via": "anthropic"}))


def test_m2_falls_back_to_dashscope_model_when_no_anthropic(monkeypatch):
    # 无 ALI_ANTHROPIC_*，但设了 M2_MODEL + DASHSCOPE：m2 走 DashScope backend + 该模型名（reasoning 模型路径）
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-fake")
    monkeypatch.setenv("M2_MODEL", "qwen3.7-max")
    monkeypatch.delenv("ALI_ANTHROPIC_TOKEN", raising=False)
    monkeypatch.delenv("ALI_ANTHROPIC_BASE_URL", raising=False)
    _mark_backends(monkeypatch)
    clients = load_clients()
    assert clients["m2"]._model == "qwen3.7-max"
    assert clients["m2"].extract("SQL", "x")["_via"] == "dashscope"
    assert clients["m1"].extract("SQL", "x")["_via"] == "dashscope"


def test_m2_prefers_anthropic_endpoint_when_configured(monkeypatch):
    # 向后兼容：ALI_ANTHROPIC_* 齐全时优先走老的 anthropic 端点，即便 M2_MODEL 也在
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-fake")
    monkeypatch.setenv("M2_MODEL", "qwen3.7-max")
    monkeypatch.setenv("ALI_ANTHROPIC_TOKEN", "sk-fake-token")
    monkeypatch.setenv("ALI_ANTHROPIC_BASE_URL", "https://fake/anthropic")
    _mark_backends(monkeypatch)
    clients = load_clients()
    assert clients["m2"].extract("SQL", "x")["_via"] == "anthropic"
