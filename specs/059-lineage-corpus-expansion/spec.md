# 059 血缘语料扩增 + 推理蒸馏（纯训练手段追平 deepseek-v4-pro 单跑）

**日期**：2026-07-09 · **worktree**：`dw-059-lineage-corpus-expansion` · **分支**：`059-lineage-corpus-expansion`（基线 main `1e0b946`）

## 1. 终极目标（north star）

用**纯训练手段**，让自托管小模型（distill-3b 及后续）的血缘抽取精度把 **deepseek-v4-pro 这类企业级大模型追平**——落地不必按次付费调用大模型（"企业级大模型是有钱人才能用的"）。

- 当前小模型单体：precision **0.36** / recall 0.66 / 方向 0.60（gold A，FINDINGS-052）。
- teacher 单跑（deepseek-v4-pro / qwen 级）：约 **0.55–0.60**。
- 差距 = 本轮要靠训练补的量。

**诚实边界**（早先已确认）：追平 deepseek-v4-pro **这个模型** 现实可达；追平"完美 oracle"不可能——"什么算血缘"是定义性天花板，连 pro 也只 ~0.56。**系统级**（模型∪AST+分级校准前沿）已 0.93、早超 teacher；本轮专攻更难的 **模型单体** 这条线。

## 2. 本轮范围（两拳里的一拳半 + 备料）

一次采集/打标同时产出三份资产，跑通两拳训练闭环：

1. **扩语料**（punch #2，FINDINGS-052 标的 #1 瓶颈）：161 银标 → **~1500 非空**（连空脚本总采 ~2900），跨厂商一致。
2. **推理蒸馏备料 + 训练**（punch #1，最大杠杆）：pro 在干净子集上拒绝采样采思维链 → 推理语料 → SFT 教小模型"先思考再答"。
3. **还清评测债**：切 **gold C**（hash 互斥、可复现），修掉"旧 gold A/B/161 银标不可复原、无法精确排污染"的历史欠账。

**不含**：AST-验证器 RL（留下一 round 叠加）。

## 3. 性价比方案（分两段，pro 只打精算子集，总 ≈ ¥63/$9）

两拳的最优 teacher 不同——bulk 靠**厂商独立性**当 precision 代理（便宜模型够用）；推理蒸馏才需**最强推理老师**（pro，但只在干净子集跑）。

### 第一段 · bulk 一致银标（便宜投票）
`deepseek-v4-flash + qwen-max` 两家跨厂商，对 ~2900 条取**交集**（复用 `build_silver` 口径，flash 顶替 pro 当第二票）。
- flash：输入 6.4M×¥1 + 输出 0.58M×¥2 ≈ **¥8**
- qwen-max（DashScope 价待校准批实测）≈ **¥20–35**

### 第二段 · 推理轨迹（pro 只跑干净子集）
`deepseek-v4-pro` 只对第一段一致通过的 **~1500 非空银标**跑，采思维链 + **拒绝采样**（pro 自答与银标一致才留）→"已验证的推理语料"。
- pro：输入 3.3M×¥3 + 输出 3.4M×¥6 ≈ **¥30**

### 为什么不用两个 deepseek（flash+pro，更便宜）
同厂商错误相关 → 一致性不再是 precision 代理。跨厂商独立正是 test-B 加 deepseek 的初衷，bulk 至少留一家 qwen。

### 预算门（硬约束）
先跑 **30 条 × 3 方 ≈ ¥3–5** 校准批，从三家 SDK 响应抓**真实 usage token** → 把区间收成确定数字 → **摆预算给用户、点头才放全量**。对齐用户"处理脚本要给预算"的常驻要求。

## 4. 污染隔离（gold C）

痛点：distill-3b 旧 161 银标 + gold A/B 都不在磁盘、不可复原 → 无法按 content-hash 精确排污染。本轮重开语料是还债机会。

- 采集时切一块 held-out 作 **gold C**（复用 `build_gold_b` 三方一致 auto-gold）。
- gold C 与训练银标做 **content-hash 互斥**（`build_silver` 已有 `--exclude-gold` 护栏，把 gold C 加入）。
- 以后评测新模型有可复现、能精确排污染的基准，不再依赖不可复原的旧 gold。

## 5. 复用面（已存在，最小改动）

| 环节 | 复用脚本 | 改动 |
| --- | --- | --- |
| 采集 | `realeval/collect.py --profile wide --limit N` | 无（放大 limit） |
| 打标 | `realeval/teacher_label.py --teachers ... --resume` | 无（content-hash 缓存 + 续跑现成） |
| bulk 银标 | `realeval/build_silver.py`（m1∩m2 交集） | 小改：可传两 teacher 名（flash∩qwen-max） |
| 训练格式 | `data/build_train_distill.py` | 无 |
| gold C | `realeval/build_gold_b.py`（三方一致） | 无（新 held-out 切片） |
| teacher 后端 | `llm/clients.py` | 加 `m_flash`；抓 `response.usage`；pro 保留 thinking 块 |
| 推理语料 | **新** `realeval/build_reasoning_corpus.py` | pro 思维链 + 拒绝采样 → `{content→reasoning+answer}` |
| 训练 | `sft_qlora.py` | 加 reasoning 目标格式支持 |

## 6. 成功判据（可测、诚实）

- 在**干净 held-out gold C** 上，**模型单体 precision 从 0.36 明显抬向 ~0.55–0.60（teacher 单跑水平）**，recall/方向不塌。
- 分离验证两拳贡献：仅扩语料 SFT vs 扩语料+推理蒸馏，各自在 gold C 的增量。
- 诚实披露：gold C 为多 teacher 一致性 auto-gold（无人工裁决，偏向三家都找到的表，recall 口径乐观）；训练污染靠 content-hash 精确排除（本轮起可复现）。

## 7. 零破坏

- 全部改动落 `ml/lineage-extractor/`；不碰后端/前端/已发布 HF 模型。
- 新脚本 + 现有脚本加参数（默认行为不变）；真实脚本/teacher 标注/推理语料/模型预测全 gitignored 走 HF。
- 每个纯函数配单测；`ml` 全套须绿。
