import random

from data.templates import (Ctx, HELDOUT_TEMPLATES,
                            h_py_orm_chain_dsl, h_py_decorator_pipeline, h_sh_dbt_run)


def _ctx():
    return Ctx(random.Random(3), ["ods.x", "dwd.y", "dws.z"], ["id", "v", "dt"])


def test_new_heldout_forms_valid():
    for fn in (h_py_orm_chain_dsl, h_py_decorator_pipeline, h_sh_dbt_run):
        s = fn(_ctx())
        rt = {r["table"] for r in s.reads}; wt = {w["table"] for w in s.writes}
        assert rt and wt and not (rt & wt)


def test_custom_wrapper_removed_from_heldout():
    ids = {t.template_id for t in HELDOUT_TEMPLATES}
    assert "h-py-custom-wrapper" not in ids  # 已移入训练集，避免泄漏
