"""041 小模型训练：Qwen2.5-Coder-1.5B-Instruct + LoRA SFT（bf16，12G 单卡）。

选型说明（research D9/D11）：12G 显存上 1.5B bf16 base + LoRA 适配器绰绰有余，
无需 4bit 量化（规避 bitsandbytes 在新架构卡上的兼容风险）；固定 seed 可复现。

用法：
  python train/sft_qlora.py --data data/out/train.jsonl --out out/run1
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import torch
from datasets import Dataset
from peft import LoraConfig
from transformers import AutoTokenizer
from trl import SFTConfig, SFTTrainer

SEED = 20260703
BASE_MODEL = "Qwen/Qwen2.5-Coder-1.5B-Instruct"

SYSTEM_PROMPT = (
    "You are a data lineage extractor for ETL scripts. Given a PYTHON, SHELL, SCALA or "
    "JAVA task script (Spark/Flink jobs included), output ONLY a JSON object "
    "{\"reads\": [...], \"writes\": [...]} where each "
    "item is {\"table\": str, \"columns\": [str] or null}. Rules: include a table only if "
    "its literal name appears in the script text; ignore dynamically-built table names, "
    "commented-out SQL, and SQL that is merely printed or logged; if nothing is read or "
    "written, output {\"reads\": [], \"writes\": []}."
)


# 059 推理蒸馏：assistant 目标 = 思维链 + 最终答案。用显式分隔符 </think>，推理侧无花括号
# 干扰最终 JSON 的解析（eval 取 </think> 之后的 JSON；无分隔符时回退整串末尾 JSON）。
THINK_CLOSE = "</think>"


def _answer_json(row: dict) -> str:
    labels = {
        "reads": sorted(row["labels"]["reads"], key=lambda x: x["table"]),
        "writes": sorted(row["labels"]["writes"], key=lambda x: x["table"]),
    }
    return json.dumps(labels, ensure_ascii=False)


# ── 068 表结构 loss 加权 ────────────────────────────────────────────────────
# 诊断:tri 全列答案里 高熵内容 token 列名:表名 ≈ 4.39:1(实测),列名梯度淹没表名 →
# 表召回被牺牲(密度扫描证 col50 砍列后表 R 回 0.776)。加容量(r64)只补 4pt=非容量墙,
# 是梯度分配失衡。缓解:答案里 "columns":[...] 数组值 token 权重 1.0,其余答案 token
# (表名+骨架+开新边的结构决策 token)权重 W,把表名侧梯度顶回来。W=1.0 → 完全复现基线。
# 门①(评测侧表/列 counts 物理隔离)不受影响:纯训练侧改动,零 eval 耦合。
_COL_ARRAY_RE = re.compile(r'"columns":\s*(\[[^\]]*\])')


def answer_col_spans(answer: str) -> list[tuple[int, int]]:
    """答案字符串里 "columns": [...] 值数组(含中括号)的 [start,end) 区间;null 列不计。"""
    return [(m.start(1), m.end(1)) for m in _COL_ARRAY_RE.finditer(answer)]


def build_weighted_row(row: dict, tokenizer, table_weight: float = 1.0,
                       max_len: int = 2048) -> dict:
    """预分词一行 → {input_ids, labels, weights},逐字复现 SFT 整序列 LM 分词。

    - 复现门:apply_chat_template(tokenize=False) 渲染 + add_special_tokens=False 再分词,
      与 SFT 的 tokenize=True 逐字节一致(Qwen 模板把特殊 token 写成字面文本)。W=1.0 时
      input_ids 必须 == tokenize=True(单测钉死)。
    - 权重:答案内且不在任一列数组区间 → table_weight;其余(列数组值/脚本/prompt/特殊)→ 1.0。
    - labels=input_ids(整序列 LM,collator 只 mask padding,不做 completion masking=保 067/068 配方)。
    """
    msgs = to_messages(row)["messages"]
    answer = _answer_json(row)
    full = tokenizer.apply_chat_template(msgs, tokenize=False, add_generation_prompt=False)
    a_start = full.rfind(answer)
    a_end = a_start + len(answer) if a_start >= 0 else -1
    col_spans = [(a_start + s, a_start + e) for (s, e) in answer_col_spans(answer)] \
        if a_start >= 0 else []
    enc = tokenizer(full, return_offsets_mapping=True, add_special_tokens=False)
    ids, offs = enc["input_ids"], enc["offset_mapping"]
    weights: list[float] = []
    for (a, b) in offs:
        if b <= a:                                   # 空/特殊 token(offset 退化)
            weights.append(1.0)
            continue
        in_answer = a_start >= 0 and a >= a_start and b <= a_end
        in_col = any(a < e and b > s for (s, e) in col_spans)
        weights.append(table_weight if (in_answer and not in_col) else 1.0)
    ids, weights = ids[:max_len], weights[:max_len]
    return {"input_ids": ids, "labels": list(ids), "weights": weights}


def weighted_lm_loss(logits, labels, weights):
    """per-token 加权交叉熵(shift LM)。全 1 权重 → 退化为标准 mean CE(ignore_index=-100)。
    loss = Σ(w_i·ce_i) / Σ(w_i),仅统计非 -100 位置 → 与基线同量纲,W 只改表/列相对梯度。"""
    shift_logits = logits[..., :-1, :].contiguous()
    shift_labels = labels[..., 1:].contiguous()
    shift_w = weights[..., 1:].contiguous()
    vocab = shift_logits.size(-1)
    flat_labels = shift_labels.reshape(-1).clone()
    mask = flat_labels != -100
    flat_labels[~mask] = 0                            # 占位避免 gather 越界
    ce = torch.nn.functional.cross_entropy(
        shift_logits.reshape(-1, vocab), flat_labels, reduction="none")
    ce = ce * mask                                    # mask 位置置零
    w = shift_w.reshape(-1) * mask
    return (ce * shift_w.reshape(-1)).sum() / w.sum().clamp_min(1e-8)


def to_messages(row: dict) -> dict:
    return {
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f"task_type: {row['task_type']}\nscript:\n{row['content']}"},
            {"role": "assistant", "content": _answer_json(row)},
        ]
    }


def to_messages_reasoning(row: dict) -> dict:
    """推理语料行 → 想后答目标：`<think>\\n{reasoning}\\n</think>\\n{json}`。
    教小模型先复述 pro 的推理再吐答案（punch #1）。"""
    think = (row.get("reasoning") or "").strip()
    assistant = f"<think>\n{think}\n{THINK_CLOSE}\n{_answer_json(row)}"
    return {
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f"task_type: {row['task_type']}\nscript:\n{row['content']}"},
            {"role": "assistant", "content": assistant},
        ]
    }


