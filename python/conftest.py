"""pytest 根配置：把源码根目录加入 sys.path（免安装直接测试）。"""
import sys
from pathlib import Path

import pytest

_ROOT = str(Path(__file__).resolve().parent)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)


@pytest.fixture(autouse=True)
def _reset_default_materials_library():
    """每个测试前后重置材料库默认单例（防止 data_dir 跨测试污染）。"""
    from pocketsim import materials
    materials._DEFAULT_LIBRARY = materials.MaterialsLibrary()
    yield
    materials._DEFAULT_LIBRARY = materials.MaterialsLibrary()

