"""059 语义级 grounding 过滤器单测。

夹具取自 Run C @ gold C 真实捞出的 grounded-but-wrong 假阳（叶名字面在脚本、
但出现位置是注释/import/路径/变量/临时视图），及真表引用的正样本。黑名单判据：
表名**所有**出现都落排除位置 → 剔；**任一**落真表位置 → 留（保护召回）。
"""
from realeval.semantic_grounding import keep_table_semantic, occurrence_contexts


# ---- 应剔：所有出现都在约定已排除的位置 ----

def test_reject_import_class():
    assert keep_table_semantic("preference", "from data_types import Preference\n") is False


def test_reject_commented_ddl():
    assert keep_table_semantic("stg_users", "# spark.sql('drop table if exists stg_users')\n") is False


def test_reject_dynamic_name():
    # $db_output.* —— 动态名，复用 is_dynamic 直接剔（与现有 literal 过滤器一致）
    src = "# MAGIC INSERT OVERWRITE TABLE $db_output.asd_dq_output SELECT 1\n"
    assert keep_table_semantic("$db_output.asd_dq_output", src) is False


def test_reject_file_path_csv():
    src = 'data = spark.read.csv("hdfs:///data/file.csv", header=True)\n'
    assert keep_table_semantic("file.csv", src) is False


def test_reject_csv_filename_pattern():
    src = 'benefile_pattern = "DE1_0_Beneficiary_Summary_File_Sample.csv"\n'
    assert keep_table_semantic("mimi_ws_1.desynpuf.beneficiary_summary", src) is False


def test_reject_glob_path():
    src = "cc_df = spark.read.csv('/user/xxx/store/stg_coin_category*', header=True)\n"
    assert keep_table_semantic("stg_coin_category*", src) is False


def test_reject_temp_view():
    src = "finalDF.createOrReplaceTempView('temp_finaldf')\n"
    assert keep_table_semantic("temp_finaldf", src) is False


def test_reject_attribute_access_via_embedded():
    # tracking_table 嵌在 tracking_table_name 内（无 word boundary）→ 非干净引用 → 剔。
    src = "identifier = hive_table_model.tracking_table_name\n"
    assert keep_table_semantic("tracking_table", src) is False


# ---- 标签歧义边界：param/attribute/var_decl **保守保留**（实测排除它们误杀真表，召回崩） ----

def test_keep_function_param_ambiguous():
    # 保守集不剔形参：变量持有的表名也可能是真血缘，交给召回护栏权衡（净收益更优）。
    src = "def get_hive_table_row_count(self, hive_table_model, clause=''):\n    return 1\n"
    assert keep_table_semantic("hive_table_model", src) is True


def test_keep_bare_var_assignment_ambiguous():
    # `SOURCE_TABLE = "orders"` —— gold 仲裁把这类变量持有名翻成真表，故保守保留。
    src = 'ICEBERG_SOURCE_TABLE_NAME = "orders_raw_unused"\n'
    assert keep_table_semantic("orders_raw_unused", src) is True


def test_keep_qualified_name_not_misread_as_attribute():
    # 限定表名 db.dev.stats 的叶名前带 '.'，不应被当属性访问剔（保守集不剔 attribute）。
    src = 'TARGET = f"{cat}.dev.category_daily_stats"\nspark.table(TARGET)\n'
    assert keep_table_semantic("demo_ib.dev.category_daily_stats", src) is True


def test_reject_notebook_run_magic():
    assert keep_table_semantic("measure_metadata", "%run ./measure_metadata\n") is False


# ---- 应留：至少一处出现在真表引用位置 ----

def test_keep_sql_from():
    assert keep_table_semantic("orders", "df = spark.sql('SELECT * FROM orders WHERE x=1')\n") is True


def test_keep_spark_table():
    assert keep_table_semantic("users", 'df = spark.table("users")\n') is True


def test_keep_insert_overwrite():
    assert keep_table_semantic("fact_sales", "INSERT OVERWRITE TABLE fact_sales SELECT * FROM stg\n") is True


def test_keep_save_as_table():
    assert keep_table_semantic("dw.fact", 'x.write.saveAsTable("dw.fact")\n') is True


def test_keep_qualified_from_leaf_match():
    # 预测限定名 db.s.orders，脚本里 FROM orders —— 叶名在真表位置
    assert keep_table_semantic("db.s.orders", "SELECT a FROM orders o JOIN dim d ON o.id=d.id\n") is True


# ---- 召回护栏：变量持有 + 别处真引用 → 不误杀 ----

def test_keep_var_held_but_also_referenced():
    src = ('TABLE = "sales"\n'
           'spark.sql(f"INSERT INTO sales SELECT * FROM raw")\n')
    assert keep_table_semantic("sales", src) is True


# ---- 多出现：一处注释 + 一处真 FROM → 留 ----

def test_keep_when_any_occurrence_is_table_ref():
    src = ("# TODO: rebuild orders\n"
           "df = spark.sql('SELECT * FROM orders')\n")
    assert keep_table_semantic("orders", src) is True


# ---- occurrence_contexts 暴露每处标签供审计 ----

def test_occurrence_contexts_labels():
    src = ("# from orders\n"
           "SELECT * FROM orders\n")
    ctxs = occurrence_contexts("orders", src)
    assert "comment" in ctxs
    assert "table_ref" in ctxs