class WeightedCollator:
    """把预分词行 pad 成 batch,保留 per-token weights(整序列 LM:labels=input_ids)。
    remove_unused_columns 必须关,否则 'weights' 列会被 Trainer 丢弃。"""

    def __init__(self, pad_token_id: int):
        self.pad = pad_token_id

    def __call__(self, examples: list[dict]) -> dict:
        ids = [e["input_ids"] for e in examples]
        ws = [e["weights"] for e in examples]
        maxlen = max(len(x) for x in ids)
        n = len(ids)
        input_ids = torch.full((n, maxlen), self.pad, dtype=torch.long)
        attn = torch.zeros((n, maxlen), dtype=torch.long)
        labels = torch.full((n, maxlen), -100, dtype=torch.long)
        weights = torch.ones((n, maxlen), dtype=torch.float)
        for r, (i, w) in enumerate(zip(ids, ws)):
            m = len(i)
            input_ids[r, :m] = torch.tensor(i, dtype=torch.long)
            attn[r, :m] = 1
            labels[r, :m] = torch.tensor(i, dtype=torch.long)     # 整序列 LM,只 pad 处 -100
            weights[r, :m] = torch.tensor(w, dtype=torch.float)
        return {"input_ids": input_ids, "attention_mask": attn,
                "labels": labels, "weights": weights}


