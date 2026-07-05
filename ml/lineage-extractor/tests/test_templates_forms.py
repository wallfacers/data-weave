import random

from data.templates import (
    Ctx,
    t_py_pyspark_saveastable,
    t_py_sqlalchemy_orm,
    t_sh_presto,
    t_sh_sqlplus,
    t_py_pandas_sql,
    t_sh_dbt_run,
    h_sh_dbt_run,
    t_scala_spark_sql,
    t_scala_save_table,
    t_scala_write_to,
    t_scala_flink_sql,
    t_scala_commented_sql,
    t_java_spark_sql,
    t_java_save_table,
    t_java_flink_sql,
    h_scala_dataset_chain,
    h_java_flink_from_insert,
    t_scala_dynamic_interp,
    t_java_dynamic_concat,
)


def _ctx():
    return Ctx(random.Random(7), ["ods.a", "dwd.b", "dws.c", "ads.d"], ["id", "amt", "dt", "region"])


def _check(fn):
    s = fn(_ctx())
    rt = {r["table"] for r in s.reads}
    wt = {w["table"] for w in s.writes}
    assert rt and wt and not (rt & wt)
    for t in rt | wt:
        assert t.split(".")[-1] in s.content or t in s.content
    return s


def test_all_new_forms():
    for fn in (t_py_pyspark_saveastable, t_py_sqlalchemy_orm, t_sh_presto, t_sh_sqlplus,
               t_py_pandas_sql, t_sh_dbt_run):
        _check(fn)


def test_jvm_positive_forms():
    """JVM（Scala/Java）正例：读写表名字面出现、方向正确、不同表。"""
    for fn in (t_scala_spark_sql, t_scala_save_table, t_scala_write_to, t_scala_flink_sql,
               t_scala_commented_sql, t_java_spark_sql, t_java_save_table, t_java_flink_sql,
               h_scala_dataset_chain, h_java_flink_from_insert):
        _check(fn)


def test_jvm_dynamic_name_not_labeled():
    """JVM 负例：s-插值 / 字符串拼接的动态 sink 名不进 labels；真实读仍在。"""
    for fn in (t_scala_dynamic_interp, t_java_dynamic_concat):
        for seed in range(10):
            s = fn(Ctx(random.Random(seed), ["ods.a", "dwd.b", "dws.c", "ads.d"],
                       ["id", "amt", "dt"]))
            assert s.reads and not s.writes, f"{fn.__name__}: 动态写不应进 labels"
            assert "tmp_" not in {t["table"] for t in s.reads}, "动态 tmp 名误入 reads"


def test_scala_commented_sql_ignores_comment_table():
    """Scala // 注释里的表名不算血缘。"""
    for seed in range(10):
        s = t_scala_commented_sql(Ctx(random.Random(seed), ["ods.a", "dwd.b", "dws.c", "ads.d"],
                                      ["id", "amt", "dt"]))
        exe = "\n".join(l for l in s.content.splitlines() if not l.strip().startswith("//"))
        for t in {r["table"] for r in s.reads} | {w["table"] for w in s.writes}:
            assert t in exe, f"表 {t} 应在可执行行（非注释）"


def test_dbt_labels_in_executable_line_not_comment():
    """零噪声铁律：dbt 写模型名与读来源必须字面出现在可执行行（非 `#` 注释）。
    旧 heldout 把写表标为 schema 全名、可执行行只有叶名 → 会红；修正后写=裸名 → 绿。
    训练/heldout 两个 dbt 模板都须满足。"""
    for fn in (t_sh_dbt_run, h_sh_dbt_run):
        for seed in range(20):  # 多 seed 覆盖命令/select/src 变体与叶名撞车守卫
            s = fn(Ctx(random.Random(seed), ["ods.a", "dwd.orders", "bare_tbl", "ads.d"],
                       ["id", "amt", "dt"]))
            exe = "\n".join(l for l in s.content.splitlines() if not l.strip().startswith("#"))
            rt = {r["table"] for r in s.reads}
            wt = {w["table"] for w in s.writes}
            assert rt and wt and not (rt & wt), f"{fn.__name__} seed={seed}: 读写为空或同表"
            for t in rt | wt:
                assert t in exe, f"{fn.__name__} seed={seed}: 表名 {t} 未在可执行行"
