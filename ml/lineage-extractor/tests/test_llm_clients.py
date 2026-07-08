from llm.clients import _parse_lineage_json, load_clients

# 所有 teacher 相关环境变量——每个 load_clients 测试先清空，再按需设置。
# （llm.clients 导入时 load_dotenv 会把真实 .env 的 QWEN2/DEEPSEEK/DASHSCOPE 灌入 os.environ，
#  不清则泄漏进测试。）
_TEACHER_ENVS = [
    "DASHSCOPE_API_KEY", "DASHSCOPE_BASE_URL", "QWEN_MODEL",
    "QWEN2_API_KEY", "QWEN2_BASE_URL", "QWEN2_MODEL",
    "ALI_ANTHROPIC_TOKEN", "ALI_ANTHROPIC_BASE_URL", "ALI_ANTHROPIC_MODEL", "M2_MODEL",
    "DEEPSEEK_ANTHROPIC_TOKEN", "DEEPSEEK_ANTHROPIC_BASE_URL", "DEEPSEEK_MODEL",
]


def _clean(monkeypatch):
    for k in _TEACHER_ENVS:
        monkeypatch.delenv(k, raising=False)


def _mark_backends(monkeypatch):
    """把两个 backend 工厂换成带 _via 标记的哨兵，避免真调 API，可断言各 teacher 走哪条路。
    新签名带参（api_key_env/base_url_env、base_url_env/token_env），哨兵吞掉任意参数。"""
    import llm.clients as C
    monkeypatch.setattr(C, "_dashscope_backend",
                        lambda *a, **k: (lambda m, u: {"reads": [], "writes": [], "_via": "dashscope"}))
    monkeypatch.setattr(C, "_anthropic_backend",
                        lambda *a, **k: (lambda m, u: {"reads": [], "writes": [], "_via": "anthropic"}))


def test_parse_extracts_json_from_noisy_text():
    raw = 'Sure!\n```json\n{"reads":[{"table":"a","columns":null}],"writes":[]}\n```'
    got = _parse_lineage_json(raw)
    assert got == {"reads":[{"table":"a","columns":None}],"writes":[]}

def test_parse_returns_empty_on_garbage():
    assert _parse_lineage_json("no json here") == {"reads":[],"writes":[],"_error":"no_json"}


def test_load_clients_skips_m2_when_no_credentials(monkeypatch):
    # M2 只有 anthropic TOKEN、缺 BASE_URL，且无 QWEN2 / M2_MODEL 回退：跳过 m2，不崩溃。
    _clean(monkeypatch)
    monkeypatch.setenv("ALI_ANTHROPIC_TOKEN", "sk-fake-token")
    clients = load_clients()
    assert "m2" not in clients


def test_m2_prefers_qwen2_dashscope_key(monkeypatch):
    # 新首选：独立 QWEN2_API_KEY → m2 走 DashScope backend + QWEN2_MODEL（qwen3.7-max）。
    _clean(monkeypatch)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-fake-m1")
    monkeypatch.setenv("QWEN2_API_KEY", "sk-fake-m2")
    monkeypatch.setenv("QWEN2_MODEL", "qwen3.7-max")
    monkeypatch.setenv("ALI_ANTHROPIC_TOKEN", "sk-fake-token")   # 即便老 anthropic 也在，QWEN2 优先
    monkeypatch.setenv("ALI_ANTHROPIC_BASE_URL", "https://fake/anthropic")
    _mark_backends(monkeypatch)
    clients = load_clients()
    assert clients["m2"]._model == "qwen3.7-max"
    assert clients["m2"].extract("SQL", "x")["_via"] == "dashscope"


def test_m2_falls_back_to_dashscope_model_when_no_anthropic(monkeypatch):
    # 无 QWEN2 / ALI_ANTHROPIC，但 M2_MODEL + DASHSCOPE：m2 走 DashScope backend + 该模型名。
    _clean(monkeypatch)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-fake")
    monkeypatch.setenv("M2_MODEL", "qwen3.7-max")
    _mark_backends(monkeypatch)
    clients = load_clients()
    assert clients["m2"]._model == "qwen3.7-max"
    assert clients["m2"].extract("SQL", "x")["_via"] == "dashscope"
    assert clients["m1"].extract("SQL", "x")["_via"] == "dashscope"


def test_m2_prefers_anthropic_endpoint_over_m2_model(monkeypatch):
    # 无 QWEN2 时，ALI_ANTHROPIC_* 齐全优先走 anthropic 端点（即便 M2_MODEL 也在）。
    _clean(monkeypatch)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-fake")
    monkeypatch.setenv("M2_MODEL", "qwen3.7-max")
    monkeypatch.setenv("ALI_ANTHROPIC_TOKEN", "sk-fake-token")
    monkeypatch.setenv("ALI_ANTHROPIC_BASE_URL", "https://fake/anthropic")
    _mark_backends(monkeypatch)
    clients = load_clients()
    assert clients["m2"].extract("SQL", "x")["_via"] == "anthropic"


def test_m3_loads_on_deepseek_anthropic(monkeypatch):
    # M3：DEEPSEEK anthropic 端点齐全 → 加载第三方 teacher（跨厂商增强一致性 auto-gold）。
    _clean(monkeypatch)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-fake")
    monkeypatch.setenv("DEEPSEEK_ANTHROPIC_TOKEN", "sk-fake-ds")
    monkeypatch.setenv("DEEPSEEK_ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")
    monkeypatch.setenv("DEEPSEEK_MODEL", "deepseek-v4-pro")
    _mark_backends(monkeypatch)
    clients = load_clients()
    assert clients["m3"]._model == "deepseek-v4-pro"
    assert clients["m3"].extract("SQL", "x")["_via"] == "anthropic"


def test_m3_absent_without_deepseek(monkeypatch):
    _clean(monkeypatch)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-fake")
    _mark_backends(monkeypatch)
    assert "m3" not in load_clients()
