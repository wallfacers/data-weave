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

## 全量 heldout(600 条)评测 — 2026-07-17 续跑

`python -m eval.evaluate --model out/run-14b/merged --data data/out/heldout.jsonl`(041 门槛闸口径,形态隔离,N=600)。报告拉回本地 `out/eval-14b-heldout600.{md,json}`。

| 粒度 | P | R | F1 | 方向 | 幻觉 | 非法 |
|---|---|---|---|---|---|---|
| **表级** | 0.9954 | 0.9963 | **0.9958** | 0.9954 | 0.0000 | 0 |
| **列级** | 1.0000 | 1.0000 | **1.0000** | — | 0.0000 | — |

- 列级在 **482 个命中表**上评列,P=R=F1=1.000;规则未覆盖子集(rule_covered=False)表 R 0.9943 / 列 F1 1.000。
- 全 9 个形态 chain/cli/config/decorator/orm/py/sh/wrapper 均满分,唯 `h`(Spark 别名难例)表 F1 0.9889;5 条失败样例全是 spark-table-alias 的读写方向对调。
- **意义**:068 判定「3B/LoRA 表血缘与列血缘是竞争目标、严格双门(表 R≥0.75 且 列 F1≥0.85)不可兼得、7B 为出路」(best 3B tri-lw3 = 表 F1 0.781 / 列 F1 0.825)。14B **单模型同时打穿两门**,直接验证放大即出路。
- **诚实边界**:heldout600 是 **synth 合成集**(source=synth),比 068 frontier 所用的真实 GitHub gold C 简单,0.995/1.000 含测试集难度红利;要与 068 的 3B frontier 严格同集对比,需把 14B 也在 tri gold(真实)上跑一遍 —— 列为可选后续,不阻塞放大结论。

## 7B 规模点(2026-07-17 同一云机续训)

base `Qwen/Qwen2.5-Coder-7B-Instruct`,**同 14B 干净配方**(plain LoRA r16/α32,epochs 2,max-len 2048,1 万样本 1250 步)。~1.8s/step ≈ 38min,显存 25G/84G,loss 2.25(s20)→0.61(s40)→0.18 收敛,与 14B 同轨无发散。merged 15G 留云盘 `out/run-7b/merged/`,adapter 已拉回本地(gitignored)。

合成 heldout(N=600)评测,与 14B 并列:

| 规模 | 表 P | 表 R | 表 F1 | 方向 | 幻觉 | 列 F1(评列表数) |
|---|---|---|---|---|---|---|
| **7B** | 0.9991 | 0.9991 | **0.9991** | 0.9991 | 0.0009 | **1.0000**(481) |
| **14B** | 0.9954 | 0.9963 | 0.9958 | 0.9954 | 0.0000 | 1.0000(482) |

- **关键发现:合成 heldout 对 7B/14B 已双双触顶**(表 ~0.99+,列 1.000),7B 甚至微超 14B —— 差异在噪声内,**合成集对规模无区分力**。这正是负结果叙事的又一实证:合成饱和分不能用来判定「更大模型更好」。要分出 7B vs 14B 高下(尤其 068 关切的真实表列两全),**只能靠真实 tri gold**(见下 blocker)。

## ★ 真实 tri gold 评测(2026-07-17 晚,blocker 解除)

gold 意外在本地找到:068 worktree(`dw-068-tri-vendor-gold`,未 remove)的 `realeval/gold/` 完整保有 `real-c-tri.jsonl`(399 条,非空 129)——无需等家里机器。已复制回 main 的 `realeval/gold/`(gitignored)。云机被更换(host/port 变),paramiko 重推公钥后数据盘完好(merged 权重都在),`realeval/eval_model_c.py`(main 与 068 worktree 逐字节一致)在云机跑通两个规模:

| 规模(synth-only 训练) | 表 P(非空) | 表 R | 表 F1 | 方向 | 幻觉 | 列 P | 列 R | 列 F1 |
|---|---|---|---|---|---|---|---|---|
| **14B** | 0.8382 | 0.3615 | **0.5052** | 0.3298 | 0.0196 | 0.9657 | 0.5788 | **0.7238** |
| **7B** | 0.7644 | 0.3362 | 0.4670 | 0.3171 | 0.0769 | 0.9871 | 0.4703 | 0.6371 |
| 3B tri-lw3(真实银标训练,068) | — | — | **0.781** | — | — | — | — | **0.825** |
| 3B 融合B(068 US6) | — | 0.801 | — | 0.780 | — | — | — | 0.828 |

