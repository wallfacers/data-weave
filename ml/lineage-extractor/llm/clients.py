"""大模型薄封装：多 teacher 血缘打标后端。

- M1 = DashScope(OpenAI 兼容)，`DASHSCOPE_API_KEY` + `QWEN_MODEL`（默认 qwen-max）。
- M2 = 独立 DashScope 兼容 key（`QWEN2_API_KEY` + `QWEN2_MODEL`，默认 qwen3.7-max）；
       无则回退旧路径（`ALI_ANTHROPIC_*` 或 `M2_MODEL`+DASHSCOPE）。
- M3 = Anthropic 兼容第三方（如 DeepSeek pro）：`DEEPSEEK_ANTHROPIC_TOKEN` +
       `DEEPSEEK_ANTHROPIC_BASE_URL` + `DEEPSEEK_MODEL`（默认 deepseek-v4-pro）；开 capture_reasoning。
- M_FLASH = deepseek-v4-flash 便宜档（`DEEPSEEK_FLASH_MODEL`），059 bulk 一致投票用。
多 teacher 供一致性 auto-gold（≥K 方一致才入 gold）；每次调用回传 `_usage`（token 用量）供成本校准，
pro 回传 `_reasoning`（思维链）供 059 推理蒸馏。
"""
from __future__ import annotations
import json, os, re
from pathlib import Path
import httpx
from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

SYSTEM_PROMPT = (
    "You are a data lineage extractor for ETL scripts. Given a PYTHON or SHELL task "
    "script, output ONLY a JSON object {\"reads\": [...], \"writes\": [...]} where each "
    "item is {\"table\": str, \"columns\": [str] or null}. Rules: include a table only if "
    "its literal name appears in the script text; ignore dynamically-built table names, "
    "commented-out SQL, and SQL that is merely printed or logged; if nothing is read or "
    "written, output {\"reads\": [], \"writes\": []}."
)

def _parse_lineage_json(raw: str) -> dict:
    m = re.search(r"\{.*\}", raw, re.DOTALL)
    if not m:
        return {"reads": [], "writes": [], "_error": "no_json"}
    try:
        obj = json.loads(m.group(0))
        return {"reads": obj.get("reads") or [], "writes": obj.get("writes") or []}
    except Exception as e:
        return {"reads": [], "writes": [], "_error": f"json:{e}"}

def _user_msg(task_type: str, content: str) -> str:
    return f"task_type: {task_type}\nscript:\n{content}"

class LlmClient:
    def __init__(self, name: str, backend, model: str):
        self.name, self._backend, self._model = name, backend, model
    def extract(self, task_type: str, content: str) -> dict:
        try:
            return self._backend(self._model, _user_msg(task_type, content))
        except Exception as e:
            return {"reads": [], "writes": [], "_error": f"call:{e}"}

