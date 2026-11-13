"""Call-level recall / false-alarm of the unified score (model + tactic bumps + rule floor), Stage 0 model.
  python backend/evaluate_scoring.py
"""
import json, sys
from pathlib import Path
import lightgbm  # noqa: F401  (Windows import-order workaround)
sys.path.insert(0, str(Path(__file__).parent))
from bosc_core import load, WARN_SCORE, PAUSE_SCORE
from train import load_jsonl, load_hf, caller_utts, first_ask_turn
ROOT = Path(__file__).resolve().parent.parent
sc = load(ROOT / "frontend/model/model.json", ROOT / "frontend/model/tactics.json")
sets = {"gen_dev": load_jsonl(ROOT / "data-eval/generated/dev.jsonl"), "gen_test": load_jsonl(ROOT / "data-eval/generated/test.jsonl"),
        "hf_test": load_hf(ROOT / "data-eval/raw/scam-dialogue_test.csv", "test")}
for name, calls in sets.items():
    rows = []
    for c in calls:
        sc.reset(); peak = 0.0; first_warn = first_pause = None; model_pause = False
        for i, u in enumerate(caller_utts(c)):
            r = sc.push(u); peak = max(peak, r["score"])
            if first_warn is None and r["score"] >= WARN_SCORE: first_warn = i
            if first_pause is None and r["score"] >= PAUSE_SCORE: first_pause = i
            if r["risk"] >= sc.pause: model_pause = True
        rows.append((c["label"], c["type"], first_warn, first_pause, model_pause, c["first_suspicious_caller_turn"], first_ask_turn(c)))
    scam = [r for r in rows if r[0] == 1]; gen = [r for r in rows if r[0] == 0]
    rp = sum(r[3] is not None for r in scam) / len(scam); rw = sum(r[2] is not None for r in scam) / len(scam)
    fp = sum(r[3] is not None for r in gen) / len(gen); fw = sum(r[2] is not None for r in gen) / len(gen)
    mp = sum(r[4] for r in scam) / len(scam); mfp = sum(r[4] for r in gen) / len(gen)
    early = [r[3] - r[5] for r in scam if r[3] is not None and r[5] is not None]
    ask = [r for r in scam if r[6] is not None]; before = sum(r[3] is not None and r[3] <= r[6] for r in ask) / max(1, len(ask))
    print(f"{name:9s} scam {len(scam):3d} gen {len(gen):3d} | unified: recall@PAUSE {rp:.3f} FA {fp:.3f}  recall@WARN {rw:.3f} FA {fw:.3f} | "
          f"model-only: recall {mp:.3f} FA {mfp:.3f} | median turns after first suspicious {sorted(early)[len(early)//2] if early else None} | PAUSE by first ask {before:.2f}")
    fa_types = {}
    for r in gen:
        if r[3] is not None: fa_types[r[1]] = fa_types.get(r[1], 0) + 1
    if fa_types: print("          genuine types paused:", fa_types)
