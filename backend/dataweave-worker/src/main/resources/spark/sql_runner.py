#!/usr/bin/env python3
"""
Weft Spark SQL runner (decision A1 / contracts C2).

Reads a .sql file (argv[1]), splits by semicolons, runs spark.sql() per statement.
Prints diagnostic line per statement; exits non-zero on any failure.

Usage (called by SparkTaskExecutor):
  spark-submit [confs] sql_runner.py <body.sql>
"""
import sys
from pyspark.sql import SparkSession

if len(sys.argv) < 2:
    print("Usage: spark-submit sql_runner.py <body.sql>", file=sys.stderr)
    sys.exit(1)

sql_path = sys.argv[1]

try:
    with open(sql_path, "r") as f:
        content = f.read()
except Exception as e:
    print(f"Failed to read SQL file: {e}", file=sys.stderr)
    sys.exit(2)

spark = SparkSession.builder.appName("weft-spark-sql").getOrCreate()

# Naive semicolon-split (same approach as SqlTaskExecutor.splitStatements).
# Complex SQL with semicolons inside string literals may split incorrectly;
# future: use a SQL parser.
raw = [s.strip() for s in content.split(";")]
statements = [s for s in raw if s]

if not statements:
    print("No SQL statements found in input file")
    sys.exit(0)

for i, stmt in enumerate(statements, 1):
    try:
        spark.sql(stmt)
        print(f"Statement {i}/{len(statements)} executed OK")
    except Exception as e:
        print(f"Statement {i}/{len(statements)} failed: {e}", file=sys.stderr)
        sys.exit(3)

print(f"All {len(statements)} statements executed successfully")
spark.stop()
sys.exit(0)
