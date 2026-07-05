"""041 脚本血缘合成模板库。

每个模板 = (template_id, task_type, render(ctx) -> (content, labels))。
labels 由注入过程自动产生（零人工、零噪声）；动态表名/注释假 SQL 等负例形态
**不进 labels**——教会模型宁缺毋滥（FR-006/SC-006 幻觉率）。

split:
  TRAIN_TEMPLATES   训练 + heldout 皆可实例化（heldout 用未见过的名字组合）
  HELDOUT_TEMPLATES 仅进 heldout（形态隔离，测泛化 = "规则未覆盖子集"）
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Callable


@dataclass
class Sample:
    content: str
    reads: list[dict] = field(default_factory=list)   # {"table": str, "columns": [str]|None}
    writes: list[dict] = field(default_factory=list)


@dataclass
class Template:
    template_id: str
    task_type: str  # PYTHON | SHELL | SCALA | JAVA
    render: Callable[["Ctx"], Sample]
    rule_covered: bool = True  # False = Java 规则通道不覆盖（heldout 子集召回指标用）
    form_family: str = ""  # 空则由 synth_pipeline 按 template_id 前缀推断


class Ctx:
    """一次实例化的随机上下文：表名/列名/常量池，全部经 seeded RNG。"""

    def __init__(self, rng: random.Random, tables: list[str], columns: list[str]):
        self.rng = rng
        self._tables = tables
        self._columns = columns

    def table(self) -> str:
        return self.rng.choice(self._tables)

    def tables(self, n: int) -> list[str]:
        return self.rng.sample(self._tables, n)

    def cols(self, n: int) -> list[str]:
        return self.rng.sample(self._columns, min(n, len(self._columns)))

    def num(self) -> int:
        return self.rng.randint(1, 500)

    def distractors(self, task_type: str) -> str:
        """随机干扰行（不涉及任何表）。"""
        py = [
            "import logging",
            "logger = logging.getLogger(__name__)",
            "threshold = cfg.get('threshold', 0.5)",
            "result = value * ratio + offset",
            "if not rows:\n    logger.warning('empty result')",
            "retries = int(os.environ.get('RETRIES', '3'))",
            "metrics.append(round(score, 4))",
        ]
        sh = [
            "set -euo pipefail",
            "export TZ=Asia/Shanghai",
            "echo \"job start: $(date +%F)\"",
            "RETRIES=${RETRIES:-3}",
            "mkdir -p /tmp/joblog",
            "trap 'echo failed' ERR",
        ]
        scala = [
            "import org.apache.spark.sql.functions._",
            "val logger = LoggerFactory.getLogger(getClass)",
            "spark.conf.set(\"spark.sql.shuffle.partitions\", \"200\")",
            "val threshold = args.headOption.map(_.toDouble).getOrElse(0.5)",
            "logger.info(s\"job start ${java.time.LocalDate.now}\")",
        ]
        java = [
            "Logger log = LoggerFactory.getLogger(App.class);",
            "spark.conf().set(\"spark.sql.shuffle.partitions\", \"200\");",
            "int retries = Integer.parseInt(System.getenv(\"RETRIES\"));",
            "log.info(\"job start {}\", LocalDate.now());",
            "double threshold = Double.parseDouble(args[0]);",
        ]
        pool = {"PYTHON": py, "SHELL": sh, "SCALA": scala, "JAVA": java}.get(task_type, py)
        k = self.rng.randint(1, 3)
        return "\n".join(self.rng.sample(pool, k))


# ── SQL 语句生成（labels 由构造保证精确）─────────────────────────────

def _insert_select(ctx: Ctx) -> tuple[str, Sample]:
    r, w = ctx.tables(2)
    cols = ctx.cols(ctx.rng.randint(2, 4))
    sql = f"INSERT INTO {w} SELECT {', '.join(cols)} FROM {r} WHERE {cols[0]} > {ctx.num()}"
    s = Sample(content="", reads=[{"table": r, "columns": cols}], writes=[{"table": w, "columns": cols}])
    return sql, s


def _insert_join(ctx: Ctx) -> tuple[str, Sample]:
    r1, r2, w = ctx.tables(3)
    c = ctx.cols(3)
    sql = (f"INSERT INTO {w} SELECT a.{c[0]}, b.{c[1]} FROM {r1} a "
           f"JOIN {r2} b ON a.{c[2]} = b.{c[2]}")
    s = Sample(content="",
               reads=[{"table": r1, "columns": None}, {"table": r2, "columns": None}],
               writes=[{"table": w, "columns": None}])
    return sql, s


def _select_only(ctx: Ctx) -> tuple[str, Sample]:
    r = ctx.table()
    cols = ctx.cols(2)
    sql = f"SELECT {cols[0]}, {cols[1]} FROM {r} LIMIT {ctx.num()}"
    return sql, Sample(content="", reads=[{"table": r, "columns": cols}])


def _insert_values(ctx: Ctx) -> tuple[str, Sample]:
    w = ctx.table()
    cols = ctx.cols(2)
    sql = f"INSERT INTO {w} ({cols[0]}, {cols[1]}) VALUES (%s, %s)"
    return sql, Sample(content="", writes=[{"table": w, "columns": cols}])


# ── PYTHON 训练模板 ──────────────────────────────────────────────────

def t_py_spark_sql_inline(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'{ctx.distractors("PYTHON")}\nspark.sql("{sql}")\n'
    return s


def t_py_sql_var_indirect(ctx: Ctx) -> Sample:
    sql, s = _insert_join(ctx)
    s.content = (f'{ctx.distractors("PYTHON")}\n'
                 f'sql = "{sql}"\n'
                 f'spark.sql(sql)\n')
    return s


def t_py_cursor_execute(ctx: Ctx) -> Sample:
    sql, s = _insert_values(ctx)
    s.content = (f'conn = psycopg2.connect(dsn)\ncur = conn.cursor()\n'
                 f'cur.execute("{sql}", (uid, amt))\nconn.commit()\n')
    return s


def t_py_cursor_select(ctx: Ctx) -> Sample:
    sql, s = _select_only(ctx)
    s.content = (f'cur.execute("{sql}")\nrows = cur.fetchall()\n'
                 f'{ctx.distractors("PYTHON")}\n')
    return s


def t_py_multi_statement(ctx: Ctx) -> Sample:
    sql1, s1 = _select_only(ctx)
    sql2, s2 = _insert_select(ctx)
    s = Sample(content=(f'spark.sql("{sql1}")\n{ctx.distractors("PYTHON")}\n'
                        f'spark.sql("{sql2}")\n'),
               reads=s1.reads + s2.reads, writes=s2.writes)
    return s


def t_py_read_save_table(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'df = spark.read.table("{r}")\n'
                 f'df = df.filter(df.status == "OK")\n'
                 f'df.write.mode("overwrite").saveAsTable("{w}")\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def t_py_pandas_roundtrip(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    cols = ctx.cols(2)
    return Sample(
        content=(f'df = pd.read_sql("SELECT {cols[0]}, {cols[1]} FROM {r}", engine)\n'
                 f'{ctx.distractors("PYTHON")}\n'
                 f'df.to_sql("{w}", engine, if_exists="append", index=False)\n'),
        reads=[{"table": r, "columns": cols}], writes=[{"table": w, "columns": None}])


def t_py_to_sql_columns(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    cols = ctx.cols(2)
    return Sample(
        content=(f'df = spark.read.table("{r}").toPandas()\n'
                 f'df[["{cols[0]}", "{cols[1]}"]].to_sql("{w}", engine, index=False)\n'),
        reads=[{"table": r, "columns": None}],
        writes=[{"table": w, "columns": cols}])


def t_py_insert_into(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'src = spark.read.table("{r}")\n'
                 f'src.write.insertInto("{w}", overwrite=True)\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def t_py_subprocess_hive(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = (f'import subprocess\n'
                 f'subprocess.run(["hive", "-e", "{sql}"], check=True)\n')
    return s


def t_py_dynamic_fstring(ctx: Ctx) -> Sample:
    """负例：f-string 动态表名 → 不进 labels；旁边保留一个真实读。"""
    r = ctx.table()
    return Sample(
        content=(f'df = spark.read.table("{r}")\n'
                 f'tbl = f"dw.tmp_{{ds_nodash}}"\n'
                 f'df.write.saveAsTable(tbl)\n'),
        reads=[{"table": r, "columns": None}])


def t_py_commented_sql(ctx: Ctx) -> Sample:
    """负例：注释里的 SQL 不算；真实执行的是另一条。"""
    fake = ctx.table()
    sql, s = _select_only(ctx)
    s.content = (f'# TODO: 旧逻辑 INSERT INTO {fake} SELECT * FROM legacy\n'
                 f'cur.execute("{sql}")\n')
    return s


def t_py_pure_compute(ctx: Ctx) -> Sample:
    return Sample(content=(f'{ctx.distractors("PYTHON")}\n'
                           f'total = sum(x ** 2 for x in range(100))\n'
                           f'print(round(total / 7, 3))\n'))


def t_py_logged_sql_not_executed(ctx: Ctx) -> Sample:
    """负例：仅打印/记录 SQL 字符串、从未执行。"""
    fake = ctx.table()
    return Sample(content=(f'msg = "would run: INSERT INTO {fake} SELECT 1"\n'
                           f'logger.info(msg)\n{ctx.distractors("PYTHON")}\n'))


# ── SHELL 训练模板 ──────────────────────────────────────────────────

def t_sh_hive_e(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'{ctx.distractors("SHELL")}\nhive -e "{sql}"\n'
    return s


def t_sh_psql_c(ctx: Ctx) -> Sample:
    sql, s = _insert_join(ctx)
    s.content = f'psql -h "$PGHOST" -U etl -c "{sql}"\n'
    return s


def t_sh_heredoc(ctx: Ctx) -> Sample:
    sql1, s1 = _select_only(ctx)
    sql2, s2 = _insert_select(ctx)
    s = Sample(content=(f'psql "$DB_URL" <<SQL\n{sql1};\n{sql2};\nSQL\n'),
               reads=s1.reads + s2.reads, writes=s2.writes)
    return s


def t_sh_sqoop_export(ctx: Ctx) -> Sample:
    w = ctx.table()
    cols = ctx.cols(2)
    return Sample(
        content=(f'sqoop export --connect "$JDBC" --table {w} '
                 f'--columns {cols[0]},{cols[1]} --export-dir /warehouse/stage\n'),
        writes=[{"table": w, "columns": cols}])


def t_sh_sqoop_import(ctx: Ctx) -> Sample:
    r = ctx.table()
    return Sample(
        content=(f'{ctx.distractors("SHELL")}\n'
                 f'sqoop import --connect "$JDBC" --table {r} --target-dir /tmp/land\n'),
        reads=[{"table": r, "columns": None}])


def t_sh_beeline(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'beeline -u "$HS2_URL" -e "{sql}"\n'
    return s


def t_sh_mysql_e(ctx: Ctx) -> Sample:
    sql, s = _insert_values(ctx)
    s.content = f'mysql -h db01 -uetl -p"$PW" -e "{sql}"\n'
    return s


def t_sh_spark_sql_e(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'spark-sql --master yarn -e "{sql}"\n'
    return s


def t_sh_dynamic_var(ctx: Ctx) -> Sample:
    """负例：变量表名 → 不进 labels。"""
    r = ctx.table()
    return Sample(
        content=(f'TBL="ads_report_${{BIZ_DATE}}"\n'
                 f'hive -e "INSERT INTO $TBL SELECT * FROM {r}"\n'),
        reads=[{"table": r, "columns": None}])


def t_sh_echo_only(ctx: Ctx) -> Sample:
    """负例：echo 打印 SQL、未执行。"""
    fake = ctx.table()
    return Sample(content=(f'echo "dry-run: INSERT INTO {fake} SELECT 1"\n'
                           f'{ctx.distractors("SHELL")}\n'))


READ_VERBS = ["load", "read", "fetch", "extract", "source", "pull", "get"]
WRITE_VERBS = ["write", "save", "sink", "persist", "dump", "export", "push", "upsert"]


def t_py_wrapper_verb(ctx: Ctx) -> Sample:
    """custom-wrapper 动词→方向模板族：动词前缀（读/写词池）决定读写方向。"""
    r, w = ctx.tables(2)
    rv = ctx.rng.choice(READ_VERBS)
    wv = ctx.rng.choice(WRITE_VERBS)
    noun_r = ctx.rng.choice(["frame", "source", "table", "dataset", "input"])
    noun_w = ctx.rng.choice(["warehouse", "sink", "target", "output", "store"])
    return Sample(
        content=(f'df = {rv}_{noun_r}(ctx, "{r}")\n'
                 f'{wv}_to_{noun_w}(df, "{w}", mode="overwrite")\n'),
        reads=[{"table": r, "columns": None}],
        writes=[{"table": w, "columns": None}],
    )


# ── 多引擎 CLI / ORM / 链式 DataFrame / pandas 形态（拓宽训练谱系）───────

def t_py_pyspark_saveastable(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'df = spark.read.table("{r}")\n'
                 f'df.filter("dt >= \'2024-01-01\'").write.mode("append").saveAsTable("{w}")\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def t_py_sqlalchemy_orm(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    c = ctx.cols(2)
    return Sample(
        content=(f'rows = session.query(Src).filter(Src.{c[0]} > {ctx.num()}).all()\n'
                 f'# src table: {r}\n'
                 f'engine.execute("INSERT INTO {w} SELECT * FROM {r}")\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def t_sh_presto(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'presto --server presto01:8080 --catalog hive --execute "{sql}"\n'
    return s


def t_sh_sqlplus(ctx: Ctx) -> Sample:
    """Oracle sqlplus heredoc 形态：与既有 impala/clickhouse/psql/spark-sql/beeline/presto/mysql
    均为不同引擎/协议的真新形态（区别于曾提议的 sh-beeline-jdbc——那只是 sh-beeline 的连接串变体）。"""
    sql, s = _insert_select(ctx)
    s.content = f'sqlplus -s etl/"$ORA_PW"@orcl <<EOF\n{sql};\nEOF\n'
    return s


def t_py_pandas_sql(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'df = pd.read_sql("SELECT * FROM {r}", conn)\n'
                 f'df.to_sql("{w}", conn, if_exists="replace", index=False)\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def _dbt_model_name(ctx: Ctx, w: str, r: str) -> str:
    """dbt 模型名=裸名（schema 由 dbt 配置管理，不出现在 --select）；防叶名与读表相撞成读写同表。"""
    model = w.split(".")[-1]
    if model == r or model == r.split(".")[-1]:
        model = model + "_model"
    return model


def t_sh_dbt_run(ctx: Ctx) -> Sample:
    """dbt 约定形态（训练）：--select/-s/--models 命名的模型=写（裸名），--vars 的 src/source=读。
    `--select` 无方向词法线索（听起来像读），故必须在训练分布内见过该工具约定才能推断方向。"""
    r, _w = ctx.tables(2)
    model = _dbt_model_name(ctx, _w, r)
    cmd = ctx.rng.choice(["dbt run", "dbt build"])
    sel = ctx.rng.choice(["--select", "-s", "--models"])
    src = ctx.rng.choice([
        f'--vars \'{{"src":"{r}"}}\'',
        f'--vars \'{{"source_table":"{r}"}}\'',
        f"--vars 'source: {r}'",
    ])
    return Sample(
        content=(f'# model {model} depends on {r}\n'
                 f'{cmd} {sel} {model} {src}\n'),
        reads=[{"table": r, "columns": None}],
        writes=[{"table": model, "columns": None}])


# ── JVM 作业模板（Scala / Java：Spark + Flink，拓宽语言谱系至 C-下半）──────
# 表名同样只在 API 调用/内嵌 SQL 字符串里字面出现；s-插值/字符串拼接的动态名不进
# labels（与 Python f-string 负例同构）。distractors 用 SCALA/JAVA 池。

def t_scala_spark_sql(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'{ctx.distractors("SCALA")}\nspark.sql("{sql}")\n'
    return s


def t_scala_save_table(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'val df = spark.table("{r}")\n'
                 f'df.filter($"status" === "OK").write.mode("overwrite").saveAsTable("{w}")\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def t_scala_write_to(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'spark.table("{r}").where("dt = current_date()").writeTo("{w}").append()\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def t_scala_flink_sql(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = (f'{ctx.distractors("SCALA")}\n'
                 f'tableEnv.executeSql("{sql}")\n')
    return s


def t_scala_dynamic_interp(ctx: Ctx) -> Sample:
    """负例：s-插值动态表名 → 不进 labels；旁边保留一个真实读。"""
    r = ctx.table()
    return Sample(
        content=(f'val df = spark.table("{r}")\n'
                 f'val sink = s"dw.tmp_${{dsNodash}}"\n'
                 f'df.write.saveAsTable(sink)\n'),
        reads=[{"table": r, "columns": None}])


def t_java_spark_sql(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'{ctx.distractors("JAVA")}\nspark.sql("{sql}");\n'
    return s


def t_java_save_table(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'Dataset<Row> df = spark.table("{r}");\n'
                 f'df.write().mode("overwrite").saveAsTable("{w}");\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def t_java_flink_sql(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = (f'{ctx.distractors("JAVA")}\n'
                 f'tableEnv.executeSql("{sql}");\n')
    return s


def t_java_dynamic_concat(ctx: Ctx) -> Sample:
    """负例：字符串拼接动态表名 → 不进 labels；旁边保留一个真实读。"""
    r = ctx.table()
    return Sample(
        content=(f'Dataset<Row> df = spark.table("{r}");\n'
                 f'String sink = "dw.tmp_" + dsNodash;\n'
                 f'df.write().saveAsTable(sink);\n'),
        reads=[{"table": r, "columns": None}])


def t_scala_commented_sql(ctx: Ctx) -> Sample:
    """负例：// 注释里的 SQL 不算；真实执行的是另一条。"""
    fake = ctx.table()
    sql, s = _insert_select(ctx)
    s.content = (f'// legacy: INSERT INTO {fake} SELECT * FROM legacy\n'
                 f'spark.sql("{sql}")\n')
    return s


