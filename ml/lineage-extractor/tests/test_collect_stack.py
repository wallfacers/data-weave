"""059 collect_stack 纯函数单测：ETL 习语门 + license 双检（离线，无 datasets 依赖）。"""
from realeval.collect_stack import is_etl_script, _licenses_ok


def test_etl_idiom_matches():
    assert is_etl_script("spark.sql('INSERT INTO ods.a SELECT * FROM dwd.b')")
    assert is_etl_script("df.write.saveAsTable('mydb.out')")
    assert is_etl_script("COPY INTO tgt FROM @stage")
    assert is_etl_script("hive -e 'INSERT OVERWRITE TABLE t SELECT 1'")


def test_literal_density_fallback_keeps_lineage_scripts():
    # 无习语关键词但有字面表名（FROM x）→ literal_density>0 兜底保留。
    assert is_etl_script("sql = 'SELECT a FROM analytics.events'")


def test_non_etl_rejected():
    assert not is_etl_script("def add(a, b):\n    return a + b")
    assert not is_etl_script("print('hello world')")


def test_licenses_ok():
    assert _licenses_ok({"max_stars_repo_licenses": ["mit"]})
    assert _licenses_ok({"max_stars_repo_licenses": ["gpl-3.0", "apache-2.0"]})  # 任一宽松即可
    assert _licenses_ok({"max_stars_repo_licenses": "bsd-3-clause"})             # 字符串亦可
    assert not _licenses_ok({"max_stars_repo_licenses": ["gpl-3.0"]})
    assert not _licenses_ok({"max_stars_repo_licenses": []})                     # 无 license → 剔
    assert not _licenses_ok({})
