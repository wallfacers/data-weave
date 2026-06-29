# sample-spark.py —— PySpark 脚本体（与 sample-spark.task.yaml 同目录同名）
# 本地 dw run：SPARK 数据源解析为 local[*]，spark-submit 提交本文件。
# 未装 Spark 的环境 dw run 呈现 SKIPPED（不报错、不伪装成功）。

from pyspark.sql import SparkSession

spark = SparkSession.builder.appName("sample-spark").getOrCreate()

# 最小示例：构造一个 DataFrame 并统计行数（真实场景读 Hive/JDBC/Parquet）
df = spark.createDataFrame([(1, "a"), (2, "b"), (3, "c")], ["id", "label"])
print("rows =", df.count())
df.show()

spark.stop()
