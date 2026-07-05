"""041-R 大模型薄封装：M1=DashScope(OpenAI 兼容)、M2=阿里云 Anthropic 兼容端点。"""
from __future__ import annotations
import json, os, re
from pathlib import Path
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

def _dashscope_backend():
    from openai import OpenAI
    cli = OpenAI(api_key=os.environ["DASHSCOPE_API_KEY"],
                 base_url=os.environ.get("DASHSCOPE_BASE_URL",
                          "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    def call(model, user):
        r = cli.chat.completions.create(model=model, temperature=0.0, timeout=60,
              messages=[{"role":"system","content":SYSTEM_PROMPT},
                        {"role":"user","content":user}])
        return _parse_lineage_json(r.choices[0].message.content or "")
    return call

def _ali_anthropic_backend():
    from anthropic import Anthropic
    cli = Anthropic(base_url=os.environ["ALI_ANTHROPIC_BASE_URL"],
                    api_key=os.environ["ALI_ANTHROPIC_TOKEN"])
    def call(model, user):
        r = cli.messages.create(model=model, max_tokens=512, temperature=0.0, timeout=60,
              system=SYSTEM_PROMPT, messages=[{"role":"user","content":user}])
        text = "".join(b.text for b in r.content if getattr(b, "type", "") == "text")
        return _parse_lineage_json(text)
    return call

def load_clients() -> dict[str, "LlmClient"]:
    out = {}
    if os.environ.get("DASHSCOPE_API_KEY"):
        out["m1"] = LlmClient("m1", _dashscope_backend(), os.environ.get("QWEN_MODEL", "qwen-max"))
    else:
        print("[warn] M1 DASHSCOPE_API_KEY missing; skip")
    if os.environ.get("ALI_ANTHROPIC_TOKEN") and os.environ.get("ALI_ANTHROPIC_BASE_URL"):
        out["m2"] = LlmClient("m2", _ali_anthropic_backend(), os.environ.get("ALI_ANTHROPIC_MODEL", "qwen3-max"))
    else:
        print("[warn] M2 ALI_ANTHROPIC_TOKEN/ALI_ANTHROPIC_BASE_URL missing; skip")
    return out
