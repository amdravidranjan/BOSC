"""Train, tune, evaluate and export the BOSC detector.

Data
  gen   : template calls from gen_calls.py (train/dev/test; test uses held-out phrasings)
  hf    : BothBosu/scam-dialogue (Apache-2.0, synthetic, US-flavoured). Its own train/test split.
  lockbox (optional): data-eval/lockbox/*.jsonl human-written calls -> evaluated only, never trained on.

Outputs
  frontend/model/model.json     weights + scorer config used by the browser
  data-eval/results/metrics.json       all numbers
  data-eval/results/report.md         human-readable results
  backend/parity_cases.json    inputs + Python outputs for the JS parity test
"""
import csv
import hashlib
import json
import random
import re
import sys
import time
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, roc_auc_score

sys.path.insert(0, str(Path(__file__).parent))
from bosc_core import Model, StreamingScorer, TacticDetector, analyzer  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "data-eval" / "generated"
RAW = ROOT / "data-eval" / "raw"
LOCK = ROOT / "data-eval" / "lockbox"
OUT_MODEL = ROOT / "frontend" / "model" / "model.json"
TACTICS = ROOT / "frontend" / "model" / "tactics.json"
RES = ROOT / "data-eval" / "results"
FA_PAUSE, FA_WARN = 0.02, 0.05
ALPHA_UP, ALPHA_DOWN = 0.6, 0.15
MAX_FEATURES = 6000

# ------------------------------------------------------------------ loading

