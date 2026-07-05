# 论文必须显著披露项（dbt 形态重训的诚信记录）

> 来源：v2 过闸后对「教 dbt + 改 heldout」改动的对抗式诚信审查（2026-07-04）。
> 结论：诚信上可辩护、可发表，但**须在论文显著披露以下三点**，否则「run2 失败后只改失败那个形态的评测集」这一时序会被审稿人正当地指为 cherry-picking（即使实质无辜）。

## 背景（事实链）

- v2 第一次重训（run2，仅补 30 模板、dbt 留作未见形态）：方向准确率 **0.9264 未过 0.95 闸**。唯一拖累 `config`(dbt) 形态方向 **0.20**。
- 根因：`dbt run --select X` 的 `--select` **无方向词法线索**（字面像"读"），而 dbt 从未进训练分布 → 任何抽取器都无法零样本推断「--select 命名的是写出的模型」。
- 处置（用户决策：教 dbt + 重训一次）：① 训练集加 `t_sh_dbt_run`（教 --select/-s/--models=写、--vars src/source=读，带表面变体）；② 修 heldout `h_sh_dbt_run` 的零噪声缺陷（见下）。
- run3：方向 **0.9954 过闸**，`config`(dbt) 0.20→**1.0**，`wrapper`（v1 弱点）保持 **1.0**。

## 必须披露的三点

### ① 反事实：标签修正单独救回 0 例，过闸增益全部来自训练教会约定
run2 全部 dbt 失败行（见 `out/eval-report-v2-dbt-unseen.json`）**无一例外**是 `W … got=[]`（模型从没把 model 名判成写），或把 `--select X` 误判成读（`R got=[X]`）。**没有任何一条是 `W gold=['dw.foo'] got=['foo']`（schema 不匹配型）**；连 schema-全名的失败样例（如 `dw.dw_refunds_delta`）也都是 `got=[]`。
→ 单独做「写标签 schema 全名→裸名」的修正能救回的失败 = **0 条**。过闸完全由「训练教会 --select=写」驱动。改 heldout 与过闸机制**正交**。

### ② dbt 的 claim 降级 + 澄清 `rule_covered` 语义
- dbt 已从「未见**形态**的零样本泛化」**降级**为「已教约定上的未见**名字**泛化」（训练/heldout 同表面约定、不相交命名池）。论文不得把 dbt 算作零样本形态泛化证据。
- 未见**形态**的零样本泛化证据改由 `decorator`(@pipeline reads=/writes=) 与 `orm-chain`(copy_into) 承担——二者未进训练、run3 仍 1.0/0.9615。
- `meta.rule_covered=False` 的真实含义 = **Java 正则通道不覆盖**（一条部署轴），**不等于**「heldout 零样本形态」。指标表「规则未覆盖子集」里应把 dbt 单列，避免读者误读。

### ③ 训练/heldout 命名池不相交（已实测）
`build_pools`（`data/synth_pipeline.py:88-96`）先按全名把表池切成 train/heldout 两段。实测：train `sh-dbt-run`(322 条) vs heldout `h-sh-dbt-run`(45 条)，**写模型名交集=∅、读源名交集=∅、连裸叶名层面也不相交**。

## heldout 标签修正的正当性（为何不是把合格标签改宽松）

改前 `h_sh_dbt_run` 可执行行是 `dbt run --select {leaf}`（只含裸叶名），写标签却是 `schema.leaf` 全名——**要求模型从只含 `foo` 的文本凭空补出 `dw.` 前缀**。这违反：
1. 本模块自述的零噪声铁律（`templates.py` 头注：标签 token 必须字面可从脚本文本导出）；
2. dbt 真实语义（`--select` 命名的就是裸 model 名，schema 由 `profiles.yml`/`dbt_project.yml` 解析，不出现在命令里）。
改后写=裸名、与可执行行 `--select {model}` 字面一致，读=全名且字面出现在 `--vars '{"src":...}'`——两侧均满足零噪声。这是**把本就写错的标签改对**，独立于开发者说辞成立。

## 建议论文写法（1–2 句）

> dbt `--select` 的血缘方向无词法线索，任何抽取器都无法零样本推断，我们据此将该约定纳入训练分布，并在 held-out 上以**不相交命名池**评测（名字泛化，非形态零样本）。同一批修正中，我们将 dbt 写标签由 schema 全名改为裸 model 名以符合 dbt 语义与零噪声标注准则；**消融显示单独此标签修正救回 0 例失败，过闸增益全部来自训练约定**，故该标签修正是移除一处标注缺陷，而非过闸机制。

## 关键文件
- `data/templates.py`：`_dbt_model_name` / `t_sh_dbt_run` / `h_sh_dbt_run`
- `data/synth_pipeline.py:88-96`：训练/heldout 池切分
- `out/eval-report-v2-dbt-unseen.json`：run2 失败全为 `W got=[]`（反事实证据）
- `out/eval-report-v2.md`：run3 `config` 家族 1.0 过闸
