"""T008: dir_fix 核心单测（策略非 override / 方向修正 / 畸形健壮 / 列保留）。"""
from realeval.dir_fix import apply_dir_fix, sql_direction


def test_not_override_keeps_model_only_tables():
    # 模型抽到 SQL 通道没识别到的表 → dir_fix 必须保留（override 会丢）
    content = 'df = spark.sql("SELECT a FROM ods.orders")\n# 另有动态写入 dwd.x'
    model = {"reads": [{"table": "ods.orders"}], "writes": [{"table": "dwd.x"}]}
    out = apply_dir_fix(model, content)
    names = {i["table"] for i in out["reads"] + out["writes"]}
    assert "dwd.x" in names  # 模型独有表未被丢弃


def test_direction_corrected_by_ast():
    # 模型把写当成读；AST（INSERT OVERWRITE）判为写 → 纠正 + dir_fixed=True
    content = 'spark.sql("INSERT OVERWRITE TABLE dwd.orders_clean SELECT * FROM ods.orders")'
    model = {"reads": [{"table": "dwd.orders_clean"}, {"table": "ods.orders"}], "writes": []}
    out = apply_dir_fix(model, content)
    wnames = {i["table"] for i in out["writes"]}
    rnames = {i["table"] for i in out["reads"]}
    assert "dwd.orders_clean" in wnames  # 被 AST 纠为写
    assert "ods.orders" in rnames
    assert out["dir_fixed"] is True


def test_no_sql_passthrough():
    content = "echo hello; python train.py --epochs 3"
    model = {"reads": [{"table": "a"}], "writes": [{"table": "b"}]}
    out = apply_dir_fix(model, content)
    assert {i["table"] for i in out["reads"]} == {"a"}
    assert {i["table"] for i in out["writes"]} == {"b"}
    assert out["dir_fixed"] is False


def test_columns_preserved():
    content = 'spark.sql("SELECT id FROM ods.orders")'
    model = {"reads": [{"table": "ods.orders", "columns": ["id"]}], "writes": []}
    out = apply_dir_fix(model, content)
    assert out["reads"][0].get("columns") == ["id"]


def test_malformed_huge_fragment_no_hang():
    # 远处分号致大块 + 模板标记 → 健壮性补丁（片段窗封顶/跳模板/限时）应快速返回不爆
    content = 'sql = "SELECT * FROM {{ params.tbl }} WHERE x = $CONDITIONS"\n' + ("x" * 50000) + ";"
    model = {"reads": [{"table": "real.t"}], "writes": []}
    out = apply_dir_fix(model, content)
    assert "reads" in out and "writes" in out  # 完成且结构正确
    assert {i["table"] for i in out["reads"]} == {"real.t"}


def test_sql_direction_map():
    content = 'spark.sql("INSERT INTO dwd.t SELECT * FROM ods.s")'
    role = sql_direction(content)
    assert role.get("dwd.t") == "w"
    assert role.get("ods.s") == "r"
