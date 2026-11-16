import json, sys
sys.path.insert(0, ".")
from pocketsim import bridge

model = {
    "name": "PVC 埋地管 2m",
    "kind": "shell_buried_pipe",
    "geometry": {"outer_diameter_m": 0.05, "thickness_m": 0.003, "cover_depth_m": 2.0},
    "material": "PVC",
}
out = bridge.run_buried_pipe(json.dumps(model, ensure_ascii=False), ".", False, "zh", "si")
d = json.loads(out) if isinstance(out, str) else out
print("ok=", d.get("ok"), " verdict=", d.get("verdict"), d.get("verdict_zh"))
m = d.get("metrics", {})
for k in ["outer_diameter_mm","thickness_mm","cover_depth_m","material","E_MPa","p_soil_kPa","p_live_kPa","deflection_mm","deflection_ratio_pct","max_ring_stress_MPa","stress_ratio","buckling_pcr_kPa","buckling_sf"]:
    print(f"  {k} = {m.get(k)}")
print("checks=", [c.get("label")+":"+str(c.get("status")) for c in d.get("checks", [])])
print("assumptions=", d.get("assumptions"))
r = d.get("render", {})
print("render original pts=", len(r.get("original", [])), " deformed pts=", len(r.get("deformed", [])), " amp=", r.get("amplification"), " D=", r.get("diameter_mm"))