def load_jsonl(p):
    with open(p, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def load_hf(p, split):
    calls = []
    with open(p, encoding="utf-8-sig") as f:
        for i, row in enumerate(csv.DictReader(f)):
            parts = re.split(r"\b(caller|receiver):", row["dialogue"])
            turns = []
            for k in range(1, len(parts) - 1, 2):
                txt = parts[k + 1].strip()
                if txt:
                    turns.append({"speaker": parts[k], "text": txt, "tactics": []})
            label = int(row["label"])
            calls.append({"id": f"hf-{split}-{i}", "label": label, "type": "hf_" + row["type"],
                          "source": "hf", "split": split, "turns": turns,
                          "first_suspicious_caller_turn": 1 if label else None})
    return calls


def caller_utts(call):
    return [t["text"] for t in call["turns"] if t["speaker"] == "caller"]


def windows(calls, W):
    X, y = [], []
    for c in calls:
        u = caller_utts(c)
        fs = c["first_suspicious_caller_turn"]
        for i in range(len(u)):
            X.append(" ".join(u[max(0, i - W + 1): i + 1]))
            y.append(int(c["label"] == 1 and fs is not None and i >= fs))
    return X, np.array(y)

# ------------------------------------------------------------------ model

def fit(train_calls, W, C):
    X, y = windows(train_calls, W)
    vec = TfidfVectorizer(analyzer=analyzer, sublinear_tf=True, min_df=2,
                          max_features=MAX_FEATURES, norm="l2", smooth_idf=True)
    Xv = vec.fit_transform(X)
    clf = LogisticRegression(C=C, class_weight="balanced", max_iter=3000)
    clf.fit(Xv, y)
    inv = sorted(vec.vocabulary_.items(), key=lambda kv: kv[1])
    return {"vocab": [t for t, _ in inv], "idf": vec.idf_.tolist(),
            "coef": clf.coef_[0].tolist(), "intercept": float(clf.intercept_[0])}, vec, clf


def make_scorer(mj, det, W, tw, warn=0.5, pause=0.7):
    cfg = {"window": W, "tactic_weight": tw, "alpha_up": ALPHA_UP, "alpha_down": ALPHA_DOWN,
           "warn": warn, "pause": pause}
    return StreamingScorer(Model(mj), det, cfg)


def trace(scorer, call):
    scorer.reset()
    return [scorer.push(u)["risk"] for u in caller_utts(call)]


def pick_threshold(genuine_peaks, fa):
    # smallest threshold such that share of genuine calls with peak >= thr is <= fa
    peaks = np.sort(np.array(genuine_peaks))
    k = int(np.floor(fa * len(peaks)))  # number of genuine calls allowed above
    thr = peaks[len(peaks) - k - 1] + 1e-6 if k < len(peaks) else 0.0
    return float(min(max(thr, 0.05), 0.99))


def first_ask_turn(call):
    asks = {"credential_request", "payment_instruction", "remote_access"}
    for i, t in enumerate([t for t in call["turns"] if t["speaker"] == "caller"]):
        if set(t.get("tactics") or []) & asks:
            return i
    return None


def call_metrics(traces, calls, warn, pause):
    rows = []
    for tr, c in zip(traces, calls):
        tr = np.array(tr)
        peak = float(tr.max()) if len(tr) else 0.0
        fw = int(np.argmax(tr >= warn)) if peak >= warn else None
        fp = int(np.argmax(tr >= pause)) if peak >= pause else None
        fs = c["first_suspicious_caller_turn"]
        ask = first_ask_turn(c)
        rows.append({"label": c["label"], "type": c["type"], "peak": peak, "warn_at": fw,
                     "pause_at": fp, "first_susp": fs, "first_ask": ask})
    return rows


def summarize(rows, rng_seed=0, n_boot=1000):
    scam = [r for r in rows if r["label"] == 1]
    gen = [r for r in rows if r["label"] == 0]

    def rec(rs, key):
        return float(np.mean([r[key] is not None for r in rs])) if rs else float("nan")

    def ci(rs, key):
        if not rs:
            return [float("nan")] * 2
        rng = np.random.default_rng(rng_seed)
        v = np.array([r[key] is not None for r in rs], dtype=float)
        b = [v[rng.integers(0, len(v), len(v))].mean() for _ in range(n_boot)]
        return [float(np.percentile(b, 2.5)), float(np.percentile(b, 97.5))]

    early = [r["warn_at"] - r["first_susp"] for r in scam
             if r["warn_at"] is not None and r["first_susp"] is not None]
    before_ask = [r for r in scam if r["first_ask"] is not None]
    caught_before_ask = (float(np.mean([r["pause_at"] is not None and r["pause_at"] <= r["first_ask"]
                                        for r in before_ask])) if before_ask else None)
    return {
        "n_scam": len(scam), "n_genuine": len(gen),
        "recall_pause": rec(scam, "pause_at"), "recall_pause_ci": ci(scam, "pause_at"),
        "recall_warn": rec(scam, "warn_at"), "recall_warn_ci": ci(scam, "warn_at"),
        "false_alarm_pause": rec(gen, "pause_at"), "false_alarm_pause_ci": ci(gen, "pause_at"),
        "false_alarm_warn": rec(gen, "warn_at"), "false_alarm_warn_ci": ci(gen, "warn_at"),
        "median_earliness_turns": float(np.median(early)) if early else None,
        "pause_by_first_ask_turn": caught_before_ask,
    }


def window_auc(mj, calls, W):
    m = Model(mj)
    X, y = windows(calls, W)
    p = [m.proba(x) for x in X]
    if len(set(y)) < 2:
        return None, None
    return float(roc_auc_score(y, p)), float(average_precision_score(y, p))


def evaluate(mj, det, W, tw, dev, tests):
    sc = make_scorer(mj, det, W, tw)
    dev_rows = call_metrics([trace(sc, c) for c in dev], dev, 1, 1)
    gpeaks = [r["peak"] for r in dev_rows if r["label"] == 0]
    pause = pick_threshold(gpeaks, FA_PAUSE)
    warn = min(pick_threshold(gpeaks, FA_WARN), pause)
    out = {"thresholds": {"warn": warn, "pause": pause}}
    out["dev"] = summarize(call_metrics([trace(sc, c) for c in dev], dev, warn, pause))
    for name, calls in tests.items():
        if not calls:
            continue
        rows = call_metrics([trace(sc, c) for c in calls], calls, warn, pause)
        s = summarize(rows)
        roc, pr = window_auc(mj, calls, W) if tw < 1 else (None, None)
        s["window_roc_auc"], s["window_pr_auc"] = roc, pr
        # per-type recall
        per = {}
        for r in rows:
            per.setdefault(r["type"], []).append(r)
        s["per_type"] = {t: {"n": len(v), "flag_rate_pause": float(np.mean([x["pause_at"] is not None for x in v]))}
                         for t, v in sorted(per.items())}
        out[name] = s
    return out


def rules_only_model():
    return {"vocab": [], "idf": [], "coef": [], "intercept": -50.0}


# ------------------------------------------------------------------ main

def main():
    t0 = time.time()
    det = TacticDetector(json.load(open(TACTICS, encoding="utf-8")))
    gen_train, gen_dev, gen_test = (load_jsonl(GEN / f"{s}.jsonl") for s in ("train", "dev", "test"))
    hf_tr = load_hf(RAW / "scam-dialogue_train.csv", "train")
    hf_test = load_hf(RAW / "scam-dialogue_test.csv", "test")
    random.Random(7).shuffle(hf_tr)
    cut = int(0.85 * len(hf_tr))
    hf_train, hf_dev = hf_tr[:cut], hf_tr[cut:]
    lockbox = []
    if LOCK.exists():
        for p in sorted(LOCK.glob("*.jsonl")):
            lockbox += load_jsonl(p)

    train = gen_train + hf_train
    dev = gen_dev + hf_dev
    tests = {"test_gen_heldout_phrasing": gen_test, "test_hf": hf_test, "lockbox_human": lockbox}
    print(f"train calls {len(train)} dev {len(dev)} gen_test {len(gen_test)} hf_test {len(hf_test)} lockbox {len(lockbox)}")

    # ---- grid search on dev (never on test)
    best = None
    grid = []
    for W in (2, 3, 4):
        for C in (0.5, 2.0, 8.0):
            mj, _, _ = fit(train, W, C)
            for tw in (0.0, 0.15, 0.3):
                ev = evaluate(mj, det, W, tw, dev, {})
                d = ev["dev"]
                key = (round(d["recall_pause"], 3), round(d["recall_warn"], 3),
                       -(d["median_earliness_turns"] if d["median_earliness_turns"] is not None else 99))
                grid.append({"W": W, "C": C, "tw": tw, "dev_recall_pause": d["recall_pause"],
                             "dev_fa_pause": d["false_alarm_pause"], "dev_earliness": d["median_earliness_turns"]})
                print(f"W={W} C={C} tw={tw}: dev recall@PAUSE={d['recall_pause']:.3f} FA={d['false_alarm_pause']:.3f} early={d['median_earliness_turns']}")
                if best is None or key > best[0]:
                    best = (key, W, C, tw, mj)
    _, W, C, tw, mj = best
    print(f"best: W={W} C={C} tw={tw}")

    final = evaluate(mj, det, W, tw, dev, tests)

    # ---- ablations (same threshold policy)
    abl = {}
    abl["A_rules_only"] = evaluate(rules_only_model(), det, W, 1.0, dev, tests)
    abl["B_tfidf_lr_only"] = evaluate(mj, det, W, 0.0, dev, tests)
    abl["C_tfidf_lr_plus_tactics"] = final if tw > 0 else evaluate(mj, det, W, 0.15, dev, tests)

    # ---- cross-source generalisation
    mj_gen, _, _ = fit(gen_train, W, C)
    cross_gen_to_hf = evaluate(mj_gen, det, W, tw, gen_dev, {"test_hf": hf_test})
    mj_hf, _, _ = fit(hf_train, W, C)
    cross_hf_to_gen = evaluate(mj_hf, det, W, tw, hf_dev, {"test_gen_heldout_phrasing": gen_test})

    # ---- leave-one-scam-type-out (gen data)
    loto = {}
    scam_types = sorted({c["type"] for c in gen_train if c["label"] == 1})
    for st in scam_types:
        tr = [c for c in train if c["type"] != st]
        dv = [c for c in dev if c["type"] != st]
        te = [c for c in gen_test if c["type"] == st or c["label"] == 0]
        m2, _, _ = fit(tr, W, C)
        ev = evaluate(m2, det, W, tw, dv, {"held_out_type": te})
        loto[st] = {"recall_pause": ev["held_out_type"]["recall_pause"],
                    "recall_warn": ev["held_out_type"]["recall_warn"]}
        print(f"LOTO {st}: recall@PAUSE={loto[st]['recall_pause']:.3f}")

    # ---- risk-over-time examples
    sc = make_scorer(mj, det, W, tw, final["thresholds"]["warn"], final["thresholds"]["pause"])
    examples = {}
    for want in ("S2_digital_arrest", "G1_delivery_otp", "G8_scam_aware", "S1_bank_kyc"):
        c = next(c for c in gen_test if c["type"] == want)
        examples[want] = {"utterances": caller_utts(c), "risk": trace(sc, c)}

    # ---- export
    mj_out = {
        "format": "bosc-tfidf-lr-v1",
        "created": time.strftime("%Y-%m-%d %H:%M"),
        "vocab": mj["vocab"],
        "idf": [round(v, 6) for v in mj["idf"]],
        "coef": [round(v, 6) for v in mj["coef"]],
        "intercept": round(mj["intercept"], 6),
        "scorer": {"window": W, "tactic_weight": tw, "alpha_up": ALPHA_UP, "alpha_down": ALPHA_DOWN,
                   "warn": round(final["thresholds"]["warn"], 6), "pause": round(final["thresholds"]["pause"], 6)},
        "train_info": {"C": C, "n_train_calls": len(train), "max_features": MAX_FEATURES},
    }
    OUT_MODEL.write_text(json.dumps(mj_out, separators=(",", ":")), encoding="utf-8")
    digest = hashlib.sha256(OUT_MODEL.read_bytes()).hexdigest()[:16]

    # parity cases use the *rounded* exported weights
    exported = json.loads(OUT_MODEL.read_text(encoding="utf-8"))
    m_exp = Model(exported)
    rng = random.Random(11)
    pool = [u for c in gen_test + hf_test for u in caller_utts(c)]
    cases = rng.sample(pool, 200) + ["", "OTP", "Don't tell anyone, transfer 50,000 now!", "o.t.p 1,23,456 ok"]
    sc_exp = StreamingScorer(m_exp, det, exported["scorer"])
    stream_call = caller_utts(next(c for c in gen_test if c["type"] == "S2_digital_arrest"))
    sc_exp.reset()
    stream_out = [sc_exp.push(u)["risk"] for u in stream_call]
    (ROOT / "backend" / "parity_cases.json").write_text(json.dumps({
        "proba": [{"text": t, "p": m_exp.proba(t)} for t in cases],
        "tactics": [{"text": t, "found": sorted(det.detect(t))} for t in cases],
        "stream": {"utterances": stream_call, "risk": stream_out},
    }), encoding="utf-8")

    RES.mkdir(exist_ok=True)
    metrics = {"model_sha256_16": digest, "chosen": {"W": W, "C": C, "tactic_weight": tw},
               "final": final, "ablations": abl,
               "cross_source": {"train_gen_test_hf": cross_gen_to_hf.get("test_hf"),
                                "train_hf_test_gen": cross_hf_to_gen.get("test_gen_heldout_phrasing")},
               "leave_one_type_out": loto, "grid": grid, "examples": examples,
               "elapsed_s": round(time.time() - t0, 1)}
    (RES / "metrics.json").write_text(json.dumps(metrics, indent=1), encoding="utf-8")
    write_report(metrics)
    print(f"done in {metrics['elapsed_s']}s; model sha {digest}; size {OUT_MODEL.stat().st_size // 1024} KB")


def pct(x):
    return "n/a" if x is None or x != x else f"{100 * x:.1f}%"


def pci(s, key):
    lo, hi = s[key + "_ci"]
    return f"{pct(s[key])} [{pct(lo)}–{pct(hi)}]"


def write_report(m):
    f = m["final"]
    L = ["# BOSC detector — results", "",
         f"Model hash `{m['model_sha256_16']}` · window={m['chosen']['W']} caller utterances · "
         f"C={m['chosen']['C']} · tactic weight={m['chosen']['tactic_weight']}",
         f"Thresholds (picked on dev so genuine-call false alarms ≤2% PAUSE / ≤5% WARN): "
         f"WARN={f['thresholds']['warn']:.3f}, PAUSE={f['thresholds']['pause']:.3f}", "",
         "> **Read this first.** Every set below is synthetic (template-generated or LLM-generated). "
         "Do not headline these numbers. The headline must come from the human-written lockbox and real recordings.", "",
         "## Main results (95% bootstrap CIs)", "",
         "| Test set | Scam calls | Genuine | Recall @PAUSE | False alarm @PAUSE | Recall @WARN | False alarm @WARN | Median earliness (turns) | PAUSE before first OTP/pay/app ask | Window ROC-AUC |",
         "|---|---|---|---|---|---|---|---|---|---|"]
    for name in ("test_gen_heldout_phrasing", "test_hf", "lockbox_human"):
        s = f.get(name)
        if not s:
            continue
        L.append(f"| {name} | {s['n_scam']} | {s['n_genuine']} | {pci(s, 'recall_pause')} | {pci(s, 'false_alarm_pause')} | "
                 f"{pci(s, 'recall_warn')} | {pci(s, 'false_alarm_warn')} | {s['median_earliness_turns']} | "
                 f"{pct(s['pause_by_first_ask_turn'])} | {s['window_roc_auc'] and round(s['window_roc_auc'], 3)} |")
    L += ["", "## Ablation (same threshold policy)", "",
          "| System | Gen test recall @PAUSE | Gen test FA @PAUSE | HF test recall @PAUSE | HF test FA @PAUSE |", "|---|---|---|---|---|"]
    for k, ev in m["ablations"].items():
        g, h = ev["test_gen_heldout_phrasing"], ev["test_hf"]
        L.append(f"| {k} | {pct(g['recall_pause'])} | {pct(g['false_alarm_pause'])} | {pct(h['recall_pause'])} | {pct(h['false_alarm_pause'])} |")
    cs = m["cross_source"]
    L += ["", "## Cross-source generalisation", "",
          f"- Trained on our Indian-context templates only → tested on HF (US-style) calls: recall @PAUSE {pct(cs['train_gen_test_hf']['recall_pause'])}, false alarm {pct(cs['train_gen_test_hf']['false_alarm_pause'])}",
          f"- Trained on HF only → tested on our templates: recall @PAUSE {pct(cs['train_hf_test_gen']['recall_pause'])}, false alarm {pct(cs['train_hf_test_gen']['false_alarm_pause'])}",
          "", "## Leave-one-scam-type-out (recall on the unseen type @PAUSE)", "", "| Held-out type | Recall @PAUSE | Recall @WARN |", "|---|---|---|"]
    for t, v in m["leave_one_type_out"].items():
        L.append(f"| {t} | {pct(v['recall_pause'])} | {pct(v['recall_warn'])} |")
    L += ["", "## Per-type flag rate on gen test (scam types should be high, genuine low)", "", "| Type | n | Flagged @PAUSE |", "|---|---|---|"]
    for t, v in f["test_gen_heldout_phrasing"]["per_type"].items():
        L.append(f"| {t} | {v['n']} | {pct(v['flag_rate_pause'])} |")
    (RES / "report.md").write_text("\n".join(L) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
