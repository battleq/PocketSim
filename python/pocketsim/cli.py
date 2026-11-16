# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""命令行调试入口（桌面端验证用，Android 不经过此模块）。

用法：
    python -m pocketsim.cli templates
    python -m pocketsim.cli run cantilever_shelf \
        --params '{"length_mm": 300, "thickness_mm": 20, "material": "松木", "load_kg": 10}' \
        --mesh medium
    python -m pocketsim.cli parse --text "在墙上打个钉子挂3kg"
    python -m pocketsim.cli demo-snapshots --out ../android/app/src/test/resources/snapshot
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .engine import list_templates, run_scenario
from .parser import parse_problem

# demo-snapshots 的默认场景（覆盖 UI 截图测试回放所需的全部模板/材料/结果）
_DEMO_SCENARIOS = [
    ("wall_nail", "在墙上打进一个钉子，钉子上悬挂3KG重物，墙和钉子是否能够承重"),
    ("cantilever_shelf", "松木搁板悬挑250毫米，厚25毫米，端部挂5公斤重物"),
]

# 通用框架模式的演示模型（UiScreenshotTest.frameReplay 回放）：
# 靠墙单立柱双层书架——立柱 30×40 松木，下层臂端一箱 15 公斤书（集中力），
# 上层整层约 25 公斤书（均布载荷）。截面偏细，结果落在"危险"临界档，
# 截图可完整展示危险配色、变形放大与杆件利用率。
_FRAME_DEMO_MODEL = {
    "name": "单立柱双层书架",
    "nodes": [
        {"id": 1, "x": 0, "y": 0},
        {"id": 2, "x": 0, "y": 900},
        {"id": 3, "x": 0, "y": 1500},
        {"id": 4, "x": 400, "y": 900},
        {"id": 5, "x": 400, "y": 1500},
    ],
    "members": [
        {"id": 1, "i": 1, "j": 2, "width_b": 30, "depth_h": 40, "material": "松木"},
        {"id": 2, "i": 2, "j": 3, "width_b": 30, "depth_h": 40, "material": "松木"},
        {"id": 3, "i": 2, "j": 4, "width_b": 30, "depth_h": 40, "material": "松木"},
        {"id": 4, "i": 3, "j": 5, "width_b": 30, "depth_h": 40, "material": "松木"},
    ],
    "supports": [{"node": 1, "type": "fixed"}],
    "loads": [
        {"type": "force", "node": 4, "fx": 0, "fy": -147},
        {"type": "udl", "member": 4, "qy": -0.6125},
    ],
}


# 流体演示模型（UiScreenshotTest.flowReplay 回放）：4 米长、2.5 米高的 240 砖墙，
# 10 级风（27.5 m/s）已经超过临界风速，结论落在"危险"档，截图可展示
# 危险配色、三项稳定性校核与压力云图/流线。
_FLOW_DEMO_MODEL = {
    "name": "砖墙抗风",
    "kind": "wind_wall",
    "wall": {"height_m": 2.5, "length_m": 4.0, "thickness_m": 0.24},
    "beaufort": 10,
    "material": "砖砌体",
    "mesh_level": "medium",
}


# 渗流演示模型（UiScreenshotTest.seepageReplay 回放）：12 米高混凝土重力坝，
# 上游水位 11 米、下游 1 米，坝基渗透系数 1e-5 m/s。坝趾出逸比降超过允许值，
# 结论落在"危险"档，截图可展示危险配色、扬压力/渗流量指标与水头云图/渗流线。
_SEEPAGE_DEMO_MODEL = {
    "name": "大坝渗流",
    "kind": "dam_seepage",
    "dam": {
        "height_m": 12.0,
        "crest_width_m": 5.0,
        "upstream_slope": 0.0,
        "downstream_slope": 0.75,
    },
    "upstream_level_m": 11.0,
    "downstream_level_m": 1.0,
    "material": "混凝土C20",
    "foundation_permeability_m_s": 1e-5,
    "foundation_depth_m": 18.0,
    "friction_coeff": 0.6,
    "mesh_level": "medium",
}


