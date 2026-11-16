"""材料库测试：加载、别名模糊匹配、upsert 去重与外部副本持久化。"""
import json

import pytest

from pocketsim.i18n import set_locale
from pocketsim.materials import MaterialsLibrary, USER_FILENAME


def test_builtin_library_has_enough_materials():
    """内置材料库 ≥10 种生活常见材料（实际 11 种）。"""
    lib = MaterialsLibrary()
    assert len(lib) >= 10
    ids = {m["id"] for m in lib.all_materials()}
    assert {"low_carbon_steel", "pine", "plywood", "brick_masonry",
            "concrete_c20", "gypsum_board", "glass"} <= ids


def test_find_by_name_and_id():
    """中文名与英文 id 精确查找。"""
    lib = MaterialsLibrary()
    assert lib.find_material("松木")["id"] == "pine"
    assert lib.find_material("low_carbon_steel")["id"] == "low_carbon_steel"
    assert lib.find_material("砖砌体")["id"] == "brick_masonry"


def test_alias_fuzzy_matching():
    """别名精确与双向包含模糊匹配。"""
    lib = MaterialsLibrary()
    assert lib.find_material("钢钉")["id"] == "low_carbon_steel"
    assert lib.find_material("铁钉")["id"] == "low_carbon_steel"
    assert lib.find_material("红砖")["id"] == "brick_masonry"
    assert lib.find_material("木头")["id"] == "pine"
    assert lib.find_material("夹板")["id"] == "plywood"
    # 模糊："松木搁板"包含别名"木搁板"与"松木"，取更长候选，仍命中松木
    assert lib.find_material("松木搁板")["id"] == "pine"
    # 大小写/空格归一化
    assert lib.find_material(" MDF ")["id"] == "mdf"


def test_find_material_not_found():
    """找不到时返回 None（不抛异常）。"""
    lib = MaterialsLibrary()
    assert lib.find_material("航空钛合金xyz123") is None
    assert lib.find_material("") is None
    assert lib.find_material(None) is None


def test_upsert_insert_then_update_by_id(tmp_path):
    """按 id 去重：首次插入、再按 id 更新（条数不变，数值更新）。"""
    lib = MaterialsLibrary(data_dir=str(tmp_path))
    n0 = len(lib)
    record = {
        "name": "航天钛合金",
        "elastic_modulus": 110e9,
        "poisson_ratio": 0.34,
        "density": 4430.0,
        "strength": 900.0,
    }
    rec, action = lib.upsert_material(record, source_label="联网检索")
    assert action == "inserted"
    assert len(lib) == n0 + 1
    assert rec["source"].startswith("联网检索+")   # source 记录"来源+日期"

    rec2, action2 = lib.upsert_material(
        dict(record, id=rec["id"], elastic_modulus=114e9), "联网检索"
    )
    assert action2 == "updated"
    assert len(lib) == n0 + 1                        # 去重：不新增
    found = lib.find_material("航天钛合金")
    assert found["elastic_modulus"] == 114e9


def test_upsert_dedup_by_name_alias(tmp_path):
    """无 id 时按“名称+别名”交集去重：同名材料就地更新并合并别名。"""
    lib = MaterialsLibrary(data_dir=str(tmp_path))
    n0 = len(lib)
    rec, action = lib.upsert_material({
        "name": "低碳钢",
        "aliases": ["structure_steel"],
        "elastic_modulus": 205e9,
        "poisson_ratio": 0.3,
        "density": 7850.0,
        "strength": 235.0,
    })
    assert action == "updated"
    assert len(lib) == n0                            # 未新增条目
    found = lib.find_material("低碳钢")
    assert found["elastic_modulus"] == 205e9
    assert "structure_steel" in found["aliases"]     # 别名已合并
    assert lib.find_material("structure_steel")["id"] == "low_carbon_steel"


