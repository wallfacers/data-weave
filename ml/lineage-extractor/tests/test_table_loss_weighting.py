"""068 loss 加权单测（TDD 先行）：对答案里的「表结构 token」加权,让 3B/LoRA 表列两全。

诊断:tri 全列 高熵内容 token 列名:表名=4.39:1(实测),列名淹没表名梯度。
方案:答案内 "columns":[...] 数组值 token 权重=1.0,其余答案 token(表名+骨架)权重=W。
配方隔离硬约束:W=1.0 必须 bit 级复现现有 SFT 整序列分词(可复现门);门① 评测侧不受影响。
"""
import pytest

pytest.importorskip("trl")  # 训练重依赖缺失则跳过
torch = pytest.importorskip("torch")

from train.sft_qlora import (
    answer_col_spans,
    build_weighted_row,
    weighted_lm_loss,
    to_messages,
    _answer_json,
)


# ---- 轻量本地 tokenizer（离线；缺则跳过整文件） ----
@pytest.fixture(scope="module")
def tok():
    from transformers import AutoTokenizer
    try:
        return AutoTokenizer.from_pretrained("Qwen/Qwen2.5-Coder-0.5B-Instruct")
    except Exception as e:  # noqa: BLE001
        pytest.skip(f"Qwen tokenizer 不可用(离线): {e}")


def _row(reads, writes=()):
    """reads/writes 项 = (table, columns) 元组;columns=None 表示弃权。"""
    def mk(items):
        return [{"table": t, "columns": (list(c) if c is not None else None)} for t, c in items]
    return {"task_type": "SQL", "content": "SELECT * FROM db.src",
            "labels": {"reads": mk(reads), "writes": mk(writes)}}


# ===== 1. 列数组字符区间识别 =====
def test_col_spans_captures_only_array_values():
    ans = '{"reads": [{"table": "db.a", "columns": ["x", "y"]}], "writes": []}'
    spans = answer_col_spans(ans)
    assert len(spans) == 1
    s, e = spans[0]
    assert ans[s:e] == '["x", "y"]'          # 恰是数组含中括号,不含 "columns": 键


def test_col_spans_skips_null_columns():
    ans = '{"reads": [{"table": "db.a", "columns": null}], "writes": []}'
    assert answer_col_spans(ans) == []       # null 列不产生列 span


def test_col_spans_multiple_edges():
    ans = ('{"reads": [{"table": "db.a", "columns": ["x"]}], '
           '"writes": [{"table": "db.b", "columns": ["y", "z"]}]}')
    spans = answer_col_spans(ans)
    assert len(spans) == 2
    assert ans[spans[0][0]:spans[0][1]] == '["x"]'
    assert ans[spans[1][0]:spans[1][1]] == '["y", "z"]'


# ===== 2. per-token 权重装配 =====
def test_weights_len_matches_ids(tok):
    r = _row([("db.customers", ["id", "name"])])
    out = build_weighted_row(r, tok, table_weight=3.0)
    assert len(out["input_ids"]) == len(out["weights"]) == len(out["labels"])
    assert out["labels"] == out["input_ids"]     # 整序列 LM,labels=input_ids(collator 只 mask padding)


def test_w1_reproduces_baseline_tokenization(tok):
    """可复现硬门:W=1.0 时 input_ids 必须逐字节复现 SFT 的 apply_chat_template(tokenize=True)。"""
    r = _row([("db.a", ["x"])], writes=[("db.b", None)])
    out = build_weighted_row(r, tok, table_weight=1.0)
    ref = tok.apply_chat_template(to_messages(r)["messages"],
                                  tokenize=True, return_dict=True, add_generation_prompt=False)
    assert out["input_ids"] == list(ref["input_ids"])
    assert all(w == 1.0 for w in out["weights"])   # W=1.0 → 全 1,加权 CE 退化为标准 CE


def _pieces_in(tok, ids, ws, target):
    """收集 decode 后(去空白、≥3 字符)属于 target 子串的 token 权重(避子词碎片误配)。"""
    hit = []
    for i, w in zip(ids, ws):
        p = tok.decode([i]).strip()
        if len(p) >= 3 and p in target:
            hit.append(w)
    return hit


