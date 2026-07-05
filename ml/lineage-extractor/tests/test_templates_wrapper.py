import random
from data.templates import Ctx, t_py_wrapper_verb, READ_VERBS, WRITE_VERBS


def _ctx():
    return Ctx(random.Random(1), ["ods.a", "dws.b", "ads.c"], ["id", "amt", "dt"])


def test_wrapper_read_verb_goes_to_reads():
    s = t_py_wrapper_verb(_ctx())
    read_tables = {r["table"] for r in s.reads}
    write_tables = {w["table"] for w in s.writes}
    # 读表与写表不重叠，且都真实出现在脚本文本
    assert read_tables and write_tables
    assert not (read_tables & write_tables)
    for t in read_tables | write_tables:
        assert t in s.content


def test_verb_pools_disjoint():
    assert not (set(READ_VERBS) & set(WRITE_VERBS))
