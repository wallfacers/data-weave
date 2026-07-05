from data.augment import paraphrase_script


def test_reject_when_table_dropped(monkeypatch):
    # 改写结果丢了表 ods.a → 必须返回 None
    from data import augment
    monkeypatch.setattr(augment, "_rewrite", lambda content: "df = load('other')")
    assert paraphrase_script("x ods.a y", {"ods.a"}) is None


def test_accept_when_tables_preserved(monkeypatch):
    from data import augment
    monkeypatch.setattr(augment, "_rewrite", lambda content: "frame = fetch('ods.a')")
    out = paraphrase_script("x ods.a", {"ods.a"})
    assert out and "ods.a" in out


def test_reject_when_table_only_substring_matched(monkeypatch):
    # ods.a 被改写成 ods.a_v2 → 只是子串命中，不能算保留，必须拒绝
    from data import augment
    monkeypatch.setattr(augment, "_rewrite", lambda content: "df = load('ods.a_v2')")
    assert paraphrase_script("x ods.a y", {"ods.a"}) is None
