"""样例集评测：离线规则解析器 parse_problem 的模板识别与参数抽取。

验收（对应 spec Task 5）：
- 模板识别正确率 ≥90%（20 条样例至少 18 条正确，逐条断言）；
- 每条样例 expect_params 中的关键参数逐一断言相等（数值容差 1%）；
- 全部输出可通过模板 param_schema 校验（键存在、类型/范围合法）。
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from pocketsim.parser import parse_problem
from pocketsim.templates import TEMPLATES

SAMPLES_PATH = Path(__file__).resolve().parent.parent / "samples.json"


def _load_samples():
    data = json.loads(SAMPLES_PATH.read_text(encoding="utf-8"))
    samples = data["samples"]
    assert len(samples) >= 20, f"样例集至少 20 条，当前 {len(samples)} 条"
    return samples


SAMPLES = _load_samples()
# 各模板样例条数下限（三模板各 ≥6 条）
_TEMPLATE_COUNTS = {}
for s in SAMPLES:
    _TEMPLATE_COUNTS[s["expect_template"]] = _TEMPLATE_COUNTS.get(s["expect_template"], 0) + 1


def test_sample_set_covers_all_templates():
    """样例集覆盖三个模板且各 ≥6 条。"""
    for tid in ("wall_nail", "cantilever_shelf", "simply_supported_panel"):
        assert _TEMPLATE_COUNTS.get(tid, 0) >= 6, (
            f"模板 {tid} 样例不足 6 条，当前 {_TEMPLATE_COUNTS.get(tid, 0)}"
        )


def test_template_recognition_accuracy():
    """模板识别正确率 ≥90%（20 条至少 18 对，不模糊断言）。"""
    correct = 0
    failures = []
    for s in SAMPLES:
        outcome = parse_problem(s["text"])
        if outcome.get("ok") and outcome.get("template_id") == s["expect_template"]:
            correct += 1
        else:
            failures.append(
                f"{s['text'][:24]}… 期望 {s['expect_template']}, "
                f"实际 {outcome.get('template_id')!r}"
            )
    assert correct >= 18, f"模板识别仅 {correct}/20 正确：\n" + "\n".join(failures)


@pytest.mark.parametrize("sample", SAMPLES, ids=lambda s: s["text"][:18])
def test_expected_params_extracted(sample):
    """每条样例的关键参数逐一断言（数值容差 1%）。"""
    outcome = parse_problem(sample["text"])
    assert outcome.get("ok") is True, f"解析失败：{outcome}"
    assert outcome["template_id"] == sample["expect_template"]
    params = outcome["params"]
    for key, expect in sample["expect_params"].items():
        assert key in params, f"缺少参数 {key}：解析结果 {params}"
        got = params[key]
        if isinstance(expect, (int, float)) and isinstance(got, (int, float)):
            assert got == pytest.approx(expect, rel=0.01), (
                f"参数 {key} 期望 {expect}，实际 {got}"
            )
        else:
            assert got == expect, f"参数 {key} 期望 {expect!r}，实际 {got!r}"


@pytest.mark.parametrize("sample", SAMPLES, ids=lambda s: s["text"][:18])
def test_output_passes_schema_validation(sample):
    """解析输出可通过模板 param_schema 校验（键存在、类型/范围合法）。"""
    outcome = parse_problem(sample["text"])
    assert outcome.get("ok") is True
    template = TEMPLATES[outcome["template_id"]]
    spec_by_key = {spec.key: spec for spec in template.param_schema}
    for key, value in outcome["params"].items():
        assert key in spec_by_key, f"未知参数键 {key}"
        spec = spec_by_key[key]
        if spec.type == "number":
            assert isinstance(value, (int, float)) and not isinstance(value, bool)
            if spec.minimum is not None:
                assert value >= spec.minimum
            if spec.maximum is not None:
                assert value <= spec.maximum
        else:
            assert isinstance(value, str)
    # missing 必须都是该模板的真实必填键
    for key in outcome["missing"]:
        spec = spec_by_key.get(key)
        assert spec is not None and spec.required, f"missing 含非必填键 {key}"


def test_original_nail_case():
    """钉子挂 3kg 原例（spec 验收基准）：识别 wall_nail 且抽出 mass_kg=3。"""
    outcome = parse_problem(
        "在墙上打进一个钉子，钉子上悬挂3KG重物，墙和钉子是否能够承重，变形有多少"
    )
    assert outcome["ok"] is True
    assert outcome["template_id"] == "wall_nail"
    assert outcome["params"]["mass_kg"] == pytest.approx(3.0, rel=0.01)
    assert "mass_kg" not in outcome["missing"]


def test_unrecognized_text_returns_reason():
    """无法识别的问题返回 ok=False 与中文原因。"""
    outcome = parse_problem("今天天气怎么样")
    assert outcome["ok"] is False
    assert outcome.get("reason")
    assert parse_problem("")["ok"] is False