def test_upsert_missing_fields_raises(tmp_path):
    """缺必填字段时抛 ValueError。"""
    lib = MaterialsLibrary(data_dir=str(tmp_path))
    with pytest.raises(ValueError):
        lib.upsert_material({"name": "缺参数材料", "elastic_modulus": 1e9})


def test_save_and_reload_external_copy(tmp_path):
    """外部可写副本：保存后重新加载自动与内置库合并。"""
    lib = MaterialsLibrary(data_dir=str(tmp_path))
    lib.upsert_material({
        "name": "碳纤维板",
        "elastic_modulus": 70e9,
        "poisson_ratio": 0.3,
        "density": 1600.0,
        "strength": 600.0,
    }, "联网检索")
    path = lib.save_library()
    assert path.endswith(USER_FILENAME)

    reloaded = MaterialsLibrary(data_dir=str(tmp_path))
    found = reloaded.find_material("碳纤维板")
    assert found is not None and found["strength"] == 600.0
    assert len(reloaded) == len(lib)
    # 内置材料不受影响
    assert reloaded.find_material("松木")["id"] == "pine"


def test_readonly_mode_rejects_save():
    """只读模式（data_dir=None）save_library 抛 PermissionError。"""
    lib = MaterialsLibrary()
    with pytest.raises(PermissionError):
        lib.save_library()


def test_builtin_material_record_fields():
    """材料记录字段完整（单位：Pa / MPa / kg/m³）。"""
    lib = MaterialsLibrary()
    steel = lib.find_material("低碳钢")
    for key in ("id", "name", "aliases", "elastic_modulus", "poisson_ratio",
                "density", "strength", "strength_type", "source", "notes"):
        assert key in steel, f"缺少字段 {key}"
    assert steel["elastic_modulus"] == pytest.approx(206e9)
    assert steel["strength"] == pytest.approx(235.0)
    assert 0.0 < steel["poisson_ratio"] < 0.5
    assert steel["density"] > 0


def test_all_records_json_serializable():
    """全部记录可直接 JSON 序列化（Android 传递要求）。"""
    lib = MaterialsLibrary()
    payload = json.dumps({"materials": lib.all_materials()},
                         ensure_ascii=False, allow_nan=False)
    assert "materials" in json.loads(payload)


def test_find_material_prefers_locale_matching_system():
    """中文→公制库中文名、英文→美标库英文名，避免标题混用语种。

    “structural_steel” 是 AI 解析器常输出的英文 id：公制库 low_carbon_steel
    没有该别名、美标库有，故旧实现会在中文版返回 “ASTM A36 Steel”。语言偏好
    与跨库等价回退应把中文版落到 gb 的“低碳钢”。
    """
    set_locale("zh", "metric")
    try:
        lib = MaterialsLibrary()
        rec = lib.find_material("structural_steel")
        assert rec is not None
        assert rec["id"] == "low_carbon_steel"
        assert rec["name"] == "低碳钢"
        assert rec.get("system") == "gb"

        # 显式指定偏好系统时按指定走
        rec_us = lib.find_material("structural_steel", prefer_system="us")
        assert rec_us["id"] == "astm_a36_steel"
        assert rec_us["name"] == "ASTM A36 Steel"

        # 英文环境走美标库
        set_locale("en", "imperial")
        rec_en = lib.find_material("structural_steel")
        assert rec_en["id"] == "astm_a36_steel"
    finally:
        # 恢复默认中文，避免污染后续测试
        set_locale("zh", "metric")


def test_find_material_id_exact_not_overridden_by_locale():
    """按 id 精确取数必须返回该 id 对应记录，不受语言偏好/等价回退影响。"""
    set_locale("zh", "metric")
    try:
        lib = MaterialsLibrary()
        # 中文版偏好 gb，但显式按美标 id 取数仍应返回美标记录
        rec = lib.find_material("astm_a36_steel", prefer_system="gb")
        assert rec is not None and rec["id"] == "astm_a36_steel"
    finally:
        set_locale("zh", "metric")
