# Contract: `realeval/ablation_table.py` — 抗泄漏消融表汇编

**新增脚本**。把 基线/B1/B2/3B 的评测产物汇成一张同口径消融表 md/json，供并入论文。

## CLI

```
python realeval/ablation_table.py \
    --b1-eval out/eval-real-b1.json --b1-leak out/leak-report-b1.json --b1-synth out/eval-report-b1.json \
    --b2-eval out/eval-real-b2.json --b2-leak out/leak-report-b2.json --b2-synth out/eval-report-b2.json \
    --report out/ablation-antileak.md
```
（`--b1-*` / `--b2-*` 均可选：只做一支时另一支省略，表中该行标"未做"。）

## 行为契约

1. **行**：基线 1.5B / B1 / B2 / 3B。基线与 3B 数字为**冻结常量**（脚本内嵌，注释标 `paper-negative-result-findings.md` §5 来源），不读运行时、不重跑。
2. **列**：真实 precision · 真实幻觉 · 真实 recall(非空) · 真实方向(非空) · 逐字泄漏(自有池) · 合成形态率 · 合成 held-out precision。
3. **诚实性约束**（硬）：
   - 每支必须**同时**有真实指标与合成 held-out 指标才入表；缺合成列 → 报错（防单边汇报，spec FR-005/SC-002）。
   - 生成的结论段模板须留"trade-off / 未解病"占位，且在检测到 recall 相对基线跌 > 20% 或合成 precision 跌 > 5pt 时**自动插入显式告警行**。
4. **产物**：`out/ablation-antileak.md`（表 + 结论骨架）、`.json`（结构化）。并入论文由后续任务人工审读后 append 到 `paper-negative-result-findings.md` §B（不自动改论文，防误写）。

## 退出码
`0` 成功；`≠0` 缺必需的合成列 / 输入 json 缺字段。
