"""CPU-only smoke test of Stage 3 (no transformers needed).

Fakes the 9 window sigmoids from the Stage 0 model (scam p) and the regex tactics (tactic probs),
trains the stream model, checks our tree evaluator against LightGBM, and writes parity cases for
backend/parity_stream.mjs.

  python backend/test_stream_local.py OUT_DIR
  node backend/parity_stream.mjs OUT_DIR/stream_model.json OUT_DIR/stream_parity_cases.json
"""
import json
import sys
from pathlib import Path

import lightgbm  # noqa: F401  (Windows: import before sklearn or LightGBM crashes with an OpenMP DLL clash)
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from bosc_core import Model, TacticDetector  # noqa: E402
import stream_features as sf  # noqa: E402
from train import caller_utts, call_metrics, load_jsonl, pick_threshold, summarize  # noqa: E402

ROOT = HERE.parent
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
m = Model(json.load(open(ROOT / "frontend/model/model.json", encoding="utf-8")))
det = TacticDetector(json.load(open(ROOT / "frontend/model/tactics.json", encoding="utf-8")))


def fake(calls):
    P, R, Y = [], [], []
    for c in calls:
        u = caller_utts(c)
        fs = c["first_suspicious_caller_turn"]
        p_rows, r_rows = [], []
        for i in range(len(u)):
            found = sorted(det.detect(u[i]))
            p = m.proba(" ".join(u[max(0, i - 3): i + 1]))
            p_rows.append([p] + [0.9 if t in found else 0.05 for t in sf.TACTICS])
            r_rows.append(found)
        P.append(p_rows)
        R.append(r_rows)
        Y.append([int(c["label"] == 1 and fs is not None and i >= fs) for i in range(len(u))])
    return P, R, Y


train = load_jsonl(ROOT / "data-eval/generated/train.jsonl")[:1200]
dev = load_jsonl(ROOT / "data-eval/generated/dev.jsonl")
Pc, Rc, Yc = fake(train)
Pd, Rd, _ = fake(dev)
X, y, w, g = sf.step_dataset(Pc, Rc, Yc)
booster_m, iso, cal = sf.fit_stream(X, y, w, g)
print("calibration", cal)

sm = sf.make_export("lgbm", booster_m.booster_, iso)
lgb_raw = booster_m.booster_.predict(X[:2000], raw_score=True)
mine = np.array([sf.tree_raw(sm["trees"], x) for x in X[:2000]])
print("tree evaluator vs LightGBM raw: max |diff| =", float(np.abs(lgb_raw - mine).max()))

tr = sf.traces(sm, Pd, Rd)
peaks = [max(t) for t, c in zip(tr, dev) if c["label"] == 0]
sm["pause"] = pick_threshold(peaks, 0.02)
sm["warn"] = min(pick_threshold(peaks, 0.05), sm["pause"])
tr = sf.traces(sm, Pd, Rd)
s = summarize(call_metrics(tr, dev, sm["warn"], sm["pause"]))
print(f"dev recall@PAUSE {s['recall_pause']:.3f} FA {s['false_alarm_pause']:.3f} (fake inputs, only a smoke test)")

(out / "stream_model.json").write_text(sf.dumps(sm), encoding="utf-8")
cases = []
sc = sf.StreamScorer(sm)
for P, R in list(zip(Pd, Rd))[:100]:
    sc.reset()
    rows = [sc.push(P[i], R[i]) for i in range(len(P))]
    cases.append({"probs": P, "regex": R, "features": [r["features"] for r in rows],
                  "q": [r["q"] for r in rows], "risk": [r["risk"] for r in rows], "level": [r["level"] for r in rows]})
(out / "stream_parity_cases.json").write_text(json.dumps({"calls": cases}), encoding="utf-8")
print("wrote", out)
