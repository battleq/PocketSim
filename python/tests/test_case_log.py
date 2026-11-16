# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""案例埋点（pocketsim.case_log + bridge 集成）测试。

验证：A-S 列字段对齐、失败模式 ID 提取（正则+关键词）、类别/严重度推断、
JSONL 写入、案例编号递增、bridge.run_solid_generic 成功/失败自动埋点、
log_case_context 校验阶段埋点。
"""
from __future__ import annotations

import json
import os
import tempfile

import pytest

from pocketsim import case_log, bridge


# --------------------------------------------------------- A-S 列 schema
def test_case_columns_match_excel_a_to_s():
    """A-S 共 19 列，列字母连续 A..S，字段 key 唯一。"""
    cols = case_log.CASE_COLUMNS
    assert len(cols) == 19
    letters = [c["col"] for c in cols]
    expected = [chr(ord("A") + i) for i in range(19)]
    assert letters == expected
    keys = [c["key"] for c in cols]
    assert len(set(keys)) == 19


def test_build_record_keys_are_exactly_a_s_fields():
    """build_record 只输出 A-S 白名单字段，不多不少。"""
    rec = case_log.build_record(user_text="测试")
    assert list(rec.keys()) == case_log.CASE_FIELD_KEYS


# --------------------------------------------------------- 失败模式提取
@pytest.mark.parametrize("msg,expected", [
    ("加载面不在边界上（L-01）", "L-01"),
    ("压力方向错误 BCL-03", "BCL-03"),
    ("约束不足导致机构 L-05", "L-05"),
    ("无模式ID的普通错误", ""),
    ("", ""),
])
def test_failure_mode_regex_extract(msg, expected):
    assert case_log.failure_mode_from_error(msg) == expected


def test_failure_mode_keyword_fallback():
    """无显式 ID 时按关键词推断。"""
    assert case_log.failure_mode_from_error("加载面 x=0.5 不在实体边界上") == "L-01"
    assert case_log.failure_mode_from_error("加载面与约束面重合") == "BCL-03"
    assert case_log.failure_mode_from_error("结构可能为机构或约束不足") == "L-05"
    assert case_log.failure_mode_from_error("pressure_dir 只能是 in 或 out") == "L-02"
    assert case_log.failure_mode_from_error("风速 0.1 m/s 超出范围") == "L-03"


def test_category_and_severity_inference():
    assert case_log.category_from("加载面不在边界", "L-01") == "荷载施加"
    assert case_log.category_from("与约束面重合", "BCL-03") == "边界条件"
    assert case_log.severity_from("", ok=True) == "OK"
    assert case_log.severity_from("结构为机构", ok=False) == "S"
    assert case_log.severity_from("超出范围", ok=False) == "M"


# --------------------------------------------------------- JSONL 写入
def test_append_case_log_writes_one_json_line():
    d = tempfile.mkdtemp()
    rec = case_log.build_record(user_text="钢板受压", session_id="s1")
    path = case_log.append_case_log(d, rec)
    assert os.path.basename(path) == "case_log.jsonl"
    lines = open(path, encoding="utf-8").read().strip().splitlines()
    assert len(lines) == 1
    obj = json.loads(lines[0])
    assert obj["user_text"] == "钢板受压"
    assert obj["session_id"] == "s1"
    # 只含 A-S 字段
    assert set(obj.keys()) == set(case_log.CASE_FIELD_KEYS)


def test_case_id_increments_same_day():
    d = tempfile.mkdtemp()
    r1 = case_log.build_record(user_text="a", data_dir=d)
    case_log.append_case_log(d, r1)
    r2 = case_log.build_record(user_text="b", data_dir=d)
    case_log.append_case_log(d, r2)
    lines = [json.loads(l) for l in open(case_log.case_log_path(d), encoding="utf-8")]
    assert lines[0]["case_id"].endswith("-001")
    assert lines[1]["case_id"].endswith("-002")


def test_append_requires_data_dir():
    with pytest.raises(ValueError, match="data_dir"):
        case_log.append_case_log("", {})


# --------------------------------------------------------- bridge 自动埋点
def _box_model_json(**over):
    model = {
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": 1.0, "height_m": 0.3, "thickness_m": 0.3},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "x", "value": 0.0, "dofs": "fixed"}],
        "loads": [{"type": "pressure", "face": {"axis": "x", "value": 1.0}, "magnitude": 1e5}],
        "mesh_level": "coarse",
    }
    model.update(over)
    return json.dumps(model, ensure_ascii=False)


def test_run_solid_generic_auto_logs_success():
    d = tempfile.mkdtemp()
    model = _box_model_json(_case_meta={"user_text": "钢板受压", "session_id": "sess-ok", "input_form": "text"})
    bridge.run_solid_generic(model, data_dir=d, case_log_enabled="1")
    lines = [json.loads(l) for l in open(case_log.case_log_path(d), encoding="utf-8")]
    assert len(lines) == 1
    rec = lines[0]
    assert rec["user_text"] == "钢板受压"
    assert rec["session_id"] == "sess-ok"
    assert rec["severity"] == "OK"
    assert rec["final_status"] == "通过"
    assert rec["remarks"] == "stage=solve"


def test_run_solid_generic_auto_logs_failure():
    d = tempfile.mkdtemp()
    bad = json.loads(_box_model_json())
    bad["supports"] = [{"type": "plane", "axis": "x", "value": 0.0, "dofs": [0]}]
    bad["loads"] = [{"type": "force", "point": [1.0, 0.15, 0.15], "vector": [0.0, -1000.0, 0.0]}]
    bad["_case_meta"] = {"user_text": "坏模型", "session_id": "sess-bad"}
    with pytest.raises(Exception):
        bridge.run_solid_generic(json.dumps(bad), data_dir=d, case_log_enabled="1")
    lines = [json.loads(l) for l in open(case_log.case_log_path(d), encoding="utf-8")]
    assert len(lines) == 1
    rec = lines[0]
    assert rec["failure_mode"] == "L-05"
    assert rec["final_status"] == "失败"
    assert rec["detected"] == "否"  # 求解阶段才暴露


def test_log_case_context_for_validation_failure():
    d = tempfile.mkdtemp()
    ctx = json.dumps({
        "model": {},
        "error": "加载面不在实体边界上",
        "user_text": "用户描述",
        "session_id": "sess-val",
        "input_form": "text",
        "stage": "validate",
        "detected": True,
    })
    bridge.log_case_context(ctx, data_dir=d, case_log_enabled="1")
    lines = [json.loads(l) for l in open(case_log.case_log_path(d), encoding="utf-8")]
    assert len(lines) == 1
    rec = lines[0]
    assert rec["failure_mode"] == "L-01"
    assert rec["detected"] == "是"
    assert rec["category"] == "荷载施加"


def test_build_record_appends_attempts_to_remarks_keeping_a_s_columns():
    """llm_attempts/first_error 追加进 S 列 remarks，A-S 19 列结构不变。"""
    rec = case_log.build_record(
        stage="validate",
        error="[L-01] 加载面不在边界\n请二选一;改正",
        llm_attempts=2,
        first_error="[L-01] 首轮错误\n带换行;和分号",
    )
    assert list(rec.keys()) == case_log.CASE_FIELD_KEYS  # 仍然只有 19 个 key
    remarks = rec["remarks"]
    assert "stage=validate" in remarks
    assert "llm_attempts=2" in remarks
    # 换行被空格替换、半角分号被全角替换（不破坏 remarks 的 ; 分隔）
    assert "first_error=[L-01] 首轮错误 带换行；和分号" in remarks
    assert "\n" not in remarks
    assert ";" not in remarks.split("first_error=", 1)[1]


def test_build_record_without_attempts_remarks_stays_stage_only():
    """求解阶段不带重试统计时，remarks 保持 stage=solve（向后兼容）。"""
    rec = case_log.build_record(stage="solve")
    assert rec["remarks"] == "stage=solve"


def test_log_case_context_records_llm_attempts():
    """bridge 透传 llm_attempts/first_error 到 case_log.jsonl。"""
    d = tempfile.mkdtemp()
    ctx = json.dumps({
        "model": {},
        "error": "[L-05] 约束不足（重试后仍失败）",
        "user_text": "一块板",
        "session_id": "sess-retry",
        "input_form": "text",
        "stage": "validate",
        "detected": True,
        "llm_attempts": 3,
        "first_error": "[L-01] 第 1 个压力加载面 不在实体边界上",
    })
    bridge.log_case_context(ctx, data_dir=d, case_log_enabled="1")
    lines = [json.loads(l) for l in open(case_log.case_log_path(d), encoding="utf-8")]
    remarks = lines[0]["remarks"]
    assert "llm_attempts=3" in remarks
    assert "first_error=[L-01]" in remarks


def test_log_case_context_tolerates_bad_attempts_value():
    """llm_attempts 非数字时不崩溃，按缺省处理。"""
    d = tempfile.mkdtemp()
    ctx = json.dumps({
        "error": "约束不足", "user_text": "u", "stage": "validate",
        "llm_attempts": "abc",
    })
    bridge.log_case_context(ctx, data_dir=d, case_log_enabled="1")
    lines = [json.loads(l) for l in open(case_log.case_log_path(d), encoding="utf-8")]
    assert "llm_attempts=" not in lines[0]["remarks"]


def test_case_meta_not_leaked_to_solver():
    """_case_meta 应被 bridge 弹出，不传入求解器（避免污染模型）；埋点关闭也能正常求解。"""
    d = tempfile.mkdtemp()
    model = _box_model_json(_case_meta={"user_text": "x", "session_id": "y"})
    result = json.loads(bridge.run_solid_generic(model, data_dir=d))
    assert result["ok"] is True


# --------------------------------------------------------- 埋点开关（正式包不写文件）
def test_run_solid_generic_logging_disabled_by_default():
    """正式包：不传/传 "0" 时求解成功也不产生 case_log.jsonl。"""
    d = tempfile.mkdtemp()
    model = _box_model_json(_case_meta={"user_text": "正式包用户", "session_id": "rel"})
    result = json.loads(bridge.run_solid_generic(model, data_dir=d))
    assert result["ok"] is True
    assert not os.path.exists(case_log.case_log_path(d)), "默认（正式包）不应写 case_log.jsonl"


def test_run_solid_generic_logging_disabled_on_failure():
    """正式包：求解失败照常抛异常，但不写任何案例文件。"""
    d = tempfile.mkdtemp()
    bad = json.loads(_box_model_json())
    bad["supports"] = [{"type": "plane", "axis": "x", "value": 0.0, "dofs": [0]}]
    bad["loads"] = [{"type": "force", "point": [1.0, 0.15, 0.15], "vector": [0.0, -1000.0, 0.0]}]
    with pytest.raises(Exception):
        bridge.run_solid_generic(json.dumps(bad), data_dir=d, case_log_enabled="0")
    assert not os.path.exists(case_log.case_log_path(d)), "正式包失败也不应写 case_log.jsonl"


def test_log_case_context_disabled_returns_empty():
    """正式包：校验阶段埋点入口直接返回空串、不建文件。"""
    d = tempfile.mkdtemp()
    ctx = json.dumps({"error": "加载面不在边界", "user_text": "u"})
    # 默认关闭
    assert bridge.log_case_context(ctx, data_dir=d) == ""
    # 显式关闭
    assert bridge.log_case_context(ctx, data_dir=d, case_log_enabled="0") == ""
    assert not os.path.exists(case_log.case_log_path(d))


# --------------------------------------------------------- Excel 导出
def _seed_records(d, n=2):
    """往 case_log.jsonl 写入 n 条记录，返回记录列表。"""
    recs = []
    for i in range(n):
        r = case_log.build_record(
            model={"geometry": {"type": "box"}, "material": "钢"},
            user_text=f"用户描述{i}",
            session_id=f"sess-{i}",
            error="加载面不在实体边界上" if i == 1 else None,
            data_dir=d,
        )
        case_log.append_case_log(d, r)
        recs.append(r)
    return recs


def test_export_to_excel_headers_match_a_s():
    """导出的 xlsx 第 1 行表头等于 CASE_COLUMNS 的 label，共 19 列。"""
    d = tempfile.mkdtemp()
    _seed_records(d)
    out = case_log.export_to_excel(d)
    from openpyxl import load_workbook
    wb = load_workbook(out)
    ws = wb.active
    headers = [cell.value for cell in ws[1]]
    assert headers == [c["label"] for c in case_log.CASE_COLUMNS]
    assert len(headers) == 19


def test_export_to_excel_rows_align_with_columns():
    """数据行按 CASE_FIELD_KEYS 顺序取值，与表头列一一对应。"""
    d = tempfile.mkdtemp()
    recs = _seed_records(d, n=3)
    out = case_log.export_to_excel(d)
    from openpyxl import load_workbook
    ws = load_workbook(out).active
    # 3 条记录 → 第 2~4 行
    for row_idx, rec in enumerate(recs, start=2):
        for col_idx, key in enumerate(case_log.CASE_FIELD_KEYS, start=1):
            expected = "" if rec.get(key) is None else str(rec.get(key))
            actual = ws.cell(row=row_idx, column=col_idx).value or ""
            assert actual == expected, (
                f"行{row_idx} 列{col_idx}({key}) 不匹配：{actual!r} != {expected!r}"
            )


def test_export_handles_missing_and_none_fields():
    """记录缺字段或 None 时，对应单元格为空串。"""
    d = tempfile.mkdtemp()
    # 手动写一条缺字段的 JSONL
    with open(case_log.case_log_path(d), "w", encoding="utf-8") as f:
        f.write(json.dumps({"case_id": "FC-1", "user_text": None}) + "\n")
    out = case_log.export_to_excel(d)
    from openpyxl import load_workbook
    ws = load_workbook(out).active
    # A 列=案例编号，D 列=用户原话（None→空）
    assert ws.cell(row=2, column=1).value == "FC-1"
    assert ws.cell(row=2, column=4).value in (None, "")


def test_export_empty_log_still_has_headers():
    """无记录时导出仍含表头行。"""
    d = tempfile.mkdtemp()
    out = case_log.export_to_excel(d)
    from openpyxl import load_workbook
    ws = load_workbook(out).active
    assert ws.max_row == 1
    assert ws.cell(row=1, column=1).value == "案例编号"
    assert ws.cell(row=1, column=19).value == "备注"


def test_bridge_export_case_log_excel():
    d = tempfile.mkdtemp()
    _seed_records(d)
    out = bridge.export_case_log_excel(data_dir=d)
    assert os.path.exists(out)
    assert out.endswith("case_log.xlsx")
