import random

from data.templates import TRAIN_TEMPLATES
from data.synth_pipeline import render


def test_render_carries_form_family():
    rng = random.Random(5)
    tpl = TRAIN_TEMPLATES[0]
    row = render(rng, tpl, ["ods.a", "dws.b", "ads.c"], ["id", "v"], "train")
    assert "form_family" in row["meta"]
    assert row["meta"]["form_family"]  # 非空
