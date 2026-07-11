"""T024: sidecar postprocess 单测（dir_fix 非 override / 弃权 / 畸形健壮 / 确定性）。

只测纯函数 postprocess——不加载模型/GPU（serve.app 顶部 import torch 但 postprocess 不用它）。
"""
from serve.app import postprocess


def test_dir_fix_not_override():
    # 模型抽到 SQL 通道没识别、但**字面在脚本里**的表 → 保留（dir_fix override 会丢）。
    # 注：改用 grounded 的模型独有表（dwd_extra 在变量里），与语义 grounding 兼容。
    content = 'spark.sql("SELECT a FROM ods.orders")\ntarget = "dwd_extra"'
    text = '{"reads": [{"table": "ods.orders", "columns": null}, {"table": "dwd_extra", "columns": null}], "writes": []}'
    out = postprocess(text, content)
    names = {i["table"] for i in out["reads"] + out["writes"]}
    assert "dwd_extra" in names  # grounded 的模型独有表未丢


def test_dir_fix_corrects_direction():
    content = 'spark.sql("INSERT OVERWRITE TABLE dwd.clean SELECT * FROM ods.orders")'
    text = '{"reads": [{"table": "dwd.clean"}, {"table": "ods.orders"}], "writes": []}'
    out = postprocess(text, content)
    assert "dwd.clean" in {i["table"] for i in out["writes"]}  # AST 纠为写
    assert out["dir_fixed"] is True


def test_abstain_empty():
    out = postprocess('{"reads": [], "writes": []}', "echo hi; python x.py")
    assert out["reads"] == [] and out["writes"] == []
    assert out["dir_fixed"] is False


def test_malformed_model_output():
    # 模型吐非 JSON → 空，不抛
    out = postprocess("sorry I cannot help", "SELECT * FROM t")
    assert out["reads"] == [] and out["writes"] == []


def test_huge_malformed_script_no_hang():
    # 远处分号大块 + 模板标记 → 片段窗封顶防回溯爆内存，快速返回
    content = 'x = "SELECT * FROM {{ p.t }} WHERE $CONDITIONS"\n' + ("y" * 60000) + ";"
    text = '{"reads": [{"table": "real.t"}], "writes": []}'
    out = postprocess(text, content)
    assert {i["table"] for i in out["reads"]} == {"real.t"}


def test_deterministic_same_input_same_output():
    content = 'spark.sql("INSERT INTO dwd.t SELECT * FROM ods.s")'
    text = '{"reads": [{"table": "ods.s"}], "writes": [{"table": "dwd.t"}]}'
    a = postprocess(text, content)
    b = postprocess(text, content)
    assert a == b


# ---- 语义 grounding 接进 serving（生产后处理，默认开，可 env 回滚） ----

def test_grounding_drops_hallucination():
    # 模型吐出脚本里根本不存在的表名（幻觉）→ 剔，grounded=True，真表保留
    content = 'spark.sql("SELECT a FROM ods.orders")'
    text = '{"reads": [{"table": "ods.orders"}, {"table": "phantom_tbl"}], "writes": []}'
    out = postprocess(text, content)
    names = {i["table"] for i in out["reads"] + out["writes"]}
    assert "phantom_tbl" not in names
    assert "ods.orders" in names
    assert out["grounded"] is True


def test_grounding_drops_comment_only_table():
    # 表名只出现在注释里 → grounded-but-wrong，剔
    content = '# insert into audit_log\nspark.sql("SELECT 1 FROM ods.real")'
    text = '{"reads": [{"table": "ods.real"}], "writes": [{"table": "audit_log"}]}'
    out = postprocess(text, content)
    names = {i["table"] for i in out["reads"] + out["writes"]}
    assert "audit_log" not in names
    assert "ods.real" in names


def test_grounding_keeps_real_table():
    content = 'spark.sql("SELECT * FROM ods.real_tbl")'
    text = '{"reads": [{"table": "ods.real_tbl"}], "writes": []}'
    out = postprocess(text, content)
    assert "ods.real_tbl" in {i["table"] for i in out["reads"]}
    assert out["grounded"] is False  # 无剔除


def test_grounding_can_be_disabled():
    # env 回滚路径：ground=False 保留幻觉（旧行为）
    content = 'spark.sql("SELECT a FROM ods.orders")'
    text = '{"reads": [{"table": "ods.orders"}, {"table": "phantom_tbl"}], "writes": []}'
    out = postprocess(text, content, ground=False)
    assert "phantom_tbl" in {i["table"] for i in out["reads"]}
    assert out["grounded"] is False
