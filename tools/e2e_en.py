import json, sys
sys.path.insert(0, ".")
from pocketsim import bridge
m = {"name":"PVC pipe","kind":"shell_buried_pipe","geometry":{"outer_diameter_m":0.05,"thickness_m":0.003,"cover_depth_m":2.0},"material":"PVC"}
d = json.loads(bridge.run_buried_pipe(json.dumps(m), ".", False, "en", "metric"))
print("verdict=", d["verdict"])
print("advice0=", d["advice"][0])
print("formula0=", d["formulas"][0])
print("assumption0=", d["assumptions"][0])
print("check0=", d["checks"][0]["label"])