def test_table_name_tokens_upweighted_columns_not(tok):
    """表名 token 权重=W;列数组值 token 权重=1.0(核心机制)。标识符两两不相交。"""
    W = 3.0
    r = _row([("tblQQQ", ["xvalWWW", "xvalZZZ"])])
    out = build_weighted_row(r, tok, table_weight=W)
    tbl_w = _pieces_in(tok, out["input_ids"], out["weights"], "tblQQQ")
    assert tbl_w and all(w == W for w in tbl_w)              # 表名被加权
    col_w = _pieces_in(tok, out["input_ids"], out["weights"], "xvalWWWxvalZZZ")
    assert col_w and all(w == 1.0 for w in col_w)            # 列值保持 1.0


def test_script_tokens_stay_unit_weight(tok):
    """答案外的脚本 token 保持 1.0(只重平衡答案内表列,不动脚本)。"""
    r = _row([("db.a", ["x"])])
    r["content"] = "SELECT scrYYY FROM db.a"
    out = build_weighted_row(r, tok, table_weight=5.0)
    scr_w = _pieces_in(tok, out["input_ids"], out["weights"], "scrYYY")
    assert scr_w and all(w == 1.0 for w in scr_w)


# ===== 3. 加权交叉熵数学 =====
def test_weighted_loss_reduces_to_ce_when_uniform():
    torch.manual_seed(0)
    B, T, V = 2, 5, 16
    logits = torch.randn(B, T, V)
    labels = torch.randint(0, V, (B, T))
    labels[:, 0] = -100                         # 掺入 mask token
    weights = torch.ones(B, T)
    got = weighted_lm_loss(logits, labels, weights)
    # 参考:标准 shift CE(ignore_index=-100, mean)
    ref = torch.nn.functional.cross_entropy(
        logits[..., :-1, :].reshape(-1, V), labels[..., 1:].reshape(-1),
        ignore_index=-100, reduction="mean")
    assert torch.allclose(got, ref, atol=1e-5)


def test_weighted_loss_scales_token_contribution():
    """两 token 手算:loss = (w1*ce1 + w2*ce2)/(w1+w2)。"""
    V = 4
    # 构造 3 位置序列 → shift 后 2 个预测位
    logits = torch.zeros(1, 3, V)
    logits[0, 0, 1] = 10.0   # 预测位0 强烈偏向 class1
    logits[0, 1, 2] = 10.0   # 预测位1 强烈偏向 class2
    labels = torch.tensor([[0, 1, 2]])           # 预测 pos1→label1(命中), pos2→label2(命中)
    w = torch.tensor([[1.0, 1.0, 5.0]])          # 末 token 权重 5
    ce = torch.nn.functional.cross_entropy(
        logits[..., :-1, :].reshape(-1, V), labels[..., 1:].reshape(-1), reduction="none")
    sw = w[..., 1:].reshape(-1)
    expect = (ce * sw).sum() / sw.sum()
    got = weighted_lm_loss(logits, labels, w)
    assert torch.allclose(got, expect, atol=1e-6)


# ===== 3b. collator pad 保权重 =====
def test_collator_pads_ids_labels_weights():
    from train.sft_qlora import WeightedCollator
    col = WeightedCollator(pad_token_id=0)
    batch = col([{"input_ids": [5, 6, 7], "labels": [5, 6, 7], "weights": [3.0, 1.0, 3.0]},
                 {"input_ids": [8, 9], "labels": [8, 9], "weights": [3.0, 1.0]}])
    assert batch["input_ids"].shape == (2, 3)
    assert batch["input_ids"][1].tolist() == [8, 9, 0]        # 右 pad
    assert batch["attention_mask"][1].tolist() == [1, 1, 0]
    assert batch["labels"][1].tolist() == [8, 9, -100]        # pad 位 label=-100
    assert batch["weights"][0].tolist() == [3.0, 1.0, 3.0]    # 权重原样保留
    assert batch["weights"][1].tolist() == [3.0, 1.0, 1.0]    # pad 位权重=1.0(无害)


# ===== 4. 门① 结构隔离:加权模块不引入任何 eval/metrics 依赖 =====
def test_no_eval_metrics_import_in_train():
    import train.sft_qlora as m
    src = __import__("inspect").getsource(m)
    assert "eval.metrics" not in src and "from eval" not in src  # 训练侧零评测耦合
