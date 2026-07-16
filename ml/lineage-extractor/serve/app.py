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
from realeval.semantic_grounding import filter_pred_semantic
from realeval.specialist_fusion import fuse
from realeval.tier_classify import classify_tiers

MODEL_DIR = os.environ.get("MODEL_DIR", "out/run1/merged")
MODEL_VERSION = os.environ.get("MODEL_VERSION", "wallfacers/weft-lineage-extractor-1.5b@v1")
# 068 US6 双专家融合：绕开 3B/LoRA 表↔列容量墙。置 LINEAGE_FUSION=1 开——加载表专家
# (MODEL_DIR_TABLE, 表召回天花板) + 列专家(MODEL_DIR_COLUMN, 列 F1 天花板)，两趟确定性解码后
# 由 specialist_fusion.fuse 组合(表集取表专家/并集，列从列专家嫁接)，再走同一 postprocess。
# 关时逐字节等价单模型现状。默认策略 table(A，精度稳、表 R 已过门)；union(B) 冲召回。
FUSION_ENABLED = os.environ.get("LINEAGE_FUSION", "0") == "1"
MODEL_DIR_TABLE = os.environ.get("MODEL_DIR_TABLE", "")
MODEL_DIR_COLUMN = os.environ.get("MODEL_DIR_COLUMN", "")
FUSION_STRATEGY = os.environ.get("LINEAGE_FUSION_STRATEGY", "table")
# 语义 grounding 后处理默认开（gold C 实测 ALL-p +4.2pt、零召回损）；置 0 回滚到旧行为。
GROUND_DEFAULT = os.environ.get("LINEAGE_SEMANTIC_GROUNDING", "1") != "0"
# 置信度分层默认开（063）：reads/writes=自动采纳层（治理安全），reviewReads/Writes=复核候选层。
# 置 LINEAGE_TIERING=0 完全关闭，退回旧的单一 reads/writes 输出（逐字节等价 059）。
TIERING_DEFAULT = os.environ.get("LINEAGE_TIERING", "1") != "0"
# 自动采纳治理阈（累计 CV held-out precision ≥ 此值的最大召回集进自动层）；默认 0.95 治理严格。
AUTOACCEPT_MIN_PRECISION = float(os.environ.get("LINEAGE_AUTOACCEPT_MIN_PRECISION", "0.95"))

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

    def _load(d):
        return AutoModelForCausalLM.from_pretrained(
            d, dtype=torch.bfloat16, device_map=device).eval()

    if FUSION_ENABLED:
        # 双专家共享 base 分词器；两模型各自加载(12G 显存偏紧时可换 4bit 或 base+双 adapter)。
        state["tok"] = AutoTokenizer.from_pretrained(MODEL_DIR_TABLE)
        state["model_table"] = _load(MODEL_DIR_TABLE)
        state["model_col"] = _load(MODEL_DIR_COLUMN)
    else:
        state["tok"] = AutoTokenizer.from_pretrained(MODEL_DIR)
        state["model"] = _load(MODEL_DIR)
    yield
    state.clear()


app = FastAPI(lifespan=lifespan)


class ExtractRequest(BaseModel):
    taskType: str
    content: str


class TableIo(BaseModel):
    table: str
    columns: list[str] | None = None
    tier: str = ""            # 063：置信级 agree/sql_qual/sql_bare/model_qual/model_bare
    confidence: float = 0.0   # 063：该级冻结 precision（复核层排序用）


class ExtractResponse(BaseModel):
    modelVersion: str
    reads: list[TableIo]           # 自动采纳层（≥治理阈，可直接入库）
    writes: list[TableIo]
    reviewReads: list[TableIo] = []   # 063：复核候选层（并集剩余，进人工队列，不自动入库）
    reviewWrites: list[TableIo] = []
    dirFixed: bool = False
    grounded: bool = False
    tiered: bool = False           # 063：是否发生分层（有 ≥1 表被分到复核层）


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