def _dashscope_backend(api_key_env: str = "DASHSCOPE_API_KEY",
                       base_url_env: str = "DASHSCOPE_BASE_URL"):
    """OpenAI 兼容(DashScope)后端；api_key/base_url 由环境变量名参数化，
    支持多个独立 DashScope 兼容 key（m1 与 m2 各自一把）。"""
    from openai import OpenAI
    cli = OpenAI(api_key=os.environ[api_key_env],
                 base_url=os.environ.get(base_url_env,
                          "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    def call(model, user):
        r = cli.chat.completions.create(model=model, temperature=0.0, timeout=60,
              messages=[{"role":"system","content":SYSTEM_PROMPT},
                        {"role":"user","content":user}])
        out = _parse_lineage_json(r.choices[0].message.content or "")
        u = getattr(r, "usage", None)
        if u is not None:  # 059 预算门：抓真实 token 用量供成本校准
            out["_usage"] = {"in": getattr(u, "prompt_tokens", 0) or 0,
                             "out": getattr(u, "completion_tokens", 0) or 0}
        return out
    return call

def _anthropic_backend(base_url_env: str, token_env: str, max_tokens: int = 8192,
                       capture_reasoning: bool = False):
    """Anthropic 兼容后端（阿里云 / DeepSeek 等），base_url/token 参数化。
    max_tokens 放宽到 8192：reasoning 模型（deepseek-v4-pro）思维链吃 output，
    2048 仍会被思维链吃光、最终 JSON 未吐出致 no_json（实测 155 条中 22 条中招）。
    capture_reasoning=True 时保留思维链文本（供 059 推理蒸馏）；抓 usage 供成本校准。"""
    from anthropic import Anthropic
    cli = Anthropic(base_url=os.environ[base_url_env], api_key=os.environ[token_env])
    def call(model, user):
        r = cli.messages.create(model=model, max_tokens=max_tokens, temperature=0.0, timeout=180,
              system=SYSTEM_PROMPT, messages=[{"role":"user","content":user}])
        text = "".join(b.text for b in r.content if getattr(b, "type", "") == "text")
        out = _parse_lineage_json(text)
        if capture_reasoning:
            # DeepSeek anthropic 兼容：思维链可能落 type=="thinking" 块(.thinking)；收下供蒸馏。
            think = "".join(getattr(b, "thinking", "") or "" for b in r.content
                            if getattr(b, "type", "") == "thinking")
            out["_reasoning"] = think or None
        u = getattr(r, "usage", None)
        if u is not None:  # 059 预算门：抓真实 token 用量
            out["_usage"] = {"in": getattr(u, "input_tokens", 0) or 0,
                             "out": getattr(u, "output_tokens", 0) or 0}
        return out
    return call

def _openai_raw_backend(base_url_env: str, key_env: str, timeout: float = 180.0):
    """068：GPT-5.6 经中转站的 OpenAI 兼容后端，用 httpx 裸 POST。
    关键——**不用 OpenAI SDK**：SDK 默认注入的 `x-stainless-*` 遥测头触发中转站 WAF
    （实测 PermissionDeniedError: Your request was blocked，连 models.list/最小 hello 都拦）；
    裸 POST 无这些头即 200。不传 temperature（reasoning 档，裸 POST 默认即可）。抓 usage 供成本核算。
    base_url 须含 /v1（.env 的 GPT_BASE_URL 已含）。"""
    base = os.environ[base_url_env].rstrip("/")
    key = os.environ[key_env]

    def call(model, user):
        headers = {"Authorization": f"Bearer {key}", "Content-Type": "application/json"}
        body = {"model": model,
                "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                             {"role": "user", "content": user}]}
        try:
            with httpx.Client(timeout=timeout) as c:
                r = c.post(base + "/chat/completions", headers=headers, json=body)
            if r.status_code != 200:
                return {"reads": [], "writes": [], "_error": f"http:{r.status_code}"}
            j = r.json()
            content = j["choices"][0]["message"].get("content") or ""
            out = _parse_lineage_json(content)
            u = j.get("usage") or {}
            out["_usage"] = {"in": u.get("prompt_tokens", 0) or 0,
                             "out": u.get("completion_tokens", 0) or 0}
            return out
        except Exception as e:
            return {"reads": [], "writes": [], "_error": f"call:{e}"}
    return call

def load_clients() -> dict[str, "LlmClient"]:
    out = {}
    # M1：DashScope qwen-max（现有 key）。
    if os.environ.get("DASHSCOPE_API_KEY"):
        out["m1"] = LlmClient("m1", _dashscope_backend(), os.environ.get("QWEN_MODEL", "qwen-max"))
    else:
        print("[warn] M1 DASHSCOPE_API_KEY missing; skip")
    # M2：优先独立 DashScope 兼容 key（qwen3.7-max）；否则回退旧 ALI_ANTHROPIC / M2_MODEL 路径。
    if os.environ.get("QWEN2_API_KEY"):
        out["m2"] = LlmClient("m2", _dashscope_backend("QWEN2_API_KEY", "QWEN2_BASE_URL"),
                              os.environ.get("QWEN2_MODEL", "qwen3.7-max"))
    elif os.environ.get("ALI_ANTHROPIC_TOKEN") and os.environ.get("ALI_ANTHROPIC_BASE_URL"):
        out["m2"] = LlmClient("m2", _anthropic_backend("ALI_ANTHROPIC_BASE_URL", "ALI_ANTHROPIC_TOKEN"),
                              os.environ.get("ALI_ANTHROPIC_MODEL", "qwen3-max"))
    elif os.environ.get("M2_MODEL") and os.environ.get("DASHSCOPE_API_KEY"):
        out["m2"] = LlmClient("m2", _dashscope_backend(), os.environ["M2_MODEL"])
    else:
        print("[warn] M2 missing (need QWEN2_API_KEY / ALI_ANTHROPIC_* / M2_MODEL); skip")
    # M3：DeepSeek pro——跨厂商增强一致性 auto-gold；兼作 059 推理老师（capture_reasoning 留思维链）。
    if os.environ.get("DEEPSEEK_ANTHROPIC_TOKEN") and os.environ.get("DEEPSEEK_ANTHROPIC_BASE_URL"):
        out["m3"] = LlmClient("m3", _anthropic_backend("DEEPSEEK_ANTHROPIC_BASE_URL", "DEEPSEEK_ANTHROPIC_TOKEN",
                                                       capture_reasoning=True),
                              os.environ.get("DEEPSEEK_MODEL", "deepseek-v4-pro"))
        # m_flash：deepseek-v4-flash 便宜档，059 bulk 一致投票用。flash 默认亦走思考模式，
        # max_tokens=4096 防思维链吃光 JSON（校准实测 2048 时 1/30 no_json 截断）。
        out["m_flash"] = LlmClient("m_flash",
                                   _anthropic_backend("DEEPSEEK_ANTHROPIC_BASE_URL", "DEEPSEEK_ANTHROPIC_TOKEN",
                                                      max_tokens=4096),
                                   os.environ.get("DEEPSEEK_FLASH_MODEL", "deepseek-v4-flash"))
    # 068 M_GPT：GPT-5.6（OpenAI，经中转站）——跨 qwen/deepseek 的第三独立厂商，破 gold 循环。
    # httpx 裸 POST（避 WAF）。m_gpt=sol 档作 gold 裁判；m_gpt_bulk=luna 便宜档作 silver bulk。
    if os.environ.get("GPT_API_KEY"):
        _gpt_backend = _openai_raw_backend("GPT_BASE_URL", "GPT_API_KEY")
        out["m_gpt"] = LlmClient("m_gpt", _gpt_backend,
                                 os.environ.get("GPT_MODEL", "gpt-5.6-sol"))
        out["m_gpt_bulk"] = LlmClient("m_gpt_bulk", _gpt_backend,
                                      os.environ.get("GPT_BULK_MODEL", "gpt-5.6-luna"))
    return out
