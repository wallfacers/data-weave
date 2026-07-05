from eval.baselines.regex_baseline import predict
from eval.baselines.llm_baseline import make_predict


def test_regex_catches_insert_select():
    row = {"task_type": "SHELL", "content": 'hive -e "INSERT INTO dws.x SELECT * FROM ods.y"'}
    got = predict(row)
    assert {t["table"] for t in got["writes"]} == {"dws.x"}
    assert {t["table"] for t in got["reads"]} == {"ods.y"}


def test_regex_catches_python_writers():
    row = {
        "task_type": "PYTHON",
        "content": 'df = spark.sql("SELECT * FROM ods.orders JOIN ods.users ON 1=1")\n'
                   'df.write.saveAsTable("dwd.orders_enriched")',
    }
    got = predict(row)
    assert {t["table"] for t in got["writes"]} == {"dwd.orders_enriched"}
    assert {t["table"] for t in got["reads"]} == {"ods.orders", "ods.users"}


def test_regex_no_reads_or_writes():
    row = {"task_type": "SHELL", "content": "echo hello world"}
    got = predict(row)
    assert got == {"reads": [], "writes": []}


def test_llm_baseline_wraps_client_extract():
    calls = []

    class FakeClient:
        def extract(self, task_type, content):
            calls.append((task_type, content))
            return {"reads": [{"table": "ods.a", "columns": None}], "writes": []}

    predict_fn = make_predict(FakeClient())
    row = {"task_type": "PYTHON", "content": "select * from ods.a"}
    got = predict_fn(row)

    assert calls == [("PYTHON", "select * from ods.a")]
    assert got == {"reads": [{"table": "ods.a", "columns": None}], "writes": []}
