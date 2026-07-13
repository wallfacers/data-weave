# 068 一致率：GPT-5.6 vs 067 gold（qwen∩deepseek）

参照=067 gold（realeval/gold/real-c.jsonl），pred=GPT-5.6 标注。**recall=一致率**（GPT 独立确认的既有 gold 边占比）。

参与行 n=382（GPT 弃权/缺失的行不计入分母）。

| 粒度 | 确认(tp) | GPT多出(fp) | GPT漏(fn) | precision | **一致率(recall)** |
|---|---|---|---|---|---|
| 表级 | 328 | 255 | 8 | 0.563 | **0.976** |
| 列级 | 407 | 32 | 18 | 0.927 | **0.958** |

**读法**：一致率高=既有 067 gold 非 qwen∩deepseek 单家族臆造，第三厂商 GPT-5.6 也认可 → gold 厂商稳健。
