# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""材料库：内置 JSON（包内只读）+ 外部可写副本合并。

设计要点（面向 Android）：
- 内置真源 materials.json 打包在 Python 源码目录内，Chaquopy 下为只读；
- Android 端实例化 MaterialsLibrary(data_dir=context.getFilesDir())，
  联网检索到的材料通过 upsert_material() 写入 data_dir/materials_user.json，
  下次启动自动与内置库合并（按 id 或 名称+别名 去重）；
- 桌面/测试端 data_dir 传 None 表示只用内置只读库，save_library() 会拒绝。

字段单位：elastic_modulus 为 Pa；strength 为 MPa（注明抗弯/抗拉/抗压代表值类型）；
density 为 kg/m³。数值均为工程手册代表值（非保证值），仅供生活场景快速估算。
"""
from __future__ import annotations

import hashlib
import json
import re
from datetime import date
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .i18n import is_en

# 内置材料库文件（只读真源）：优先包目录，其次源码根目录
#（Chaquopy sourceSets 下 materials.json 与 pocketsim/ 包同级）
_PKG_DIR = Path(__file__).resolve().parent
BUILTIN_PATH = _PKG_DIR / "materials.json"
if not BUILTIN_PATH.exists():
    BUILTIN_PATH = _PKG_DIR.parent / "materials.json"
# 美标材料库（locale=en 时按 system="us" 筛选展示；求解查名时与公制库合并）
BUILTIN_US_PATH = _PKG_DIR / "materials_us.json"
if not BUILTIN_US_PATH.exists():
    BUILTIN_US_PATH = _PKG_DIR.parent / "materials_us.json"
# 外部可写副本文件名（位于 data_dir 下）
USER_FILENAME = "materials_user.json"
# 材料记录必填字段
_REQUIRED_FIELDS = ("name", "elastic_modulus", "poisson_ratio", "density", "strength")


def _norm(text: Any) -> str:
    """名称归一化：去首尾空白、转小写、去掉空格与常见中英文标点，便于模糊匹配。"""
    return re.sub(r"[\s\-_·，,。.（）()【】\[\]{}]", "", str(text).strip().lower())


class MaterialsLibrary:
    """材料库：加载内置 JSON，并按需合并外部可写副本。"""

    def __init__(self, data_dir: Optional[str] = None):
        """data_dir 为 None 时仅使用包内只读库；否则合并该目录下的用户副本。"""
        self._data_dir = Path(data_dir).expanduser() if data_dir is not None else None
        self._records: List[Dict[str, Any]] = []
        self.load_library()

    # ------------------------------------------------------------------ 基础
    @property
    def data_dir(self) -> Optional[Path]:
        """外部可写数据目录（None 表示只读模式）。"""
        return self._data_dir

    def load_library(self) -> List[Dict[str, Any]]:
        """加载材料库：公制真源 + 美标库（若有），再合并外部可写副本。"""
        with open(BUILTIN_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        records: List[Dict[str, Any]] = []
        for m in data.get("materials", []):
            rec = dict(m)
            rec.setdefault("system", "gb")
            records.append(rec)
        if BUILTIN_US_PATH.exists():
            with open(BUILTIN_US_PATH, "r", encoding="utf-8") as fh:
                us_data = json.load(fh)
            for m in us_data.get("materials", []):
                rec = dict(m)
                rec.setdefault("system", "us")
                records.append(rec)
        if self._data_dir is not None:
            user_path = self._data_dir / USER_FILENAME
            if user_path.exists():
                try:
                    with open(user_path, "r", encoding="utf-8") as fh:
                        user_data = json.load(fh)
                    for rec in user_data.get("materials", []):
                        # 用户副本没有标记时归为 user：两种单位制下都应可见
                        rec.setdefault("system", "user")
                        self._upsert_into(records, rec)
                except (json.JSONDecodeError, OSError):
                    # 外部副本损坏时不阻塞：跳过合并，仅用内置库
                    pass
        self._records = records
        return self.all_materials()

    def all_materials(self, system: Optional[str] = None) -> List[Dict[str, Any]]:
        """返回材料记录（深拷贝）。

        system="gb"/"us" 时只返回该单位制内置库 + 用户材料；
        None 返回全部（求解器按名称查库时始终用全部，避免跨库材料找不到）。
        """
        if system in ("gb", "us"):
            picked = [
                dict(r) for r in self._records
                if r.get("system", "gb") in (system, "user")
            ]
        else:
            picked = [dict(r) for r in self._records]
        return picked

    def __len__(self) -> int:
        return len(self._records)

    # ------------------------------------------------------------------ 查询
    def find_material(
        self, name_or_alias: Any, prefer_system: Optional[str] = None
    ) -> Optional[Dict[str, Any]]:
        """按名称/别名/英文 id 模糊查找材料，找不到返回 None。

        匹配优先级：id 精确 > 名称精确 > 别名精确 > 双向包含（取最长候选，
        如“松木搁板”能命中别名“木搁板”与“松木”中的更长者）。

        语言 / 单位制偏好：同一优先级下命中多个库时，优先返回与当前语言匹配的
        库（中文→公制 gb，英文→英制 us），避免中文版标题混入 ASTM 等英文材料名；
        若只命中了另一单位制的库，则在本单位制库中寻找共享名称/别名的等价记录
        并改用之（如 “structural_steel” 中文版落到 gb 的“低碳钢”）。id 精确
        匹配不受偏好影响——按 id 取数必须返回该 id 对应的那条记录。
        """
        if name_or_alias is None:
            return None
        query = _norm(name_or_alias)
        if not query:
            return None
        if prefer_system is None:
            prefer_system = "us" if is_en() else "gb"

        def _keys(rec: Dict[str, Any]) -> set:
            ks = {_norm(rec.get("id", "")), _norm(rec.get("name", ""))}
            ks |= {_norm(a) for a in rec.get("aliases", [])}
            ks.discard("")
            return ks

        # 1) id 精确：确定性最高，不受语言偏好影响
        for rec in self._records:
            if _norm(rec.get("id", "")) == query:
                return dict(rec)

        # 2) 名称精确；3) 别名精确；4) 双向包含模糊（取最长候选）
        tiers: List[List[Dict[str, Any]]] = [
            [r for r in self._records if _norm(r.get("name", "")) == query],
            [r for r in self._records
             if any(_norm(a) == query for a in r.get("aliases", []))],
        ]
        fuzzy: List[Dict[str, Any]] = []
        best_len = -1
        for r in self._records:
            for cand in [r.get("name", "")] + list(r.get("aliases", [])):
                key = _norm(cand)
                if key and (key in query or query in key):
                    if len(key) > best_len:
                        fuzzy, best_len = [r], len(key)
                    elif len(key) == best_len and r not in fuzzy:
                        fuzzy.append(r)
        tiers.append(fuzzy)

        for tier in tiers:
            if not tier:
                continue
            pref = [r for r in tier if r.get("system", "gb") == prefer_system]
            chosen = pref[0] if pref else tier[0]
            # 跨库等价回退：命中了另一单位制的库时，若本单位制库中有共享名称/
            # 别名的等价记录，改用等价记录，保证显示名与单位制自洽
            if chosen.get("system", "gb") != prefer_system:
                chosen_keys = _keys(chosen)
                for r in self._records:
                    if r.get("system", "gb") == prefer_system \
                            and (_keys(r) & chosen_keys):
                        chosen = r
                        break
            return dict(chosen)
        return None

    # ------------------------------------------------------------------ 写入
    def upsert_material(
        self, record: Dict[str, Any], source_label: Optional[str] = None
    ) -> Tuple[Dict[str, Any], str]:
        """新增或更新材料（联网检索结果缓存入库）。

        去重规则：优先按 id 匹配；无 id 或 id 未命中时，按“名称+别名”与
        库内任何材料发生交集即视为同一材料，就地更新并合并别名。
        source_label 形如“联网检索”，最终写入 source 为“联网检索+YYYY-MM-DD”。
        返回 (最终记录, "inserted" | "updated")。
        """
        return self._upsert_into(self._records, record, source_label)

    def delete_material(self, material_id: str) -> bool:
        """删除联网检索/用户自填的材料，返回是否命中。

        内置 gb/us 材料由包内真源提供，不可删除（system 非 user 的记录直接跳过）。
        """
        key = _norm(material_id)
        if not key:
            return False
        for i, rec in enumerate(self._records):
            if rec.get("system") != "user":
                continue
            names = {_norm(rec.get("id", "")), _norm(rec.get("name", ""))}
            names |= {_norm(a) for a in rec.get("aliases", [])}
            names.discard("")
            if key in names:
                del self._records[i]
                return True
        return False

    def save_library(self) -> str:
        """把当前完整材料列表写入外部可写副本（data_dir/materials_user.json）。

        包内真源保持只读不改动；返回写入路径。未设置 data_dir 时抛出
        PermissionError（Android 端应传 context.getFilesDir()）。
        """
        if self._data_dir is None:
            raise PermissionError(
                "未设置可写数据目录：Android 端请传入 context.getFilesDir()，"
                "桌面端可指定任意可写目录；包内 materials.json 为只读真源。"
            )
        self._data_dir.mkdir(parents=True, exist_ok=True)
        path = self._data_dir / USER_FILENAME
        # 只回写用户材料；内置 gb/us 记录由包内真源提供，写了会污染筛选
        payload = {
            "version": 1,
            "materials": [r for r in self._records if r.get("system", "gb") == "user"],
        }
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2)
        return str(path)

    # ------------------------------------------------------------------ 内部
    @staticmethod
    def _auto_id(name: str) -> str:
        """为无 id 的新材料生成稳定 id（user_ + 名称摘要）。"""
        digest = hashlib.md5(_norm(name).encode("utf-8")).hexdigest()[:8]
        return f"user_{digest}"

    def _upsert_into(
        self,
        records: List[Dict[str, Any]],
        record: Dict[str, Any],
        source_label: Optional[str] = None,
    ) -> Tuple[Dict[str, Any], str]:
        """在给定列表内执行去重插入/更新，返回 (记录, 动作)。"""
        rec = dict(record)
        missing = [k for k in _REQUIRED_FIELDS if rec.get(k) is None]
        if missing:
            raise ValueError(f"材料记录缺少必填字段: {missing}")
        rec["aliases"] = [str(a) for a in rec.get("aliases", [])]
        rec.setdefault("strength_type", "强度代表值")
        rec.setdefault("notes", "")
        if source_label:
            # 联网检索结果：source 记录“检索来源+日期”
            rec["source"] = f"{source_label}+{date.today().isoformat()}"
        else:
            rec.setdefault("source", "未知来源")

        # 去重定位：先按 id，再按 名称+别名 交集
        idx: Optional[int] = None
        rec_id = rec.get("id")
        for i, old in enumerate(records):
            if rec_id is not None and old.get("id") == rec_id:
                idx = i
                break
        if idx is None:
            new_names = {_norm(rec["name"])} | {_norm(a) for a in rec["aliases"]}
            for i, old in enumerate(records):
                old_names = {_norm(old.get("name", ""))} | {
                    _norm(a) for a in old.get("aliases", [])
                }
                if new_names & old_names:
                    idx = i
                    break

        if idx is None:
            rec.setdefault("id", self._auto_id(rec["name"]))
            # 新增记录默认归为用户材料（两种单位制下都可见）
            rec.setdefault("system", "user")
            records.append(rec)
            return rec, "inserted"

        old = records[idx]
        for key, value in rec.items():
            # system 记录材料所属单位制，更新时不可被用户副本的默认值覆盖，
            # 否则内置 gb/us 材料会被误标为 user，在另一种单位制下也显示出来
            if key in ("id", "aliases", "system"):
                continue
            old[key] = value
        merged = list(old.get("aliases", []))
        known = {_norm(a) for a in merged}
        for alias in rec["aliases"]:
            if _norm(alias) not in known:
                merged.append(alias)
                known.add(_norm(alias))
        old["aliases"] = merged
        return old, "updated"


# ---------------------------------------------------------------------- 模块级便捷接口
# 默认库单例（只读内置库）；Android 端如需持久化联网检索结果，
# 先调用 set_default_data_dir(context.getFilesDir()) 再使用模块级函数。
_DEFAULT_LIBRARY: Optional[MaterialsLibrary] = None


def _default_library() -> MaterialsLibrary:
    global _DEFAULT_LIBRARY
    if _DEFAULT_LIBRARY is None:
        _DEFAULT_LIBRARY = MaterialsLibrary()
    return _DEFAULT_LIBRARY


def set_default_data_dir(path: str) -> MaterialsLibrary:
    """为模块级默认库指定可写数据目录（Android: context.getFilesDir()）。"""
    global _DEFAULT_LIBRARY
    _DEFAULT_LIBRARY = MaterialsLibrary(data_dir=path)
    return _DEFAULT_LIBRARY


def load_library(data_dir: Optional[str] = None) -> MaterialsLibrary:
    """新建材料库实例；data_dir=None 时仅内置只读库。"""
    return MaterialsLibrary(data_dir=data_dir)


def find_material(
    name_or_alias: Any, prefer_system: Optional[str] = None
) -> Optional[Dict[str, Any]]:
    """在默认库中模糊查找材料，找不到返回 None。"""
    return _default_library().find_material(name_or_alias, prefer_system)


def upsert_material(
    record: Dict[str, Any], source_label: Optional[str] = None
) -> Tuple[Dict[str, Any], str]:
    """向默认库新增/更新材料（联网检索缓存入库）。"""
    return _default_library().upsert_material(record, source_label)


def save_library() -> str:
    """把默认库写回外部可写副本（需先 set_default_data_dir）。"""
    return _default_library().save_library()


def delete_material(material_id: str) -> bool:
    """从默认库删除用户材料（内置材料不可删除）。"""
    return _default_library().delete_material(material_id)