三个结论(全部强化负结果叙事):

1. **规模不能修复 domain shift**:synth-only 14B 真实表 F1 0.505,被真实银标训练的 3B(0.781)碾压;合成 heldout 0.9958 → 真实 0.505 即卡片头条 *the misleading number* 的 14B 版本。严格双门(表 R≥0.75 且 列 F1≥0.85)synth-only 14B 在真实集上**两门全挂**(表 R 仅 0.36)——「14B 单模型打穿两门」只在合成集成立,真实集上被证伪。
2. **真实集恢复规模区分力**:合成 heldout 上 7B≈14B(双双触顶),真实集上 14B 全面优于 7B(表 F1 0.505>0.467,列 F1 0.724>0.637,幻觉 0.020<0.077)——印证「合成饱和分无区分力,规模判定只能靠真实集」。
3. **崩塌轴是召回,不是精度**:14B 真实表 P 0.838、列 P 0.966、列幻觉 0——synth 训练教会了「不瞎编」,但真实脚本形态见不着导致漏抽(R 0.36)。方向 0.33 同崩(synth 模板方向线索与真实脚本不同构)。

报告:`out/eval-{14b,7b}-real-tri.{md,json}`(已拉回本地)。**下一步若要真两全**:用 068 tri 真实银标语料在云机重训 7B/14B(数据盘已有基座+配方,`train.jsonl` 换成 tri 语料即可)。

## ★★ tri 真实银标重训 7B/14B(2026-07-17/18 夜,用户拍板「继续训练」)

语料=068 `train-tri.jsonl`(1154 条,md5 核对上云),068 同配方(r32/α64/epochs3/max-len2048,plain 全列)。7B 训 32min(loss 0.596/acc 0.883)、14B 训 65min(loss 0.552/acc 0.891)。真实 tri gold(非空 129)结果:

| 模型 | 表 P | 表 R | 表 F1 | 列 P | 列 R | 列 F1 | 方向 | 表幻觉 | 双门 |
|---|---|---|---|---|---|---|---|---|---|
| 3B 列专家(068 对照) | 0.822 | 0.645 | 0.723 | — | — | 0.931 | — | — | ✗/✓ |
| 3B lw3(068 最佳均衡) | — | — | 0.781 | — | — | 0.825 | — | — | ✗/✗ |
| 7B tri plain | 0.813 | 0.624 | 0.706 | 0.934 | 0.914 | 0.924 | 0.620 | 0.033 | ✗/✓ |
| **14B tri plain** | 0.880 | 0.679 | **0.766** | 0.923 | 0.919 | **0.921** | 0.677 | 0.014 | ✗(R差0.07)/✓ |

三个发现:

1. **换对数据立竿见影**:同一个 14B,synth 训表 F1 0.505 → tri 训 0.766(+0.26);列 0.724 → 0.921。data > scale 再次实证(tri 只有 1154 条,是 synth 1 万条的 1/9)。
2. **14B plain 支配 3B 全部单模型点**:表 F1 追平 lw3(0.766 vs 0.781,噪声内)同时列 F1 高 +0.10(0.921 vs 0.825);对 3B 列专家则表 +0.043 列持平。frontier 随规模外推成立——但**没有消失**:表 R 0.679 仍差双门 0.07,plain 全列画像在 14B 上仍是「列强表弱」(列监督负载压制表召回的机制在 14B 依旧存在)。
3. **7B ≈ 3B 列专家**(表 0.706 vs 0.723/列 0.924 vs 0.931,均微差)——规模红利在 7B 这一档几乎不可见,14B 才显现。

**接续实验(跑批中)**:14B-lw3 + 7B-lw3(`--table-loss-weight 3`,068 均衡杠杆上大模型)——14B 列 F1 富余 0.07(0.921 vs 门 0.85),lw3 用列换表若复现 3B 幅度(表 F1 +0.058),有望首次**单模型同过严格双门**。报告将落 `out/eval-tri-{14b,7b}-lw3-real.{md,json}`。

