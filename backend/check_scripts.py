"""Print the risk trajectory of every demo script (frontend/demo/scripts.json) with the exported model."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from bosc_core import load  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
sc = load(ROOT / "frontend/model/model.json", ROOT / "frontend/model/tactics.json")
print(f"WARN={sc.warn:.3f} PAUSE={sc.pause:.3f}")
for s in json.load(open(ROOT / "frontend/demo/scripts.json", encoding="utf-8"))["scripts"]:
    sc.reset()
    out = [sc.push(line) for line in s["lines"]]
    traj = " ".join(f"{o['risk']:.2f}{o['level'][0]}" for o in out)
    ps = " ".join(f"{o['p']:.2f}" for o in out)
    print(f"{s['id']:18s} {s['kind']:8s} risk: {traj}   | p: {ps}")
