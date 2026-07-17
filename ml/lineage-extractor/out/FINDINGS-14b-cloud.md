# FINDINGS — 14B 血缘抽取 LoRA 云上首训(AutoDL RTX6000D)

2026-07-17。首次把血缘抽取小模型从 3B 放大到 **14B**,在租用的云 GPU 上全自动完成训练→合并→验证。本文档固化背景知识与复现要点;权重不入库(走 HF/云盘),下面记录产物位置。

## 环境

| 项 | 值 |
|---|---|
| 平台 | AutoDL 实例(RTX 6000D 单卡) |
| GPU | RTX 6000D,**84GB** 显存(85651 MiB),driver 595.71.05 |
| CPU/内存 | Xeon Platinum 8470Q,配额 22 核 / 110GB |
| 数据盘 | `/root/autodl-tmp`(付费盘,250GB /dev/md0) |
| 基础镜像 | torch **2.8.0+cu128**(CUDA 可用);transformers/trl/peft/datasets/accelerate/modelscope **全缺需装** |
| python | `/root/miniconda3/bin/python`(非交互 shell 不在 PATH,命令须绝对路径) |
| pip 源 | 阿里云镜像(默认,快);`/etc/network_turbo` 供 HF/github 加速 |
| base 下载 | `modelscope download Qwen/Qwen2.5-Coder-14B-Instruct`,30GB,国内但慢(~6–22MB/s,约 46 分钟) |

装的依赖(按本地 041 配方版本,torch 2.8 也能跑,无需升 2.10):
`transformers==5.5.0 trl==0.24.0 peft==0.18.1 datasets==4.3.0 accelerate==1.13.0 modelscope`

## 远程 workflow(可复用)

1. 本地 `pip install --break-system-packages paramiko`;paramiko 用密码连一次,把 `~/.ssh/id_ed25519.pub` 追加到远端 `authorized_keys` → 之后系统 `ssh` 全程免密(tmux/scp/长训练都顺)。密码绝不写盘。
2. `scp` 上传 `train/sft_qlora.py` + `data/out/train.jsonl` 到数据盘。
3. 跑前 `sed` 把 `save_strategy="epoch"` 改成 `steps` + `save_steps=150` + `save_total_limit=3` —— **兜底防定时关机白跑**(否则 epoch 未完关机=零产出)。
4. `tmux new-session -d -s train` 后台跑,日志重定向到数据盘;本地脚本每 15s 轮询 GPU+日志。

## 训练配置与结果

- 命令:`sft_qlora.py --base-model <本地14B路径> --data data/out/train.jsonl --out out/run-14b --epochs 2 --max-len 2048`
- 配方:bf16 LoRA(**非 4bit**)r16/α32,有效 batch 16(2×8),cosine lr 2e-4,gradient_checkpointing。
- 规模:1 万样本 → **1250 步**,**75 分钟** @ ~3.5s/step。
- 显存:**35–40GB / 84GB**(84G 巨富余,14B bf16 base ~28G + LoRA + 激活)。
- **loss:2.242(step20) → 0.589(40) → 0.255(100) → 0.208(300) → 收敛**,无 NaN/发散。

## 产物(权重不入库)

| 产物 | 大小 | 位置 |
|---|---|---|
| merged(可部署 bf16 全权重) | 29.5GB | 云盘 `/root/autodl-tmp/lineage/out/run-14b/merged/`(待处置) |
| adapter(LoRA) | 275MB | **已拉回本地** `out/run-14b/adapter/`(gitignored) |

## 验证

heldout 前 3 条真实推理,模型输出与金标 **3/3 逐字节完全一致**(表名 / reads·writes / 列名全中)。证明训练产物可用,非坏文件。

## 待办(次日续)

机器 06:00 定时关机(AutoDL 定时=关机非释放,数据盘保留,重开可续;重开后 host/port 可能变,免密公钥在盘上应仍在)。

1. **全量 heldout(600 条)评测**,出 14B 表/列 P/R/F1,对比 3B(非空-p 0.742,见 `FINDINGS-059.md`/`PAPER-EVIDENCE-068.md`),量化放大到 14B 的增益。
2. **merged 29.5GB 处置**:发 HF(`wallfacers/weft-lineage-extractor-*` 一贯做法)/ 留云盘 / 下载回本地(上行慢)。