## ⚠️ 真实集评测 blocker + HF 推送闸(2026-07-17)——已解除,留档

- **合成 0.995/1.000 不可单独发布**:publish.py/MODEL_CARD 的既定叙事=负结果研究,头条「合成 held-out 0.995(*the misleading number*) vs 真实 GitHub 0.27 崩塌」。14B 只有合成数字 → 诚实卡片写不出 → **14B 暂不推 HF,推送闸卡在真实数字上**。
- **真实 tri gold 三处皆无**:`realeval/gold/real-c-tri.jsonl`(带列,nonempty~129)+ `real-c.jsonl`(表级,153)均 **gitignored**;本地工作树已删、云端 GPU 未传、HF 数据集 repo(`weft-script-lineage-synth`)确认只有合成 train/heldout + 报告(历史发布未带 `--include-real-gold`)。**gold 仅存于用户家里另一台机器**,当前不可达。
- **决定**:真实集评测(14B + 7B)**延后**至家里机器可连;届时拉回 gold → `python -m realeval.eval_model_c --model <merged> --gold realeval/gold/real-c-tri.jsonl` 出真实表/列数字 → 才写卡片推 HF。
- **14B merged 29.5GB**:暂**留云盘** `/root/autodl-tmp/lineage/out/run-14b/merged/`(数据盘持久,06:00 定时关机不丢);adapter 275MB 已在本地。不下载不推送,等真实数字。

## 待办

1. ~~全量合成 heldout 评测~~ ✅ 已完成(上表)。
2. ~~14B/7B 真实 tri gold 复评~~ ✅ 已完成(见「★ 真实 tri gold 评测」);HF 推送闸的两数已齐(合成 0.9958/真实 0.505),推送本身待用户确认。
3. **7B 训练**(2026-07-17 起):补 3B→7B→14B 逐规模曲线中间点,定位「表列两全」最小规模。base 已下 `/root/autodl-tmp/models/Qwen2.5-Coder-7B-Instruct`,同配方(plain r16/α32,epochs 2,max-len 2048);真实评测同样延后。✅ 训练+合成评测已完成(见上「7B 规模点」)。

## 回家续跑 checklist(给接手的 AI)

**前提**:真实 gold 只在家里那台机器;本次云机产物 merged 留云盘、adapter 已在本地 repo(gitignored)。

1. **拿真实 gold**:家里机器上 `ml/lineage-extractor/realeval/gold/` 应有 `real-c-tri.jsonl`(带列,nonempty~129)+ `real-c.jsonl`(表级,153)。gitignored,不在本 commit 里,须从家里机器取。
2. **真实评测 14B / 7B**(需 merged 权重):
   - merged 在**云盘** `/root/autodl-tmp/lineage/out/run-{7b,14b}/merged/`(AutoDL 关机保留数据盘;重开后 SSH host/port 可能变,免密公钥在系统盘会被重置需 paramiko 密码重推一次)。
   - 或用本地 adapter + base 现合:`out/run-{7b,14b}/adapter/` + Qwen2.5-Coder-{7B,14B}-Instruct → `merge_and_unload`。
   - 命令:`python -m realeval.eval_model_c --model <merged> --gold realeval/gold/real-c-tri.jsonl --report out/eval-{7b,14b}-real-tri.md`(带列 gold 会同时出表/列;口径见脚本 059 头注)。
   - 严格对照 068 的 3B frontier(表 F1 0.781 / 列 F1 0.825,tri-lw3):看 7B/14B 是否**单模型同过两门**(表 R≥0.75 且 列 F1≥0.85)。
3. **HF 推送闸**(务必先有真实数字再推):
   - token 已验证可写(user=wallfacers)。规范见 `publish.py`:模型 repo=负结果 artifact,卡片**必须并列合成 vs 真实两数**,否则违背项目诚实叙事。
   - 新规模点用 `wallfacers/weft-lineage-extractor-{7b,14b}`;model card 照 `publish.py` 的 `_MODEL_CARD` 风格重写(base_model、真实/合成对照表、诚实定位)。
   - **合成 0.99+ 单独不可发**——它就是卡片里 *the misleading number*。