# ── HELDOUT-ONLY 模板（规则未覆盖形态，测泛化）────────────────────────

def h_py_custom_wrapper_write(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'df = load_source_frame(spark, "{r}")\n'
                 f'write_to_warehouse(df, "{w}", mode="overwrite")\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def h_py_kwarg_table(ctx: Ctx) -> Sample:
    r = ctx.table()
    return Sample(
        content=(f'frame = warehouse_client.fetch(table="{r}", limit=5000)\n'
                 f'{ctx.distractors("PYTHON")}\n'),
        reads=[{"table": r, "columns": None}])


def h_py_sqlalchemy_text(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = (f'from sqlalchemy import text\n'
                 f'with engine.begin() as conn:\n'
                 f'    conn.execute(text("{sql}"))\n')
    return s


def h_py_spark_table_alias(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'events = spark.table("{r}").where("dt = current_date()")\n'
                 f'events.writeTo("{w}").append()\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def h_sh_impala(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'impala-shell -i impalad01 -q "{sql}"\n'
    return s


def h_sh_clickhouse(ctx: Ctx) -> Sample:
    sql, s = _insert_select(ctx)
    s.content = f'clickhouse-client --host ch01 --query "{sql}"\n'
    return s


def h_py_orm_chain_dsl(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'Repo.of("{r}").select(["id", "amt"]).copy_into("{w}").commit()\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def h_py_decorator_pipeline(ctx: Ctx) -> Sample:
    r, w = ctx.tables(2)
    return Sample(
        content=(f'@pipeline(reads="{r}", writes="{w}")\n'
                 f'def run(src):\n    return transform(src)\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def h_sh_dbt_run(ctx: Ctx) -> Sample:
    """dbt 约定形态（heldout，未见名字）：写=裸模型名，与可执行 `--select <model>` 字面一致（零噪声）。"""
    r, _w = ctx.tables(2)
    model = _dbt_model_name(ctx, _w, r)
    return Sample(
        content=(f'# model {model} depends on {r}\n'
                 f'dbt run --select {model} --vars \'{{"src":"{r}"}}\'\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": model, "columns": None}])


def h_scala_dataset_chain(ctx: Ctx) -> Sample:
    """heldout：Scala 链式 read→写，未见名字组合，测跨语言形态泛化。"""
    r, w = ctx.tables(2)
    return Sample(
        content=(f'spark.read.table("{r}").select("id", "amt")'
                 f'.write.insertInto("{w}")\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


def h_java_flink_from_insert(ctx: Ctx) -> Sample:
    """heldout：Flink Table API from→executeInsert（Java），无内嵌 SQL 字符串线索。"""
    r, w = ctx.tables(2)
    return Sample(
        content=(f'tableEnv.from("{r}").executeInsert("{w}");\n'),
        reads=[{"table": r, "columns": None}], writes=[{"table": w, "columns": None}])


TRAIN_TEMPLATES: list[Template] = [
    Template("py-spark-sql-inline", "PYTHON", t_py_spark_sql_inline),
    Template("py-sql-var-indirect", "PYTHON", t_py_sql_var_indirect),
    Template("py-cursor-execute", "PYTHON", t_py_cursor_execute),
    Template("py-cursor-select", "PYTHON", t_py_cursor_select),
    Template("py-multi-statement", "PYTHON", t_py_multi_statement),
    Template("py-read-save-table", "PYTHON", t_py_read_save_table),
    Template("py-pandas-roundtrip", "PYTHON", t_py_pandas_roundtrip),
    Template("py-to-sql-columns", "PYTHON", t_py_to_sql_columns),
    Template("py-insert-into", "PYTHON", t_py_insert_into),
    Template("py-subprocess-hive", "PYTHON", t_py_subprocess_hive),
    Template("py-dynamic-fstring", "PYTHON", t_py_dynamic_fstring),
    Template("py-commented-sql", "PYTHON", t_py_commented_sql),
    Template("py-pure-compute", "PYTHON", t_py_pure_compute),
    Template("py-logged-not-executed", "PYTHON", t_py_logged_sql_not_executed),
    Template("sh-hive-e", "SHELL", t_sh_hive_e),
    Template("sh-psql-c", "SHELL", t_sh_psql_c),
    Template("sh-heredoc", "SHELL", t_sh_heredoc),
    Template("sh-sqoop-export", "SHELL", t_sh_sqoop_export),
    Template("sh-sqoop-import", "SHELL", t_sh_sqoop_import),
    Template("sh-beeline", "SHELL", t_sh_beeline),
    Template("sh-mysql-e", "SHELL", t_sh_mysql_e),
    Template("sh-spark-sql-e", "SHELL", t_sh_spark_sql_e),
    Template("sh-dynamic-var", "SHELL", t_sh_dynamic_var),
    Template("sh-echo-only", "SHELL", t_sh_echo_only),
    Template("py-wrapper-verb", "PYTHON", t_py_wrapper_verb, rule_covered=False, form_family="wrapper"),
    Template("py-pyspark-saveastable", "PYTHON", t_py_pyspark_saveastable, form_family="chain"),
    Template("py-sqlalchemy-orm", "PYTHON", t_py_sqlalchemy_orm, form_family="orm"),
    Template("sh-presto", "SHELL", t_sh_presto, form_family="cli"),
    Template("sh-sqlplus", "SHELL", t_sh_sqlplus, form_family="cli"),
    Template("py-pandas-sql", "PYTHON", t_py_pandas_sql, form_family="chain"),
    Template("sh-dbt-run", "SHELL", t_sh_dbt_run, rule_covered=False, form_family="config"),
    # JVM 作业（Scala / Java：Spark + Flink）——拓宽语言谱系至 C-下半
    Template("scala-spark-sql", "SCALA", t_scala_spark_sql, form_family="jvm"),
    Template("scala-save-table", "SCALA", t_scala_save_table, form_family="jvm"),
    Template("scala-write-to", "SCALA", t_scala_write_to, form_family="jvm"),
    Template("scala-flink-sql", "SCALA", t_scala_flink_sql, form_family="jvm"),
    Template("scala-dynamic-interp", "SCALA", t_scala_dynamic_interp, form_family="jvm"),
    Template("scala-commented-sql", "SCALA", t_scala_commented_sql, form_family="jvm"),
    Template("java-spark-sql", "JAVA", t_java_spark_sql, form_family="jvm"),
    Template("java-save-table", "JAVA", t_java_save_table, form_family="jvm"),
    Template("java-flink-sql", "JAVA", t_java_flink_sql, form_family="jvm"),
    Template("java-dynamic-concat", "JAVA", t_java_dynamic_concat, form_family="jvm"),
]

HELDOUT_TEMPLATES: list[Template] = [
    Template("h-py-kwarg-table", "PYTHON", h_py_kwarg_table, rule_covered=False),
    Template("h-py-sqlalchemy-text", "PYTHON", h_py_sqlalchemy_text, rule_covered=False),
    Template("h-py-spark-table-alias", "PYTHON", h_py_spark_table_alias, rule_covered=False),
    Template("h-sh-impala", "SHELL", h_sh_impala, rule_covered=False),
    Template("h-sh-clickhouse", "SHELL", h_sh_clickhouse, rule_covered=False),
    Template("h-py-orm-chain-dsl", "PYTHON", h_py_orm_chain_dsl, rule_covered=False, form_family="orm"),
    Template("h-py-decorator-pipeline", "PYTHON", h_py_decorator_pipeline, rule_covered=False, form_family="decorator"),
    Template("h-sh-dbt-run", "SHELL", h_sh_dbt_run, rule_covered=False, form_family="config"),
    Template("h-scala-dataset-chain", "SCALA", h_scala_dataset_chain, rule_covered=False, form_family="jvm"),
    Template("h-java-flink-from-insert", "JAVA", h_java_flink_from_insert, rule_covered=False, form_family="jvm"),
]
