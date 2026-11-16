import json, sys
sys.path.insert(0, ".")
from pocketsim import bridge
m = {"kind":"shell_vessel","geometry":{"section":"rect","length_m":1.0,"width_m":0.8,"height_m":1.0,"thickness_m":0.008},"material":"钢","fill":{"type":"fluid","bulk_density_kg_m3":1000.0,"depth_m":1.0}}
d = json.loads(bridge.run_shell(json.dumps(m), ".", "0", "zh", "metric"))
print("kind=", d.get("kind"), "verdict=", d.get("verdict"))
mm = d.get("metrics", {})
print("max_vm=", mm.get("max_von_mises_MPa"), "SF=", mm.get("safety_factor"), "max_disp=", mm.get("max_disp_mm"))
