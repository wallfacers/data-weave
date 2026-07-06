# Quickstart: 自训小模型血缘蒸馏到生产可用

端到端跑通顺序（全部在 worktree `dw-052-lineage-distillation` 的 `ml/lineage-extractor/` 下）。长命令按 [[wsl2-long-command-detach]] 用 `setsid` 脱离 + 单次秒回轮询。

## 0. 一次性：数据资产与环境

```bash
cd /home/wallfacers/project/dw-052-lineage-distillation/ml/lineage-extractor
# 从 dw-041 复制 gitignore 数据资产（.env / 已有金标 / 语料池）——不入 git
cp ../../../dw-041-script-lineage/ml/lineage-extractor/.env .
cp -r ../../../dw-041-script-lineage/ml/lineage-extractor/realeval/gold realeval/
cp -r ../../../dw-041-script-lineage/ml/lineage-extractor/realeval/pool realeval/   # 175 候选起步
pip install -r requirements.txt --break-system-packages   # PEP668；含 sqlglot/peft/bitsandbytes
```

## 1. 扩采真实语料（US1 / FR-001）

```bash
# GITHUB_TOKEN 已在 .env；wide+jvm+多引擎，目标候选 6k–12k
python -m realeval.collect --profile wide --profile jvm --target 8000 --out realeval/pool
```

## 2. 双 teacher 打标（FR-003，缓存+续跑）

```bash
setsid bash -c 'python -m realeval.teacher_label --pool realeval/pool \
  --teachers m1,m2 --cache realeval/teacher_labels --resume \
  >tl.log 2>&1; echo $? >tl.exit' </dev/null >/dev/null 2>&1 & disown
# 轮询：[ -f tl.exit ] && echo DONE=$(cat tl.exit) || tail -1 tl.log
```

## 3. 构建银标（FR-002/004/005，交集为主+分歧救回+去污染+配比）

```bash
python -m realeval.build_silver --labels realeval/teacher_labels \
  --pool realeval/pool --exclude-gold realeval/gold \
  --empty-ratio 0.2 --out data/silver.jsonl
pytest tests/test_build_silver.py -q     # 字面门/零合成名/污染/配比 不变量
```

## 4. 蒸馏训练（FR-008/009，从干净 base，3B 主 / 1.5B 对照）

```bash
# 3B 达标主体（~61min，bf16 峰值 11.9G）
setsid bash -c 'python train/sft_qlora.py --base-model Qwen/Qwen2.5-Coder-3B-Instruct \
  --data data/silver.jsonl --out ../../../weft-lineage-weights/run-distill-3b \
  >tr3b.log 2>&1; echo $? >tr3b.exit' </dev/null >/dev/null 2>&1 & disown
# 1.5B 对照同理换 --base-model .../1.5B-Instruct --out .../run-distill-1.5b
```

## 5. 构建测试集 B（FR-007/SC-009，非空≥100）

```bash
# 新采 teacher 未训练的脚本 → prelabel → agent 逐条证伪初裁 → 维护者抽查终审
python -m realeval.collect --profile wide --target 3000 --out realeval/pool-b --exclude realeval/pool
python -m realeval.prelabel --pool realeval/pool-b --out realeval/tolabel-b.jsonl
# （证伪裁决产出 realeval/gold/real-b.jsonl，非空≥100）
```

## 6. sidecar + dir_fix 服务（US2 / FR-011~014）

```bash
MODEL_DIR=../../../weft-lineage-weights/run-distill-3b/merged \
  python -m uvicorn serve.app:app --host 0.0.0.0 --port 8500
pytest tests/test_dir_fix_serve.py -q     # dir_fix 策略/健壮性/弃权/确定性
# 后端：application.yml 的 lineage.model.endpoint 指向 8500（既有配置）
```

## 7. 验收评测（US3 / eval-gate 契约）

```bash
# 四方（系统数=模型+dir_fix，模型独跑数）
python -m realeval.eval_real --gold realeval/gold/real-b.jsonl \
  --model ../../../weft-lineage-weights/run-distill-3b/merged --dir-fix --out out/eval-distill-b.md
# 逐字泄漏（自有池口径）
python -m realeval.leak_analysis --gold realeval/gold/real-b.jsonl \
  --train-pool data/silver.jsonl --out out/leak-distill.md
# 污染审计：content-hash train∩test=0
```

## 8. 判定与加性发布（FR-016/019/020）

- 读 `out/eval-distill-b.md` 的 `gate_pass`：
  - **true** → 改写 HF 卡为生产卡 + swap 后端 `MODEL_DIR`（`publish.py`）。
  - **false** → 走升级路径（难例挖掘 / 7B QLoRA / dir_fix 调参），重训重评。
- 全程现有已发布产物不动到证明通过为止。

## 验证清单（对应 SC）

- [ ] silver 不变量单测绿（字面门/零合成名/污染/配比）
- [ ] 测试集 B 非空 ≥100（SC-009）
- [ ] eval-distill-b：recall≥.80 ∧ 方向≥.73 ∧ 幻觉≤.15 ∧ precision≥.50（SC-001~004 严格全过）
- [ ] verbatim_leak ≈ 0（SC-005）
- [ ] train∩test content-hash 重叠 = 0（SC-006）
- [ ] 服务路径 teacher 调用数 = 0（SC-007）
- [ ] 单脚本 ≤ 预算返回（SC-008）
