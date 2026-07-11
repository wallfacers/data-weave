"""065 T010（US3）：benchmark 清单单测——无源码正文/无合成泄漏/凭据无关/列级 null。"""
from __future__ import annotations

import pytest

from benchmark.build_manifest import build_manifest, validate_manifest
from benchmark.fetch import source_url


def _row(reads, writes, **kw):
    return dict(labels={"reads": [{"table": t} for t in reads],
                        "writes": [{"table": t} for t in writes]},
                content="insert into x select * from y",  # 源码正文——不得进 manifest
                **kw)


def _gold():
    return [_row(["ods_o"], ["dwd_o"], repo="a/b", commit="c" * 40,
                 path="etl/load.sql", type="sql", arbitrated=True),
            _row(["ods_e"], ["dwd_s"], repo="a/b", commit="c" * 40,
                 path="jobs/p.py", type="python")]


def test_manifest_valid_and_strips_source_bodies():
    m = build_manifest(_gold(), version="0.1.0")
    validate_manifest(m)  # 不抛即合规
    for rec in m["records"]:
        assert "content" not in rec  # FR-007：无源码正文
    assert m["disclosure"] == {"no_source_bodies": True, "no_synthetic_train": True}
    assert m["credential_free"] is True


def test_columns_are_null_column_level_out_of_scope():
    m = build_manifest(_gold(), version="0.1.0")
    for rec in m["records"]:
        for t in rec["reads"] + rec["writes"]:
            assert t["columns"] is None


def test_subset_classified_per_record():
    m = build_manifest(_gold(), version="0.1.0")
    subs = sorted(r["subset"] for r in m["records"])
    assert subs == ["script", "sql"]


def test_validate_rejects_content_leak():
    m = build_manifest(_gold(), version="0.1.0")
    m["records"][0]["content"] = "leak"  # 注入源码正文
    with pytest.raises(AssertionError):
        validate_manifest(m)


def test_validate_rejects_false_disclosure():
    m = build_manifest(_gold(), version="0.1.0")
    m["disclosure"]["no_synthetic_train"] = False
    with pytest.raises(AssertionError):
        validate_manifest(m)


def test_source_url_construction():
    rec = {"repo": "owner/name", "commit": "abc123", "path": "dir/f.sql"}
    assert source_url(rec) == "https://raw.githubusercontent.com/owner/name/abc123/dir/f.sql"
