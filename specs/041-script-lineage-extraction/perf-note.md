# 041 SC-003 耗时注记

**测试环境实测（单测/集成测试内计时，2026-07-03）**：

- `EmbeddedSqlExtractor` 24 条语料全套抽取（含 Calcite 解析）：单测总耗时 ~1.2s → 单任务平均 <50ms。
- `ApiPatternExtractor` 20 条语料：单测总耗时 ~0.25s → 单任务平均 ~12ms。
- `ScriptLineagePushIT` 全量 push（pull→push 含脚本抽取）：单用例 ~1-3s（Spring 上下文占大头），脚本通道增量在 push 总耗时中 <5%。
- 编排器时间预算 2s 硬上限（超时降级留痕，`ScriptLineageServiceTest.slowExtractorTimesOutButOthersSurvive` 实测 <5s 内返回且产物保留）。
- 模型通道（可选开启）：sidecar GPU 单请求 ~1-2s，受同一 2s 预算约束，超时即弃（SC-007）；未配置 endpoint 时零开销旁路。

**结论**：`task_def.content ≤ 4000` 字符输入下，规则双通道增量为毫秒级，push 端到端增幅远低于 SC-003 的 20% 上限。**生产形态抽样（含模型通道开启态）**待隔离环境部署后补测（浏览器验证同批），届时更新本文件。
