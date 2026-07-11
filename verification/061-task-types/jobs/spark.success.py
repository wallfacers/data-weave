"""
Spark 真跑成功夹具（pyspark 形态）。
最小计算：spark.range(100).count() + df.show()。
连 real Spark（local[*] 或 standalone master），退出码 0 即真跑通过。
"""
import sys
from pyspark.sql import SparkSession

print("[SPARK_SUCCESS] 启动 SparkSession...")
spark = SparkSession.builder \
    .appName("dw061-spark-success") \
    .getOrCreate()

try:
    print(f"[SPARK_SUCCESS] master={spark.sparkContext.master}")
    print(f"[SPARK_SUCCESS] version={spark.version}")

    # 最小计算：range → count + show
    df = spark.range(100)
    cnt = df.count()
    print(f"[SPARK_SUCCESS] spark.range(100).count() = {cnt}")
    assert cnt == 100, f"期望 100，实际 {cnt}"

    print("[SPARK_SUCCESS] df.show():")
    df.show(10, truncate=False)

    # 再做一个小聚合验证 Spark 引擎确实在干活
    df2 = spark.range(1, 11).toDF("n")
    result = df2.selectExpr("sum(n) as total").collect()[0]["total"]
    print(f"[SPARK_SUCCESS] sum(1..10) = {result}")
    assert result == 55, f"期望 55，实际 {result}"

    print("[SPARK_SUCCESS] 全部断言通过，真跑成功。")
    spark.stop()
    sys.exit(0)
except Exception as e:
    print(f"[SPARK_SUCCESS] 失败: {e}", file=sys.stderr)
    try:
        spark.stop()
    except Exception:
        pass
    sys.exit(1)