def postprocess(model_text: str, content: str, ground: bool = None,
                tiering: bool = None, thr: float = None) -> dict:
    """解析 → 语义 grounding（剔非表 FP）→ dir_fix（方向 AST 校正）→ 置信度分层（063）。

    纯函数、无 torch/GPU（dir_fix 复用 channel_router 健壮性补丁：片段窗封顶/跳模板/限时）——
    可无 GPU 单测。畸形超大脚本靠 800 字符片段窗封顶防回溯爆内存（与线程无关）。

    语义 grounding（`ground`，默认 GROUND_DEFAULT）：剔叶名只在注释/import/路径/临时视图的假阳。
    置信度分层（`tiering`，默认 TIERING_DEFAULT）：model∪SQL-AST → auto（≥`thr`治理阈，CV 去偏
    诚实采纳集）+ review（并集剩余，召回回收进人工队列）。`tiering=False` 或 env `LINEAGE_TIERING=0`
    → 退回旧单一输出（review 空、tiered=False，逐字节等价 059）。

    返回：{reads, writes}=自动层、{review_reads, review_writes}=复核层、dir_fixed、grounded、tiered。
    """
    if ground is None:
        ground = GROUND_DEFAULT
    if tiering is None:
        tiering = TIERING_DEFAULT
    if thr is None:
        thr = AUTOACCEPT_MIN_PRECISION
    pred = _parse_model_json(model_text)
    grounded = False
    if ground:
        before = len(pred["reads"]) + len(pred["writes"])
        pred = filter_pred_semantic(pred, content)
        grounded = (len(pred["reads"]) + len(pred["writes"])) < before
    fixed = apply_dir_fix(pred, content)   # {reads, writes, dir_fixed}
    out = {"reads": fixed["reads"], "writes": fixed["writes"],
           "review_reads": [], "review_writes": [],
           "dir_fixed": fixed["dir_fixed"], "grounded": grounded, "tiered": False}
    if tiering:
        tiers = classify_tiers({"reads": fixed["reads"], "writes": fixed["writes"]}, content, thr)
        out["reads"], out["writes"] = tiers["auto"]["reads"], tiers["auto"]["writes"]
        out["review_reads"], out["review_writes"] = tiers["review"]["reads"], tiers["review"]["writes"]
        out["tiered"] = tiers["tiered"]
    return out


def _to_io(items) -> list[TableIo]:
    return [TableIo(table=t["table"], columns=t.get("columns"),
                    tier=t.get("tier", ""), confidence=t.get("confidence", 0.0))
            for t in items if t.get("table")]


@app.get("/health")
def health():
    return {"status": "UP", "modelVersion": MODEL_VERSION}


def _generate_pred(model, req: ExtractRequest) -> dict:
    """单模型确定性解码 → 解析出 {reads, writes}（融合前的原始逐表预测）。"""
    tok = state["tok"]
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
    return _parse_model_json(text)


@app.post("/extract", response_model=ExtractResponse)
def extract(req: ExtractRequest) -> ExtractResponse:
    if FUSION_ENABLED:
        # 068 US6：表专家定表集、列专家嫁接列 → 绕开容量墙拿两全（表 R + 列 F1 同高）。
        table_pred = _generate_pred(state["model_table"], req)
        col_pred = _generate_pred(state["model_col"], req)
        pred = fuse(table_pred, col_pred, strategy=FUSION_STRATEGY)
        fixed = postprocess(json.dumps(pred), req.content)
    else:
        pred = _generate_pred(state["model"], req)
        fixed = postprocess(json.dumps(pred), req.content)  # grounding + dir_fix + 分层（063）
    return ExtractResponse(modelVersion=MODEL_VERSION,
                           reads=_to_io(fixed["reads"]), writes=_to_io(fixed["writes"]),
                           reviewReads=_to_io(fixed["review_reads"]),
                           reviewWrites=_to_io(fixed["review_writes"]),
                           dirFixed=fixed["dir_fixed"], grounded=fixed["grounded"],
                           tiered=fixed["tiered"])
