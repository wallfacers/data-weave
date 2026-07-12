"""065 T009（US2）：工具基线分层对照 smoke——招牌图逻辑（工具脚本≈0/模型救回）。"""
from __future__ import annotations

from realeval.eval_baselines_c import build_baseline_report


def _row(reads, writes, content, **kw):
    return dict(labels={"reads": [{"table": t} for t in reads],
                        "writes": [{"table": t} for t in writes]},
                content=content, is_empty=(not reads and not writes), **kw)


def test_report_has_stratified_sections_and_verdict():
    gold = [
        # SQL 样本：工具能抽
        _row(["ods_o"], ["dwd_o"], "insert into dwd_o select * from ods_o", type="sql"),
        _row(["ods_u"], ["dwd_u"], "insert into dwd_u select * from ods_u", type="sql"),
        # 脚本样本：工具结构性失效
        _row(["ods_e"], ["dwd_s"], "import pandas as pd\ndf=pd.read_sql('ods_e')\n"
                                   "df.to_sql('dwd_s')", type="python"),
    ]
    # 模型在脚本上救回（给对脚本样本），工具则空
    model_preds = [
        {"reads": [{"table": "ods_o"}], "writes": [{"table": "dwd_o"}]},
        {"reads": [{"table": "ods_u"}], "writes": [{"table": "dwd_u"}]},
        {"reads": [{"table": "ods_e"}], "writes": [{"table": "dwd_s"}]},
    ]
    md = build_baseline_report(gold, {"model-3b": model_preds}, n_resamples=300)
    assert "子集：sql" in md and "子集：script" in md
    assert "regex" in md and "sqllineage" in md and "model-3b" in md
    assert "SC-003 裁决" in md
    assert "结构性失效" in md


def test_sqllineage_recovers_sql_but_not_script():
    from eval.baselines.sqllineage_baseline import predict
    sql_out = predict({"content": "insert into dwd_o select * from ods_o"})
    script_out = predict({"content": "spark.read.table('a').write.saveAsTable('b')"})
    assert sql_out["writes"] and not script_out["writes"]  # SQL 命中 / 脚本空
