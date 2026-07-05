"""041 小模型训练：Qwen2.5-Coder-1.5B-Instruct + LoRA SFT（bf16，12G 单卡）。

选型说明（research D9/D11）：12G 显存上 1.5B bf16 base + LoRA 适配器绰绰有余，
无需 4bit 量化（规避 bitsandbytes 在新架构卡上的兼容风险）；固定 seed 可复现。

用法：
  python train/sft_qlora.py --data data/out/train.jsonl --out out/run1
"""

from __future__ import annotations

import argparse
import json
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


def to_messages(row: dict) -> dict:
    labels = {
        "reads": sorted(row["labels"]["reads"], key=lambda x: x["table"]),
        "writes": sorted(row["labels"]["writes"], key=lambda x: x["table"]),
    }
    return {
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f"task_type: {row['task_type']}\nscript:\n{row['content']}"},
            {"role": "assistant", "content": json.dumps(labels, ensure_ascii=False)},
        ]
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/out/train.jsonl")
    ap.add_argument("--out", default="out/run1")
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--max-len", type=int, default=2048)
    # 041-R 加强实验 C：逐规模泄漏曲线。仅换 base、其余配方(SEED/LoRA/epochs/max-len)不动，
    # 保证 0.5B/1.5B/3B 是干净对比。默认保持 1.5B 向后兼容。
    ap.add_argument("--base-model", default=BASE_MODEL,
                    help="HF base 模型 id，如 Qwen/Qwen2.5-Coder-0.5B-Instruct / -3B-Instruct")
    args = ap.parse_args()
    base_model = args.base_model

    rows = [json.loads(l) for l in Path(args.data).read_text(encoding="utf-8").splitlines() if l.strip()]
    ds = Dataset.from_list([to_messages(r) for r in rows])
    print(f"train rows: {len(ds)}")

    peft_config = LoraConfig(
        r=16, lora_alpha=32, lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )

    cfg = SFTConfig(
        output_dir=args.out,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=2,
        gradient_accumulation_steps=8,
        learning_rate=2e-4,
        lr_scheduler_type="cosine",
        warmup_ratio=0.03,
        logging_steps=20,
        save_strategy="epoch",
        bf16=True,
        max_length=args.max_len,
        packing=False,
        gradient_checkpointing=True,
        seed=SEED,
        report_to=[],
        model_init_kwargs={"dtype": torch.bfloat16},
    )

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
