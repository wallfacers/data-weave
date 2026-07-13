# Phase 0 Research: 三厂商共识 gold + 全档重训

所有关键未知点已在 brainstorming + 冒烟测 + 067 资产盘点中探明并裁决。

## R1：GPT-5.6 经中转站的调用方式

- **Decision**：不用 OpenAI Python SDK，用 **httpx 裸 POST** 到 `${GPT_BASE_URL}/chat/completions`（Bearer 鉴权）。
- **Rationale**：冒烟测实证——OpenAI SDK 默认注入的 `x-stainless-*` 遥测头触发中转站 WAF，返回 `PermissionDeniedError: Your request was blocked`（连 `models.list` 与最小 "Say hi" 都被拦）；curl / httpx 无这些头 → HTTP 200 正常返回，含 `usage`。GPT-5.6-sol 对 SQL/PYTHON 均吐合法血缘 JSON、含列、并正确忽略被 `print()` 的假表（具备 teacher 判别力）。
- **Alternatives rejected**：① 给 SDK 传 `default_headers` 覆盖 UA——不确定能否剥掉全部 stainless 头，脆弱；② 用 gpt-5.6-terra/luna 做 gold——质量档不如 sol，gold 裁判要最强推理。
- **配置**（`.env`，gitignored）：`GPT_API_KEY` / `GPT_BASE_URL`（含 `/v1`）/ `GPT_MODEL=gpt-5.6-sol`。silver bulk 便宜档单独用 `gpt-5.6-luna`。

## R2：共识语义——2-of-3 vs 3-of-3 各服务什么

- **Decision**：**Silver（训练标签）= 2-of-3 多数**；**Gold（评测尺子）出两把**——2-of-3 多数（主尺/G1 破循环）+ 3-of-3 一致高置信子集（G2 涨点判据 + 限制②治理自动层）。
- **Rationale**：2-of-3 比 067 的 2-of-2 交集**多边**（GPT+某一家抓到、原被漏的真边）→ 召回升；仍要求 ≥2 独立厂商背书 → 精度守。这是同时抬 P 和 R 的唯一共识设计（直接服务用户"高准确率高召回"）。3-of-3 是最不模糊的高置信集，既作严格涨点判据，又天然是治理"自动采纳/人工复核"分界（分歧=模糊=人工）。
- **Alternatives rejected**：① 三方全用 3-of-3 交集——更严=召回更差，背离目标；② silver 用 3-of-3——训练信号太稀疏。

## R3：列级共识裁决（延续 067）

- **Decision**：仅在**表级一致**的表上裁列；列 gold = 多数/交集（三家在该表都给列集则取多数一致列，交集空或一方弃权→`null`）；`canon_col` 剥表限定前缀；通配 `*`/空集 `[]`→弃权（三态弃权优先）。
- **Rationale**：067 已验证的列级一致裁决语义，门①正交（列打分独立 `col_*` key，表级 8 key 逐字节不变）已由 `test_metrics_columns.py` 钉死；三厂商只是把裁决方从 2 家扩到 3 家，语义不变。
- **Alternatives rejected**：改列打分逻辑——违反门①正交，禁。

## R4：训练初始化——fresh vs warm-start（用户已拍板）

- **Decision**：**fresh** 从原始 `Qwen/Qwen2.5-Coder-{0.5,1.5,3}B-Instruct` base 训 LoRA，mit 配方（r32/alpha64/epochs3），只换 silver 为 2-of-3 共识。
- **Rationale**：067 run-col 也是在**原始 Qwen base**（非 059 模型）上训 LoRA（`adapter_config.base_model_name_or_path=Qwen/Qwen2.5-Coder-3B-Instruct` 实证）。068 用同 base/同配方、只换 silver → 三厂商共识效果被**干净隔离**（延续 065/067 招牌隔离消融的因果论证方法学）。warm-start 会混淆"列监督(2-of-2)+三厂商 silver(2-of-3)"两变量，削弱破循环论证，且只能续 3B（05/15 无对应起点）。
- **Alternatives rejected**：warm-start from run-col-3b-mit（用户否决，方法学不干净）。

## R5：复用 067 资产省成本

- **Decision**：复用 067 迁入 dw-068 的 `pool-c`/`pool-silver`（同池不重采）+ `teacher_labels-c/{m1,m3}` + `teacher_labels-silver/{m1,m_flash}`（qwen/deepseek 标注不重调）；068 只**新增 GPT 标注**（gold 用 sol、silver 用 luna）。
- **Rationale**：067 gold/silver 池已定；teacher 标注幂等可复用 → 只花 GPT 新增标注钱（gold ~¥25 + silver ~¥26），qwen/deepseek 零重标。保证与 067 逐条可比（FR-002/005）。
- **成本核算**（SC-009）：从各 teacher 标注 jsonl 的真实 `usage` 字段算，非估算。

## R6：限制② 治理路由（本次新增缓解，用户确认）

- **Decision**：加 `governance_routing.py`——把"三厂商一致(3-of-3)"定义为自动采纳层、"厂商分歧"为人工复核层；报模型在自动层精度（自动化安全性）+ 分歧案例占比（人工工作量）；接 063 分层信封语义。
- **Rationale**：三厂商是否一致本身是"模糊度"客观信号（免费得自 068 已算的三厂商标注）。缓解已知限制②"模糊案例缺人工复核"——缩小模糊区 + 给出有原则的复核路由，非消灭人工复核（诚实边界）。
- **限制①（动态名/注释/临时视图）**：刻意设计边界非缺陷，静态抽取无从解析 → **明确范围外 + Future work**（数据流解析另立特性）。

## R7：环境/执行约束

- **Decision**：`python3`（系统解释器，openai/httpx/dotenv/torch 全在）；WSL2 长命令（teacher 批量标注 / 训练）必 `setsid` 脱离 + 单次秒回轮询；Bash 工具长等待须设 `timeout` 参数。
- **Rationale**：067 真跑经验——`python` 不存在只有 `python3`；`collect_stack` 终结阶段 GIL race（数据已落盘）；3B r32/e3 ~2hr/12G 未 OOM。本特性不重采语料（复用池），无 collect_stack 风险。
