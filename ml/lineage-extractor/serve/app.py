"""041 推理 sidecar（FR-013，contracts §5）。

独立进程、不嵌入平台服务端（宪法原则 IV 合规姿态）；确定性解码（do_sample=False），
同版本模型同输入必同输出。平台侧 ModelExtractor 调 POST /extract，超时 2s 即弃。

启动：
  GPU:  MODEL_DIR=out/run1/merged uvicorn serve.app:app --host 0.0.0.0 --port 8500
  CPU:  同上（自动检测；1.5B bf16 CPU 单请求秒级，批量 push 场景异步可接受）
"""

from __future__ import annotations

import json
import os
import re
from contextlib import asynccontextmanager

import torch
from fastapi import FastAPI
from pydantic import BaseModel

from realeval.dir_fix import apply_dir_fix

MODEL_DIR = os.environ.get("MODEL_DIR", "out/run1/merged")
MODEL_VERSION = os.environ.get("MODEL_VERSION", "wallfacers/weft-lineage-extractor-1.5b@v1")

SYSTEM_PROMPT = (
    "You are a data lineage extractor for ETL scripts. Given a PYTHON or SHELL task "
    "script, output ONLY a JSON object {\"reads\": [...], \"writes\": [...]} where each "
    "item is {\"table\": str, \"columns\": [str] or null}. Rules: include a table only if "
    "its literal name appears in the script text; ignore dynamically-built table names, "
    "commented-out SQL, and SQL that is merely printed or logged; if nothing is read or "
    "written, output {\"reads\": [], \"writes\": []}."
)

state: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    from transformers import AutoModelForCausalLM, AutoTokenizer
    device = "cuda" if torch.cuda.is_available() else "cpu"
    state["tok"] = AutoTokenizer.from_pretrained(MODEL_DIR)
    state["model"] = AutoModelForCausalLM.from_pretrained(
        MODEL_DIR, dtype=torch.bfloat16, device_map=device).eval()
    yield
    state.clear()


app = FastAPI(lifespan=lifespan)


class ExtractRequest(BaseModel):
    taskType: str
    content: str


class TableIo(BaseModel):
    table: str
    columns: list[str] | None = None


class ExtractResponse(BaseModel):
    modelVersion: str
    reads: list[TableIo]
    writes: list[TableIo]
    dirFixed: bool = False


def _parse_model_json(text: str) -> dict:
    """从模型输出提取 {reads, writes}（非法/无 JSON → 空）。"""
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if not m:
        return {"reads": [], "writes": []}
    try:
        obj = json.loads(m.group(0))
    except Exception:
        return {"reads": [], "writes": []}
    reads = [t for t in obj.get("reads") or [] if isinstance(t, dict) and t.get("table")]
    writes = [t for t in obj.get("writes") or [] if isinstance(t, dict) and t.get("table")]
    return {"reads": reads, "writes": writes}


def postprocess(model_text: str, content: str) -> dict:
    """解析模型 JSON → dir_fix 方向修正（表由模型定、方向由 AST 定）。

    纯函数、无 torch/GPU（dir_fix 复用 channel_router 健壮性补丁：片段窗封顶/跳模板/限时）——
    可无 GPU 单测（T024）。畸形超大脚本靠 800 字符片段窗封顶防回溯爆内存（与线程无关）。
    """
    pred = _parse_model_json(model_text)
    return apply_dir_fix(pred, content)


def _to_io(items) -> list[TableIo]:
    return [TableIo(table=t["table"], columns=t.get("columns")) for t in items if t.get("table")]


@app.get("/health")
def health():
    return {"status": "UP", "modelVersion": MODEL_VERSION}


@app.post("/extract", response_model=ExtractResponse)
def extract(req: ExtractRequest) -> ExtractResponse:
    tok, model = state["tok"], state["model"]
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": f"task_type: {req.taskType}\nscript:\n{req.content[:4000]}"},
    ]
    inputs = tok.apply_chat_template(messages, add_generation_prompt=True, tokenize=True,
                                     return_dict=True, return_tensors="pt").to(model.device)
    with torch.no_grad():
        out = model.generate(**inputs, max_new_tokens=256, do_sample=False,
                             pad_token_id=tok.pad_token_id or tok.eos_token_id)
    text = tok.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()
    fixed = postprocess(text, req.content)  # dir_fix：方向由 AST 校正
    return ExtractResponse(modelVersion=MODEL_VERSION,
                           reads=_to_io(fixed["reads"]), writes=_to_io(fixed["writes"]),
                           dirFixed=fixed["dir_fixed"])