# 时间历程演示（UiScreenshotTest.transientReplay 回放）：门式遮阳篷支架，
# 横梁（杆件 2、3）在 2 小时内 20→120→20°C 循环，中间节点挂 50 公斤重物。
# 升温时横梁膨胀把立柱向外推，立柱弯矩增大；应力与变形都在 3600 秒（最热时刻）最大。
_TRANSIENT_DEMO_SPEC = {
    "name": "遮阳篷门式支架暴晒",
    "frame": {
        "name": "遮阳篷门式支架",
        "nodes": [
            {"id": 1, "x": 0, "y": 0},
            {"id": 2, "x": 0, "y": 1800},
            {"id": 5, "x": 1200, "y": 1800},
            {"id": 3, "x": 2400, "y": 1800},
            {"id": 4, "x": 2400, "y": 0},
        ],
        "members": [
            {"id": 1, "i": 1, "j": 2, "width_b": 20, "depth_h": 40, "material": "铝合金"},
            {"id": 2, "i": 2, "j": 5, "width_b": 40, "depth_h": 60, "material": "铝合金"},
            {"id": 3, "i": 5, "j": 3, "width_b": 40, "depth_h": 60, "material": "铝合金"},
            {"id": 4, "i": 3, "j": 4, "width_b": 20, "depth_h": 40, "material": "铝合金"},
        ],
        "supports": [{"node": 1, "type": "fixed"}, {"node": 4, "type": "fixed"}],
        "loads": [{"type": "force", "node": 5, "fx": 0, "fy": -490}],
    },
    "duration_s": 7200,
    "steps": 12,
    "temp_base_c": 20,
    "temp_peak_c": 120,
    "profile": "triangle",
    "members": [2, 3],
}


# 三维实体演示模型（UiScreenshotTest.solidReplay 回放）：与流体演示同一堵 4 米长、
# 2.5 米高、240 砖墙，10 级风下墙根弯曲强度不足，结论落在"危险"档，截图可展示
# 危险配色、三维应力云图与墙根开裂/倾覆的临界风速。
_SOLID_DEMO_MODEL = {
    "name": "砖墙抗风（三维实体）",
    "kind": "solid_wall",
    "wall": {"height_m": 2.5, "length_m": 4.0, "thickness_m": 0.24},
    "beaufort": 10,
    "material": "砖砌体",
    "mesh_level": "medium",
}


