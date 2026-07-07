"""T006: content-hash 工具单测（去重 + 污染检测 + 空白规范化）。"""
from realeval.hashutil import content_hash, dedup, normalize, overlap


def test_same_content_same_hash():
    assert content_hash("SELECT 1 FROM t") == content_hash("SELECT 1 FROM t")


def test_whitespace_insensitive():
    # 缩进/换行/CRLF 不同但语义同一份 → 同 hash（防脱敏/格式漂移漏检污染）
    a = "line1\n    line2\n\tline3"
    b = "line1\r\nline2   line3"
    assert content_hash(a) == content_hash(b)
    assert normalize("  a\r\nb  ") == "a b"


def test_different_content_different_hash():
    assert content_hash("read from a") != content_hash("write to b")


def test_empty_and_none():
    assert content_hash("") == content_hash(None)


def test_overlap_detects_contamination():
    train = ["job A body", "job B body"]
    test = ["job B  body", "job C body"]  # B 重叠（空白不同）
    ov = overlap(train, test)
    assert len(ov) == 1
    # 无重叠时空集
    assert overlap(["x"], ["y"]) == set()


def test_dedup_keeps_first():
    items = [{"c": "same script"}, {"c": "same   script"}, {"c": "other"}]
    out = dedup(items, key=lambda x: x["c"])
    assert len(out) == 2
    assert out[0]["c"] == "same script"