class WeightedSFTTrainer(SFTTrainer):
    """068 表结构加权:用 weighted_lm_loss 替换标准 CE,其余(优化器/调度/PEFT)全承 SFT。"""

    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        weights = inputs.pop("weights")
        labels = inputs.pop("labels")
        outputs = model(input_ids=inputs["input_ids"],
                        attention_mask=inputs["attention_mask"])
        loss = weighted_lm_loss(outputs.logits, labels, weights.to(outputs.logits.dtype))
        return (loss, outputs) if return_outputs else loss


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/out/train.jsonl",
                    help="扩语料 plain 银标（答案-only SFT，punch #2）")
    ap.add_argument("--reasoning-data", default=None,
                    help="推理语料（想后答，punch #1）；与 --data 混合训练")
    ap.add_argument("--reasoning-only", action="store_true",
                    help="消融：只用 --reasoning-data 训练（不混 plain 银标）")
    ap.add_argument("--out", default="out/run1")
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--max-len", type=int, default=2048)
    # 041-R 加强实验 C：逐规模泄漏曲线。仅换 base、其余配方(SEED/LoRA/epochs/max-len)不动，
    # 保证 0.5B/1.5B/3B 是干净对比。默认保持 1.5B 向后兼容。
    ap.add_argument("--base-model", default=BASE_MODEL,
                    help="HF base 模型 id，如 Qwen/Qwen2.5-Coder-0.5B-Instruct / -3B-Instruct")
    # 稳定性旋钮（默认=原配方，向后兼容）：bf16 LoRA 早期数值发散时用更小 lr / 更长 warmup / 更紧梯度裁剪。
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--warmup", type=float, default=0.03)
    ap.add_argument("--max-grad-norm", type=float, default=1.0)
    # 067 缓解重训：LoRA 容量旋钮（默认 r16/alpha32=059 固化配方，向后兼容）。
    # 联合表+列任务下 r16 容量争用致表 recall 掉；扩 rank 直击容量争用。
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--lora-alpha", type=int, default=32)
    # 068 表结构 loss 加权（默认 1.0=原配方，向后兼容）：答案里非列数组 token 的 loss 乘 W，
    # 把被列名淹没的表名梯度顶回来。实测 tri 全列 列名:表名≈4.39:1，col50(1.99:1) 表 R 回 0.776；
    # W=3 令有效比≈1.46:1（略偏表，留软性折扣余量），列仍绝对梯度主力→列 F1 受保护。
    ap.add_argument("--table-loss-weight", type=float, default=1.0,
                    help="答案内表结构 token 的 loss 权重（列数组值恒 1.0）；>1.0 启用加权路径")
    args = ap.parse_args()
    base_model = args.base_model
    weighted = abs(args.table_loss_weight - 1.0) > 1e-9

    def _read(p):
        return [json.loads(l) for l in Path(p).read_text(encoding="utf-8").splitlines() if l.strip()]

    if weighted and (args.reasoning_data or args.reasoning_only):
        raise SystemExit("--table-loss-weight 暂只支持 plain --data 路径（tri 重训不用 reasoning）")

    if weighted:
        # 加权路径：自行预分词（复现 SFT 整序列 LM 分词）+ per-token weights。
        tok = AutoTokenizer.from_pretrained(base_model)
        plain = _read(args.data)
        rows = [build_weighted_row(r, tok, table_weight=args.table_loss_weight,
                                   max_len=args.max_len) for r in plain]
        ds = Dataset.from_list(rows)
        print(f"weighted plain rows: {len(plain)} (table_loss_weight={args.table_loss_weight})")
    else:
        examples = []
        if not args.reasoning_only:
            plain = _read(args.data)
            examples += [to_messages(r) for r in plain]
            print(f"plain silver rows: {len(plain)}")
        if args.reasoning_data:
            reasoning = _read(args.reasoning_data)
            examples += [to_messages_reasoning(r) for r in reasoning]
            print(f"reasoning rows: {len(reasoning)}")
        if not examples:
            raise SystemExit("no training rows (检查 --data / --reasoning-data)")
        ds = Dataset.from_list(examples)
    print(f"train rows total: {len(ds)}")

    peft_config = LoraConfig(
        r=args.lora_r, lora_alpha=args.lora_alpha, lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )

    cfg = SFTConfig(
        output_dir=args.out,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=2,
        gradient_accumulation_steps=8,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_ratio=args.warmup,
        max_grad_norm=args.max_grad_norm,
        logging_steps=20,
        save_strategy="epoch",
        bf16=True,
        max_length=args.max_len,
        packing=False,
        gradient_checkpointing=True,
        seed=SEED,
        report_to=[],
        model_init_kwargs={"dtype": torch.bfloat16},
        # 加权路径：喂预分词数据 → 关掉 SFT 预处理与列裁剪，否则 'weights' 列被丢弃。
        remove_unused_columns=not weighted,
        dataset_kwargs={"skip_prepare_dataset": True} if weighted else None,
    )

    if weighted:
        trainer = WeightedSFTTrainer(
            model=base_model,
            args=cfg,
            train_dataset=ds,
            peft_config=peft_config,
            data_collator=WeightedCollator(tok.pad_token_id if tok.pad_token_id is not None
                                           else tok.eos_token_id),
        )
    else:
        trainer = SFTTrainer(
            model=base_model,
            args=cfg,
            train_dataset=ds,
            peft_config=peft_config,
        )
    trainer.train()
    trainer.save_model(args.out + "/adapter")

    # 合并 LoRA → 独立可部署权重（serve 与发布用）
    merged = trainer.model.merge_and_unload()
    merged.save_pretrained(args.out + "/merged", safe_serialization=True)
    AutoTokenizer.from_pretrained(base_model).save_pretrained(args.out + "/merged")
    print("saved:", args.out + "/merged")


if __name__ == "__main__":
    main()
