from realeval.collect import (
    sanitize, is_redistributable, dedup_key, literal_density,
    looks_like_etl_job, QUERY_PROFILES, LITERAL_RICH_QUERIES,
)


def test_sanitize_strips_secrets():
    s = sanitize('psql -h 10.0.0.5 --password=secret123 -c "SELECT 1"')
    assert "secret123" not in s and "10.0.0.5" not in s


def test_license_filter():
    assert is_redistributable("Apache-2.0") and is_redistributable("MIT")
    assert not is_redistributable("GPL-3.0")


def test_dedup_stable():
    assert dedup_key("SELECT  1") == dedup_key("select 1")


def test_sanitize_covers_password_forms():
    for s in ['password: supersecret123', 'PGPASSWORD=supersecret123',
              'jdbc:mysql://admin:supersecret123@10.0.0.5:3306/db']:
        assert 'supersecret123' not in sanitize(s)


def test_sanitize_strips_connection_host():
    # 连接串裸主机名（RDS/内网 endpoint）必须脱敏，含或不含凭据两种
    bare = sanitize('connect_url="jdbc:mysql://pc-bp185ot31.mysql.polardb.rds.aliyuncs.com:3306/prod"')
    assert 'rds.aliyuncs.com' not in bare and 'pc-bp185ot31' not in bare
    creds = sanitize('mysql://etl_user:pw@warehouse-prod.internal:3306/dw')
    assert 'warehouse-prod.internal' not in creds and 'etl_user' not in creds
    # 下划线主机名（Docker Compose/K8s 服务名，如 mysql_master/db_prod_01）不能被腰斩泄漏
    us = sanitize('jdbc:postgresql://db_prod_01:5432/app')
    assert 'db_prod_01' not in us and '5432' not in us
    usc = sanitize('mysql://root:pw@mysql_master:3306/dw')
    assert 'mysql_master' not in usc and '3306' not in usc


def test_sanitize_preserves_sql_table_names():
    # 脱敏不得误伤 SQL 里的表名（无 `://`）——血缘评测集的核心信息
    sql = 'INSERT INTO ods.orders_di SELECT id FROM dwd.user_events'
    assert sanitize(sql) == sql


def test_literal_density_counts_distinct_literal_tables():
    # 字面表名多 → 密度高（考抽取）
    hi = literal_density(
        "INSERT INTO ods.a SELECT x FROM dwd.b JOIN dwd.c ON dwd.b.id = dwd.c.id")
    assert hi >= 3


def test_literal_density_zero_when_parameterized():
    # 参数化表名 → 密度 0（只考低幻觉）；这正是真实 ETL 的常态
    assert literal_density('spark.sql(f"INSERT INTO {tbl} SELECT * FROM {src}")') == 0
    assert literal_density('bq query --destination_table $DEST < job.sql') == 0


def test_looks_like_job_rejects_framework_source():
    # 框架库源码路径 → 非作业（哪怕含大量字面表名）
    assert not looks_like_etl_job("x = 1", "pyspark/sql/readwriter.py")
    assert not looks_like_etl_job("x = 1", "src/sqlglot/generator.py")
    assert not looks_like_etl_job("x = 1", "fakesnow/copy_into.py")
    # 测试夹具路径 → 非作业
    assert not looks_like_etl_job("x = 1", "tests/test_lineage.py")


def test_looks_like_job_rejects_pure_class_module():
    # 纯 class 定义、无顶层执行 = 库模块
    lib = "import os\n\nclass Builder:\n    def build(self):\n        return 'INSERT INTO a SELECT 1'\n"
    assert not looks_like_etl_job(lib, "pgqb/builder.py")


def test_looks_like_job_accepts_real_job():
    # 顶层执行调用 = 作业
    job = 'spark.sql("INSERT INTO ods.a SELECT * FROM dwd.b")\n'
    assert looks_like_etl_job(job, "jobs/load_orders.py")
    # __main__ guard = 作业
    main = 'def run():\n    pass\n\nif __name__ == "__main__":\n    run()\n'
    assert looks_like_etl_job(main, "etl/daily.py")
    # shell 脚本 = 作业
    assert looks_like_etl_job('hive -e "INSERT INTO a SELECT * FROM b"', "run.sh")


def test_query_profiles_wellformed():
    # 分层查询组齐备（literal/natural/expanded/jvm/mixed/wide）
    assert set(QUERY_PROFILES) == {"literal", "natural", "expanded", "jvm", "mixed", "wide"}
    assert QUERY_PROFILES["mixed"][: len(LITERAL_RICH_QUERIES)] == LITERAL_RICH_QUERIES
    # literal/natural 限定 python/shell（与 prelabel task_type 推断对齐）
    for q in LITERAL_RICH_QUERIES:
        assert "language:python" in q or "language:shell" in q
    # jvm 组限定 scala/java（与 .scala→SCALA / .java→JAVA 推断对齐）
    for q in QUERY_PROFILES["jvm"]:
        assert "language:scala" in q or "language:java" in q
