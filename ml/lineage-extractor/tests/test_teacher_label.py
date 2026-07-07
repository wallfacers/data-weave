"""T011: 双 teacher 打标器单测（缓存/续跑/error 不污染）。用假 client 计调用次数。"""
import json

from realeval.teacher_label import infer_task_type, run_labeling


class FakeClient:
    def __init__(self, reads=None, writes=None, error=None):
        self.calls = 0
        self._reads, self._writes, self._error = reads or [], writes or [], error

    def extract(self, task_type, content):
        self.calls += 1
        out = {"reads": self._reads, "writes": self._writes}
        if self._error:
            out["_error"] = self._error
        return out


def _mk_pool(tmp_path, n=3):
    d = tmp_path / "pool"
    d.mkdir()
    for i in range(n):
        (d / f"c{i}.json").write_text(json.dumps(
            {"content": f"SELECT * FROM t{i}", "source": "{'path': 'job.py'}", "meta": {}}),
            encoding="utf-8")
    return d


def test_labels_every_candidate(tmp_path):
    pool = _mk_pool(tmp_path, 3)
    m1 = FakeClient(reads=[{"table": "t"}])
    stats = run_labeling(pool, tmp_path / "out", {"m1": m1}, resume=True)
    assert stats["new"] == 3
    assert m1.calls == 3
    lines = (tmp_path / "out" / "m1.jsonl").read_text().splitlines()
    assert len(lines) == 3


def test_resume_skips_cached(tmp_path):
    pool = _mk_pool(tmp_path, 3)
    m1 = FakeClient(reads=[{"table": "t"}])
    run_labeling(pool, tmp_path / "out", {"m1": m1}, resume=True)
    assert m1.calls == 3
    # 二次跑：全命中缓存，零新增调用
    m1b = FakeClient(reads=[{"table": "t"}])
    stats = run_labeling(pool, tmp_path / "out", {"m1": m1b}, resume=True)
    assert stats["new"] == 0
    assert stats["skipped"] == 3
    assert m1b.calls == 0


def test_error_recorded_not_dropped(tmp_path):
    pool = _mk_pool(tmp_path, 1)
    m1 = FakeClient(error="call:timeout")
    run_labeling(pool, tmp_path / "out", {"m1": m1}, resume=True)
    rec = json.loads((tmp_path / "out" / "m1.jsonl").read_text().splitlines()[0])
    assert rec["error"] == "call:timeout"  # error 带字段照写，供 build_silver 剔除


def test_two_teachers_separate_files(tmp_path):
    pool = _mk_pool(tmp_path, 2)
    stats = run_labeling(pool, tmp_path / "out",
                         {"m1": FakeClient(), "m2": FakeClient()}, resume=True)
    assert stats["new"] == 4
    assert (tmp_path / "out" / "m1.jsonl").exists()
    assert (tmp_path / "out" / "m2.jsonl").exists()


def test_infer_task_type():
    assert infer_task_type("{'path': 'x.scala'}") == "SCALA"
    assert infer_task_type("{'path': 'x.sh'}") == "SHELL"
    assert infer_task_type("{'path': 'x.py'}") == "PYTHON"
