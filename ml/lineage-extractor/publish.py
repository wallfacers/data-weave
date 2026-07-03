"""041 发布（FR-015）：模型 + 数据集 + 评估报告 → 用户 HF 私有 repo。

前置：`hf auth login` 的 token 具备 write 权限；评估已过闸（eval-report.md 存在且结论过闸）。

用法：python publish.py [--model-dir out/run1/merged] [--version v1]
"""

from __future__ import annotations

import argparse
from pathlib import Path

from huggingface_hub import HfApi

MODEL_REPO = "wallfacers/weft-lineage-extractor-1.5b"
DATA_REPO = "wallfacers/weft-script-lineage-synth"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", default="out/run1/merged")
    ap.add_argument("--data-dir", default="data/out")
    ap.add_argument("--report", default="out/eval-report.md")
    ap.add_argument("--version", default="v1")
    args = ap.parse_args()

    api = HfApi()
    api.create_repo(MODEL_REPO, repo_type="model", private=True, exist_ok=True)
    api.create_repo(DATA_REPO, repo_type="dataset", private=True, exist_ok=True)

    report = Path(args.report)
    card = (f"# weft-lineage-extractor-1.5b ({args.version})\n\n"
            f"Base: Qwen/Qwen2.5-Coder-1.5B-Instruct + LoRA SFT（041 合成数据）。\n"
            f"用途：从 PYTHON/SHELL 任务脚本抽取表/字段读写引用（Weft 血缘 SCRIPT_MODEL 通道）。\n\n"
            + (report.read_text(encoding="utf-8") if report.exists() else "（评估报告缺失）"))
    Path(args.model_dir, "README.md").write_text(card, encoding="utf-8")

    api.upload_folder(folder_path=args.model_dir, repo_id=MODEL_REPO, repo_type="model",
                      commit_message=f"041 {args.version}: merged weights + eval card")
    api.create_tag(MODEL_REPO, tag=args.version, repo_type="model", exist_ok=True)

    api.upload_folder(folder_path=args.data_dir, repo_id=DATA_REPO, repo_type="dataset",
                      allow_patterns=["*.jsonl"],
                      commit_message=f"041 {args.version}: train+heldout synth data")
    if report.exists():
        api.upload_file(path_or_fileobj=str(report), path_in_repo="eval-report.md",
                        repo_id=DATA_REPO, repo_type="dataset")
    api.create_tag(DATA_REPO, tag=args.version, repo_type="dataset", exist_ok=True)
    print(f"published: {MODEL_REPO}@{args.version} + {DATA_REPO}@{args.version}")


if __name__ == "__main__":
    main()
