# 063 分级置信度校准（gold C 嵌套 CV 去偏）

research R1：无独立非泄漏带标集（测试集 A 已删 / pool-c-held⊇gold C / pool-c-train 泄漏）
→ gold C 嵌套 CV 去偏。**部署常量=全 gold C 点估计；报告精度=留一/k 折 held-out（无偏）**。

- 非空金标脚本 61，金标真表 148（canon）。治理阈 thr=0.95。

## 逐级经验 precision（全 gold C 点估计 = 部署 confidence 常量）

| 置信级 | 候选边数 | 点估计 precision |
| --- | --- | --- |
| 一致（SQL∩模型） | 23 | 0.870 |
| SQL-only·限定名 | 7 | 1.000 |
| SQL-only·裸名 | 11 | 0.182 |
| 模型-only·限定名 | 54 | 0.815 |
| 模型-only·裸名 | 44 | 0.909 |

**部署校准序**：`SQL-only·限定名 > 模型-only·裸名 > 一致（SQL∩模型） > 模型-only·限定名 > SQL-only·裸名`

## CV held-out 前沿（诚实口径，SC-002）

| 口径 | pooled precision | pooled recall | 采纳级集(众数) |
| --- | --- | --- | --- |
| k=5 折 | **0.786** | 0.149 | SQL-only·限定名 + 模型-only·裸名 |
| 留一(LOO) | 0.714 | 0.068 | SQL-only·限定名 |

- 折间 precision 抖动 k=5：[0.750, 1.000]（样本小，如实披露）。

## fit_thr 扫描（消除样本内乐观后的部署 precision↔recall 曲线）

| fit_thr | held-out precision | held-out recall | 采纳级集(众数) |
| --- | --- | --- | --- |
| 0.99 | 1.000 | 0.047 | SQL-only·限定名 |
| 0.97 | 1.000 | 0.047 | SQL-only·限定名 |
| 0.95 | 0.786 | 0.149 | SQL-only·限定名 + 模型-only·裸名 |
| 0.90 | 0.812 | 0.351 | SQL-only·限定名 + 模型-only·裸名 + 一致（SQL∩模型） |
| 0.85 | 0.870 | 0.723 | SQL-only·限定名 + 模型-only·裸名 + 一致（SQL∩模型） + 模型-only·限定名 |

> 诚实边界：CV 只校同分布（gold C 内）乐观偏置，**不覆盖分布漂移**；真·独立 fresh 集仍待凭据/预算。
