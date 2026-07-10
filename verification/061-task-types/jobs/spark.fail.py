"""
Spark 真实失败夹具（pyspark 形态）。
作业自身错误（除零异常）→ 非 0 退出码 + Spark 原生错误栈。
与「缺 SPARK_HOME → 已跳过」明确可辨。
"""
import sys
from pyspark.sql import SparkSession

print("[SPARK_FAIL] 启动 SparkSession...")
spark = SparkSession.builder \
    .appName("dw061-spark-fail") \
    .getOrCreate()

try:
    print(f"[SPARK_FAIL] master={spark.sparkContext.master}")

    # 构造作业自身错误：对空 DataFrame 做运算后触发除零
    df = spark.range(0)  # 空 DataFrame
    cnt = df.count()
    print(f"[SPARK_FAIL] 空 df count={cnt}")

    # 故意除零（作业自身错，非引擎缺位）
    result = 1 / cnt
    print(f"[SPARK_FAIL] 不该到这里: 1/0={result}")
    spark.stop()
    sys.exit(0)  # 不应成功
except ZeroDivisionError as e:
    print(f"[SPARK_FAIL] 预期真失败: {e}", file=sys.stderr)
    try:
        spark.stop()
    except Exception:
        pass
    sys.exit(1)  # 真实作业失败退出码
except Exception as e:
    print(f"[SPARK_FAIL] 非预期异常: {e}", file=sys.stderr)
    try:
        spark.stop()
    except Exception:
        pass
    sys.exit(2)
