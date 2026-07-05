"""041-R JVM 扩语言：把 JVM 真实候选人工证伪裁决落成独立金标 real-jvm.jsonl。

与 apply_adjudication.py 同构，但：
  - 输入 = tolabel-jvm（Scala/Java 候选），输出 = realeval/gold/real-jvm.jsonl（独立文件，
    **不并入** py/sh 的 real.jsonl，以免污染已冻结的 n=139 负结果数字）；
  - JVM_OVERRIDES = 我对 60 条 need_manual 逐条按约定 A 证伪的人工修正（论文 provenance）。
    key=source.path；同路径多文件（JavaSQLDataSourceExample.java ×3 / QuizManager.java ×2）
    给**一致保守裁决**，故 path 键无歧义。
  - task_type 沿用 tolabel 记录（.scala→SCALA / .java→JAVA），保证预标/评测 prompt 一致。

约定 A 在 JVM 上新识别的**非表**类别（均剔，见 ADJUDICATION.md）：
  变量/常量/参数名（val x="t" 后按变量用）、类/trait/包名、jOOQ 生成的
  `import static ...Tables.X` 常量、CLI 帮助注释里的名字、CREATE VIEW/DATABASE、
  临时视图（createTemporaryView/global_temp）、JDBC 连接串、csv/text 落盘文件夹路径。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from realeval.adjudicate_aid import adjudicate
from realeval.apply_adjudication import _rows

# path -> (reads, writes, 依据)。空列表 = 判 ∅。
JVM_OVERRIDES: dict[str, tuple[list[str], list[str], str]] = {
    "spark-plugin/example_3_5_1/src/main/scala/io/dataflint/example/IcebergExample.scala": (
        [], ["demo.nyc.taxis", "demo.nyc.taxis_read_on_merge", "demo.nyc.taxis_small_files",
             "demo.nyc.taxis_unpartitioned"],
        "四张 Iceberg 表均 spark.sql CREATE/DROP/ALTER + writeTo 字面串写目标；无确证 SELECT FROM 故仅写"),
    "prost-loader/src/main/java/loader/PropertyTableLoader.java": (
        [], [], "TRIPLETABLE_NAME/ptTableName 是 Java 常量/字段（String.format 参数），非字面串表"),
    "src/datasource-service/src/main/java/org/apache/kylin/rest/service/SparkSourceService.java": (
        ["ssb.lineorder"], [], "TABLE_LINEORDER=\"ssb.lineorder\" 字面读；P_LINEORDER 是 create view（视图非表）"),
    "bpmnai-core/src/main/java/de/viadee/bpmnai/core/processing/steps/output/WriteToDiscStep.java": (
        [], [], "\"result\" 是 writeDatasetToParquet 文件名；RESULT_PREVIEW_TEMP_TABLE 是常量且名为 temp_table"),
    "manager/QuizManager.java": (
        [], ["quiz_items", "quizzes"],
        "CREATE TABLE IF NOT EXISTS QUIZ_ITEMS/QUIZZES + insertInto 为写（同路径两仓 sherwinokhowat/rickyqin005 裁决一致）"),
    "flink/src/main/scala/com/dahuatech/flink/demo/Demo.scala": (
        [], ["demo_output", "stu"],
        "demo_output=create table+executeInsert、stu=INSERT INTO stu VALUES 为写；demo 是 package 名剔"),
    "tdvt_workspace/notebooks/testv1-dataset.scala": (
        [], ["calcs"],
        "saveAsTable(\"Calcs\") 字面写；Staples 是 createOrReplaceTempView、Tableau_TDVT 是 CREATE DATABASE，剔"),
    "src/main/scala/cognite/spark/v1/FilesRelation.scala": (
        [], [], "files 来自 class FilesRelation/Files SDK 类名，非字面表"),
    "prost-loader/src/main/java/loader/EmergentSchemaLoader.java": (
        ["wide_property_table"], [],
        "WPT_NAME=\"wide_property_table\" 双模型一致读；ES_ 是 TABLE_PREFIX 前缀剔"),
    "src/main/scala/com/tazk/sink/SparkMongoSink.scala": (
        [], [], "collection 是方法参数名（MongoDB collection），非字面表"),
    "plugins/spark/common/src/intTest/java/org/apache/polaris/spark/quarkus/it/SparkDeltaIT.java": (
        [], [], "deltatb/deltatb1/deltatb2 均 String x=\"...\" 集成测试变量，非可执行语句字面表"),
    "cdc-paimon-sync/src/main/java/io/sophiadata/flink/paimon/mongo/MongoToPaimonPipeline.java": (
        [], [], "source_db 仅出现在 `--mongo.database source_db` CLI 帮助注释"),
    "app/daos/UserDao.java": (
        [], [], "USER/ACCOUNT 是 jOOQ 生成的 import static Tables.* 常量，非字面串表"),
    "spark-2.x/src/main/scala/com/aliyun/odps/spark/examples/sparksql/SparkSQL.scala": (
        [], [], "mc_test_table/mc_test_pt_table 是 val tableName=\"...\" 变量，按变量传入读写非字面"),
    "src/main/scala/cognite/spark/v1/RawTableRelation.scala": (
        [], [], "raw 来自 class RawTableRelation 类名，非字面表"),
    "spark2.3/src/main/scala/splice/SpliceRelation.scala": (
        [], [], "opts.table 是对象字段，且仅在 println/注释中出现"),
    "airlearner/airlearner-utils/src/main/scala/com/airbnb/common/ml/util/HiveManageTable.scala": (
        [], [], "table 来自 trait/object HiveManageTable，非字面表"),
    "src/main/java/com/packt/sfjd/ch8/DatasetOperations.java": (
        [], ["department"],
        "saveAsTable(\"Department\") 字面写；global_temp.dept_global_view 是 GlobalTempView 剔"),
    "plugin/spark3/spark35/src/main/java/com/vastdata/spark/write/VastWriteFactory.java": (
        [], [], "vastTableMetaData.tableName 是对象字段访问，非字面表"),
    "examples/src/main/java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java": (
        ["schema.tablename"], ["people_bucketed", "schema.tablename"],
        "dbtable=\"schema.tablename\" 字面读写、saveAsTable(\"people_bucketed\") 写；"
        "jdbc 连接串/output(csv 文件夹)/parquet 路径剔（同路径三版 CrestOfWave/Netflix/apache 取保守公共集）"),
    "src/main/java/org/finos/orr/DataLoader.java": (
        [], [], "reportable_event 为非字面幻觉名（自动已弃，显式固化）"),
    "FlinkDemo/src/main/java/com/starrocks/flink/Sql2StarRocksJava.java": (
        [], ["demo2_flink_tb3"],
        "'table-name'='demo2_flink_tb3' 字面写；sourceTable 是 createTemporaryView 临时视图剔"),
    "collect-core/unused/DataPersister.java": (
        [], [], "OFC_DATA_ID_SEQ/OfcData 是 jOOQ 生成 Sequences/Tables 常量，非字面表"),
    "spark-generate-maps/src/main/java/org/gbif/maps/MapBuilder.java": (
        ["occurrence"], ["tim"],
        "FROM occurrence 字面读、.hiveDB(\"tim\").hbaseTable(\"tim\") 字面写；maps_input_tiles_312495 幻觉剔"),
    "spark-3.x/src/main/scala/com/aliyun/odps/spark/examples/sparksql/SparkSQL.scala": (
        [], [], "同 2.x：val 变量名非字面表"),
    "linkis-engineconn-plugins/flink/flink-core/src/main/java/org/apache/linkis/engineconnplugin/flink/client/sql/operation/impl/InsertOperation.java": (
        [], [], "tableIdentifier 是方法参数名，非字面表"),
    "iql-engine/src/main/java/iql/engine/adaptor/SaveAdaptor.scala": (
        [], [], "s.getText 是方法调用、final_path 是 var，非字面表"),
    "src/main/database/Database.java": (
        [], [], "全部来自 jOOQ 生成 import static Tables.* 常量，非字面串表"),
    "spark-1.x/src/main/scala/com/aliyun/odps/spark/examples/sparksql/SparkSQL.scala": (
        [], [], "同 2.x/3.x：val 变量名非字面表"),
    "app/services/entity/AuthorityFileService.scala": (
        [], [], "AUTHORITY_FILE 是 jOOQ 生成 Tables.* 常量，非字面串表"),
    "src/main/scala/rest/RestRelation.scala": (
        [], [], "inputs 是 private val，且在 s\"...$inputs\" 中动态拼接"),
    "src/dao/impl/ProductDataDaoImpl.java": (
        [], [], "productdata 来自 class ProductData/model 类名，非字面表"),
    "src/dao/impl/CustomerDaoImpl.java": (
        [], [], "customerdata 来自 CustomerData model 类名，非字面表"),
    "consumers/spark/src/main/scala/com/oath/vdms/vflow/consumer/spark/driver/IngestStream.scala": (
        [], [], "tablename=args(4) 是运行时参数变量，非字面表"),
    "benchmark/notebooks/PrepareSource.scala": (
        [], [], "sourceTable=dbutils.widgets.get(...) 是 widget 变量，非字面表"),
    "21-BucketJoinDemo/src/main/scala/BucketJoinDemo.scala": (
        ["my_db.flight_data1", "my_db.flight_data2"],
        ["my_db.flight_data1", "my_db.flight_data2"],
        "saveAsTable(\"MY_DB.flight_dataN\") 写 + spark.read.table(\"MY_DB.flight_dataN\") 读，字面自环"),
}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tolabel", default="realeval/tolabel-jvm")
    ap.add_argument("--out", default="realeval/gold/real-jvm.jsonl")
    args = ap.parse_args(argv)

    idx = json.loads((Path(args.tolabel) / "_index.json").read_text(encoding="utf-8"))
    added, applied_overrides, unknown_overrides = [], 0, set(JVM_OVERRIDES)
    for e in idx:
        rec = json.loads((Path(args.tolabel) / e["filename"]).read_text(encoding="utf-8"))
        a = adjudicate(rec)
        path = a["path"]
        if path in JVM_OVERRIDES:
            r, w, _why = JVM_OVERRIDES[path]
            applied_overrides += 1
            unknown_overrides.discard(path)
        else:
            r, w = a["proposal"]["reads"], a["proposal"]["writes"]
        reads, writes = _rows(r, w)
        added.append({
            "content": rec["content"],
            "task_type": rec.get("task_type", "SCALA"),
            "labels": {"reads": reads, "writes": writes},
            "meta": {
                "template_id": path,
                "form_family": "real",
                "source_dataset": "real",
                "rule_covered": True,
                "literal_density": a["density"],
                "needs_review": False,
            },
        })

    if unknown_overrides:
        print(f"[warn] {len(unknown_overrides)} 条 OVERRIDE 未匹配：{sorted(unknown_overrides)}")

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in added) + "\n",
                              encoding="utf-8")
    ne = sum(1 for r in added if r["labels"]["reads"] or r["labels"]["writes"])
    import collections
    tt = collections.Counter(r["task_type"] for r in added)
    print(f"JVM 金标 {len(added)}（非空 {ne} / ∅ {len(added) - ne}，套用 override {applied_overrides}）"
          f" task_type={dict(tt)} → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
