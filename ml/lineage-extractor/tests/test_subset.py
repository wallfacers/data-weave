"""065 T002：sql/script 子集分类器单测（rigor CHK010 归类判据）。"""
from __future__ import annotations

from eval.subset import classify


def test_by_type_field():
    assert classify({"type": "SQL", "content": ""}) == "sql"
    assert classify({"type": "python", "content": ""}) == "script"
    assert classify({"lang": "spark", "content": ""}) == "script"
    assert classify({"lang": "hive", "content": ""}) == "sql"


def test_by_path_extension():
    assert classify({"path": "etl/load.sql", "content": ""}) == "sql"
    assert classify({"path": "jobs/pipeline.py", "content": ""}) == "script"
    assert classify({"path": "run.sh", "content": ""}) == "script"


def test_by_content_signal():
    assert classify({"content": "import pandas as pd\ndf.to_sql('t', con)"}) == "script"
    assert classify({"content": "INSERT INTO dwd_o SELECT * FROM ods_o"}) == "sql"
    assert classify({"content": "spark.read.table('a').write.saveAsTable('b')"}) == "script"


def test_fallback_is_sql_conservative():
    # 无任何信号 → 保守归 sql（不夸大脚本子集，守诚实）
    assert classify({"content": "-- just a comment"}) == "sql"
    assert classify({}) == "sql"