def _write_snapshots(out_dir: str) -> int:
    """生成 Android UiScreenshotTest 的回放数据（bridge 契约 JSON）。"""
    from . import bridge as bridge_mod

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    templates = json.loads(bridge_mod.list_templates())
    (out / "templates.json").write_text(
        json.dumps(templates, ensure_ascii=False), encoding="utf-8")
    (out / "materials.json").write_text(
        json.dumps(json.loads(bridge_mod.list_materials("")), ensure_ascii=False),
        encoding="utf-8")

    for fname, text in _DEMO_SCENARIOS:
        parsed = parse_problem(text)
        if not parsed.get("ok"):
            print(f"[skip] {fname}: 解析失败（{parsed.get('reason')}）")
            continue
        template = next(t for t in templates if t["id"] == parsed["template_id"])
        params = dict(parsed["params"])
        for spec in template["param_schema"]:
            if spec["key"] not in params and spec.get("default") is not None:
                params[spec["key"]] = spec["default"]
        request = {"template_id": template["id"], "params": params, "mesh_level": "medium"}
        result = json.loads(bridge_mod.run_scenario(json.dumps(request)))
        (out / f"{fname}.json").write_text(
            json.dumps(
                {"template": template, "request": request, "result": result},
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        m = result["metrics"]
        print(f"[ok] {fname}: {m['verdict']}，变形 {m['max_deflection_mm']:.4f} mm，"
              f"应力 {m['max_stress_MPa']:.2f} MPa")

    # 框架模式回放数据（model + result 一体，供 UiScreenshotTest.frameReplay）
    frame_result = json.loads(
        bridge_mod.run_frame(json.dumps(_FRAME_DEMO_MODEL, ensure_ascii=False), ""))
    (out / "frame_bookshelf.json").write_text(
        json.dumps(
            {"model": _FRAME_DEMO_MODEL, "result": frame_result},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(f"[ok] frame_bookshelf: {frame_result['verdict']}，"
          f"变形 {frame_result['max_disp_mm']:.4f} mm，"
          f"应力 {frame_result['max_stress_MPa']:.2f} MPa，"
          f"安全系数 {frame_result['safety_factor']:.2f}")

    # 流体模式回放数据（model + result 一体，供 UiScreenshotTest.flowReplay）
    flow_result = json.loads(
        bridge_mod.run_flow(json.dumps(_FLOW_DEMO_MODEL, ensure_ascii=False), ""))
    (out / "flow_wall.json").write_text(
        json.dumps(
            {"model": _FLOW_DEMO_MODEL, "result": flow_result},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(f"[ok] flow_wall: {flow_result['verdict']}，"
          f"风速 {flow_result['wind_speed_m_s']:.1f} m/s，"
          f"风荷载 {flow_result['force_N'] / 1000.0:.2f} kN，"
          f"安全系数 {flow_result['safety_factor']:.2f}，"
          f"临界 {flow_result['critical_speed_m_s']:.1f} m/s")

    # 渗流模式回放数据（model + result 一体，供 UiScreenshotTest.seepageReplay）
    seepage_result = json.loads(
        bridge_mod.run_seepage(json.dumps(_SEEPAGE_DEMO_MODEL, ensure_ascii=False), ""))
    (out / "seepage_dam.json").write_text(
        json.dumps(
            {"model": _SEEPAGE_DEMO_MODEL, "result": seepage_result},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(f"[ok] seepage_dam: {seepage_result['verdict']}，"
          f"扬压力 {seepage_result['uplift_kN_per_m']:.1f} kN/m，"
          f"单宽渗流量 {seepage_result['seepage_q_l_s']:.3f} L/s·m，"
          f"出逸比降 {seepage_result['exit_gradient']:.3f}，"
          f"安全系数 {seepage_result['safety_factor']:.2f}，"
          f"临界水位 {seepage_result['critical_level_m']:.1f} m")

    # 时间历程回放数据（spec + result 一体，供 UiScreenshotTest.transientReplay）
    transient_result = json.loads(
        bridge_mod.run_transient(json.dumps(_TRANSIENT_DEMO_SPEC, ensure_ascii=False), ""))
    (out / "transient_canopy.json").write_text(
        json.dumps(
            {"spec": _TRANSIENT_DEMO_SPEC, "result": transient_result},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    worst = transient_result["worst"]
    print(f"[ok] transient_canopy: {transient_result['verdict']}，"
          f"最不利 {worst['t_s']:.0f} s / {worst['temp_c']:.0f}°C，"
          f"应力 {worst['max_stress_MPa']:.2f} MPa，"
          f"最大变形 {transient_result['max_disp_mm']:.3f} mm，"
          f"安全系数 {transient_result['safety_factor']:.2f}")

    # 三维实体模式回放数据（model + result 一体，供 UiScreenshotTest.solidReplay）
    solid_result = json.loads(
        bridge_mod.run_solid(json.dumps(_SOLID_DEMO_MODEL, ensure_ascii=False), ""))
    (out / "solid_wall.json").write_text(
        json.dumps(
            {"model": _SOLID_DEMO_MODEL, "result": solid_result},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(f"[ok] solid_wall: {solid_result['metrics']['verdict']}，"
          f"墙顶侧移 {solid_result['metrics']['top_disp_mm']:.3f} mm，"
          f"墙根弯曲 {solid_result['metrics']['bend_MPa']:.2f} MPa，"
          f"安全系数 {solid_result['metrics']['safety_factor']:.2f}，"
          f"网格 {solid_result['mesh']['n_elements']} 单元")

    print(f"快照已写入 {out.resolve()}")
    return 0


def _main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m pocketsim.cli",
        description="口袋物理仿真：2D 线弹性有限元生活场景计算（结果为 JSON）",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("templates", help="列出全部模板与参数表")

    parse_cmd = sub.add_parser("parse", help="离线规则解析一句话问题（不联网）")
    parse_cmd.add_argument("--text", required=True, help="中文问题描述原文")

    snap_cmd = sub.add_parser(
        "demo-snapshots",
        help="生成 Android UI 截图测试的回放数据（模板/材料/两场景真实求解结果）",
    )
    snap_cmd.add_argument(
        "--out", default="../android/app/src/test/resources/snapshot",
        help="输出目录（默认 Android 工程的测试资源目录）",
    )

    frame_cmd = sub.add_parser("frame", help="求解 2D 梁杆框架（模型 JSON 文件）")
    frame_cmd.add_argument("model_file", help="框架模型 JSON 文件路径")
    frame_cmd.add_argument("--brief", action="store_true",
                           help="只打印关键指标，不输出完整 JSON")

    run_parser = sub.add_parser("run", help="运行一个场景仿真")
    run_parser.add_argument("template_id", help="模板 id（templates 子命令可查看）")
    run_parser.add_argument(
        "--params", default="{}",
        help='场景参数 JSON 字符串，如 \'{"mass_kg": 3}\'',
    )
    run_parser.add_argument(
        "--mesh", default="medium", choices=["coarse", "medium", "fine"],
        help="网格档位（默认 medium）",
    )
    run_parser.add_argument(
        "--timeout", type=float, default=60.0,
        help="单次求解耗时上限（秒），超过自动降级粗档",
    )

    args = parser.parse_args(argv)

    if args.command == "templates":
        print(json.dumps(list_templates(), ensure_ascii=False, indent=2))
        return 0

    if args.command == "parse":
        print(json.dumps(parse_problem(args.text), ensure_ascii=False, indent=2))
        return 0

    if args.command == "demo-snapshots":
        return _write_snapshots(args.out)

    if args.command == "frame":
        from .frame import run_frame
        try:
            model = json.loads(Path(args.model_file).read_text(encoding="utf-8"))
        except FileNotFoundError:
            print(f"[error] 文件不存在：{args.model_file}")
            return 1
        except json.JSONDecodeError as exc:
            print(f"[error] 模型 JSON 解析失败：{exc}")
            return 1
        try:
            result = run_frame(model)
        except ValueError as exc:
            print(f"[error] {exc}")
            return 1
        if args.brief:
            print(f"[ok] {result['model_name']}: {result['verdict']}，"
                  f"变形 {result['max_disp_mm']:.4f} mm，"
                  f"应力 {result['max_stress_MPa']:.2f} MPa，"
                  f"安全系数 {result['safety_factor']:.2f}，"
                  f"求解 {result.get('solve_ms', 0)} ms")
            if result["buckling_note"]:
                print(f"[!]  {result['buckling_note']}")
        else:
            print(json.dumps(result, ensure_ascii=False))
        return 0

    try:
        params = json.loads(args.params)
    except json.JSONDecodeError as exc:
        print(json.dumps(
            {"ok": False, "errors": [f"--params 不是合法 JSON：{exc}"]},
            ensure_ascii=False, indent=2,
        ))
        return 1
    if not isinstance(params, dict):
        print(json.dumps(
            {"ok": False, "errors": ["--params 必须是 JSON 对象，如 '{\"mass_kg\": 3}'"]},
            ensure_ascii=False, indent=2,
        ))
        return 1

    result = run_scenario(args.template_id, params, mesh_level=args.mesh,
                          timeout_s=args.timeout)
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(_main())
