# 068 部署吞吐与成本台账 —— 自部署 3B vs 云厂商 API

> 目的：回答"部署一个 3B 的并发量级 vs 直接用云 API 的成本对比"。吞吐数字来自本机
> RTX 5070（sm_120）**真跑**（`realeval/bench_serving.py`），云价按公开挂牌口径（标注为估算），
> GPT 口径用 068 中转站**真实后台账单**反推。真实血缘输出极短（均 ~22 token/req）→ prefill 受限。

## 1. 3B 自部署真实吞吐（RTX 5070，run-tri-3b-lw3/merged，确定性解码）

### 1.1 transformers eager（`realeval/bench_serving.py`，实测）
| 指标 | 值 |
| --- | --- |
| 单流延迟 p50 / p99 | **363ms** / 6.4s（p99 为一条长输出 straggler） |
| 单流可持续吞吐 | ~0.9–1.6 req/s（顺序真实混合，自然短输出） |
| 峰值 decode 吞吐（等长强制 128tok，batch16） | **388 output-tok/s**（batch1=35 → batch16=388，11× 缩放） |
| 峰值显存 | 6.3–8.9 GB / 12 GB（单模型 bf16，稳进） |
| 平均输出 | ~22 token/req（血缘 JSON 短，**prefill 受限非 decode 受限**） |

### 1.2 vLLM 连续批处理（`realeval/bench_vllm.py`）
> ⏳ **本机实测待补**：vLLM（sm_120/Blackwell）安装受当时外网降速（~0.5MB/s）阻塞，脚本已就绪，
> 待带宽恢复真跑填入。机理：连续批处理消除静态批的 straggler 浪费，同类 3B 工作负载单卡吞吐
> 通常较 transformers eager 有数倍提升——本台账不提前写未实测的具体倍数（诚实边界）。

## 2. 成本对比（真实 token 画像：~1200 input / 22 output）

| 方案 | 每 1000 请求 | 口径 |
| --- | --- | --- |
| deepseek-v4 API | ≈ ¥2.6 | 挂牌 ~¥2/M in·¥8/M out（估算，输入主导） |
| qwen-max API | ≈ ¥3.1 | 挂牌 ~¥2.4/M in·¥9.6/M out（估算） |
| GPT-5.6 sol（中转站实付） | ≈ ¥9.2 | 068 后台账单反推 ¥7.5/M blended |
| 自部署·自有 5070 | **≈ ¥0.03** | 仅电费（~200W×¥0.6/kWh），沉没成本近免费 |
| 自部署·租 4090 ¥2.5/hr | ≈ ¥0.7 | 满载时；闲置则固定费摊高 |

## 3. 交叉点与结论

- **盈亏平衡（租卡 vs deepseek）**：¥2.5/hr ÷ ¥0.0026/req ≈ **0.27 req/s（~960 req/hr）**。
- **< ~960 req/hr（交互/低频）** → 云 API 更省 + 零运维（租卡闲置固定费摊高）。
- **> ~960 req/hr 且喂满 GPU** → 自部署便宜 **4×（租卡）~ 100×（自有卡）**。
- **自有 5070 已摊销** → 任何真实批量都碾压 API；naive 0.9 req/s = 3600 req/hr = **8 万+ 请求/天**容量，扫全仓血缘绰绰有余。
- **US6 双专家融合**：两趟推理 → 吞吐减半、两 merged 3B ~11.6G 在 12G 偏紧（须 4bit 或 base+双 adapter）；自有卡边际成本仍近零。
- **最优 = 混合架构**：3B 自部署跑高精度自动采纳层（治理安全），厂商分歧的 ~30% 路由云 teacher 复核 —— 正是 068 治理路由（限制②缓解）形态，成本与质量双优。

## 4. 复现
```bash
PYTHONPATH=. python3 realeval/bench_serving.py    # transformers eager（单流+批+显存）
PYTHONPATH=. python3 realeval/bench_vllm.py        # vLLM 连续批处理（需 pip install vllm）
```
