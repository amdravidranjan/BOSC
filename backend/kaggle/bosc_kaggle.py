# %% [markdown]
# # BOSC: full training run (Stages 0 → 4) on Kaggle
#
# **One notebook, top to bottom:**
# - data;
# - Stage 0 baseline rows;
# - Stage 1 embeddings + scam library;
# - Stage 2 DeBERTa teacher;
# - Stage 3 streaming LightGBM;
# - Stage 4 MiniLM student → ONNX int8;
# - browser files, parity cases, ablation report.
#
# **Before you run**
# 1. *Settings* → **Accelerator: GPU T4 x1** (or P100) and **Internet: On**.
# 2. *Add-ons → Secrets* (optional but recommended): add `HF_TOKEN`, a Hugging Face **write** token.
#    It's used to back up checkpoints to a private HF repo, so a crashed session can resume. Never paste tokens into the code.
# 3. Optional data: *Add Input* → a Kaggle dataset containing any of:
#    - `bosc_train_*.jsonl` (extra training calls, e.g. LLM-generated);
#    - `lockbox*.jsonl`, `redteam*.jsonl`, `realaudio*.jsonl` (evaluation only, never trained on).
#    The format is the call JSONL described in `data-eval/README.md`.
# 4. Set `QUICK = True` for a ~15-minute smoke test first, then `False` for the full run (~2–3 h on a T4).
# 5. **Save Version → Save & Run All (Commit)**. It runs in the background; you can close the tab.
#
# **Download.** *Output* tab → `bosc_latest.zip`. It's rebuilt after every stage, so it always holds the newest results.
# Unzip it at the repo root; the paths inside match the repo (`frontend/model/...`, `data-eval/results/...`, `backend/...`).
# The large teacher checkpoint is in `bosc_run/teacher_*/best/` (Output tab and the HF backup), not in the zip.
#
# **Resume.** Re-run the notebook. Finished stages are skipped, and an interrupted training continues from `last_*.pt`.
# Checkpoints are restored from, in order:
# - this session's `/kaggle/working`;
# - an attached previous version's output (*Add Input* → your notebook → its output);
# - the HF backup.

# %%
QUICK = False                     # True = fast end-to-end smoke test on a small subset

CFG = dict(
    REPO="https://github.com/amdravidranjan/BOSC.git",
    TEACHERS=["microsoft/deberta-v3-base"],   # add "roberta-base" to compare; best dev PR-AUC wins
    TEACHER_SEEDS=[13],                      # README asks for [13, 42, 1234] for mean ± std (3x the time)
    TEACHER_EPOCHS=4, TEACHER_LR=2e-5, TEACHER_BS=32, TEACHER_MAXLEN=192, PATIENCE=1,
    STUDENT="sentence-transformers/all-MiniLM-L6-v2",
    STUDENT_EPOCHS=6, STUDENT_LR=5e-5, STUDENT_BS=64, STUDENT_MAXLEN=128, KD_ALPHA=0.7, KD_T=2.0,
    EMBEDDER="sentence-transformers/all-MiniLM-L6-v2", LIBRARY_K=300,
    WINDOW=4, JOIN=" | ", CALIB_FRAC=0.2,
    CKPT_EVERY_STEPS=400,
    HF_REPO="",                # "" -> "<your-hf-username>/bosc-checkpoints" (private)
    HF_SYNC=True,              # back up to HF after each epoch / stage (needs the HF_TOKEN secret)
    HF_SYNC_OPTIMIZER=False,   # also upload optimizer state (large: ~1.5 GB for deberta-base)
)
if QUICK:
    CFG.update(TEACHERS=["microsoft/deberta-v3-small"], TEACHER_EPOCHS=1, STUDENT_EPOCHS=1, LIBRARY_K=20,
               CKPT_EVERY_STEPS=50, MAX_CALLS=300, TEACHER_LR=3e-5)

# %%
import glob
import hashlib
import json
import math
import os
import random
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

import numpy as np

ON_KAGGLE = Path("/kaggle/working").exists()
LOCAL_TEST = os.environ.get("BOSC_LOCAL_TEST") == "1"   # CPU-only check of the plumbing (fake teacher, no Stage 1/4)
if LOCAL_TEST:
    QUICK = True
    CFG.update(MAX_CALLS=300, HF_SYNC=False, LIBRARY_K=20)
WORK = Path("/kaggle/working") if ON_KAGGLE else Path(os.environ.get("BOSC_WORK", "kaggle_work")).resolve()
RUN = WORK / "bosc_run"          # checkpoints, predictions, stage markers
OUT = WORK / "bosc_out"          # deliverables, laid out like the repo
ZIP = WORK / "bosc_latest.zip"
REPO_DIR = Path(os.environ["BOSC_REPO"]) if os.environ.get("BOSC_REPO") else Path("/tmp/BOSC")
for d in (WORK, RUN, OUT):
    d.mkdir(parents=True, exist_ok=True)


def sh(cmd):
    print("$", cmd)
    subprocess.run(cmd, shell=True, check=True)


if ON_KAGGLE:
    sh("pip install -q onnx onnxruntime lightgbm sentencepiece protobuf sentence-transformers")
import lightgbm  # noqa: E402,F401  (import before sklearn: avoids a LightGBM/OpenMP crash on Windows)

# secrets (never hard-code tokens)
HF_TOKEN = os.environ.get("HF_TOKEN")
if ON_KAGGLE and not HF_TOKEN:
    try:
        from kaggle_secrets import UserSecretsClient
        HF_TOKEN = UserSecretsClient().get_secret("HF_TOKEN")
    except Exception:
        HF_TOKEN = None
if HF_TOKEN:
    os.environ["HF_TOKEN"] = HF_TOKEN
print("Kaggle:", ON_KAGGLE, "| HF token:", "yes" if HF_TOKEN else "no (no HF backup)", "| QUICK:", QUICK, "| LOCAL_TEST:", LOCAL_TEST)

try:
    import torch
    DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
    if DEVICE == "cuda":
        print("GPU:", torch.cuda.get_device_name(0))
    elif not LOCAL_TEST:
        print("WARNING: no GPU. Turn on the GPU accelerator in Settings, or Stage 2/4 will take many hours.")
except ImportError:
    torch, DEVICE = None, "cpu"

# ---------------------------------------------------------------- checkpoint helpers
HF_REPO_ID = None


def hf_repo():
    global HF_REPO_ID
    if HF_REPO_ID or not (HF_TOKEN and CFG["HF_SYNC"]):
        return HF_REPO_ID
    try:
        from huggingface_hub import HfApi
        api = HfApi(token=HF_TOKEN)
        HF_REPO_ID = CFG["HF_REPO"] or f"{api.whoami()['name']}/bosc-checkpoints"
        api.create_repo(HF_REPO_ID, private=True, exist_ok=True)
        print("HF backup repo:", HF_REPO_ID, "(private)")
    except Exception as e:
        print("HF backup disabled:", e)
        CFG["HF_SYNC"] = False
    return HF_REPO_ID


def sync(msg="sync"):
    """Back up bosc_run/ (without optimizer state) and bosc_out/ to the private HF repo. Never fatal."""
    repo = hf_repo()
    if not repo:
        return
    try:
        from huggingface_hub import HfApi
        api = HfApi(token=HF_TOKEN)
        ignore = [] if CFG["HF_SYNC_OPTIMIZER"] else ["**/last_optim.pt"]
        for sub in ("bosc_run", "bosc_out"):
            if (WORK / sub).exists():
                api.upload_folder(folder_path=str(WORK / sub), path_in_repo=sub, repo_id=repo,
                                  commit_message=msg, ignore_patterns=ignore + ["**/__pycache__/**"])
        print("HF backup ok:", msg)
    except Exception as e:
        print("HF backup failed (continuing):", e)


def restore():
    """Fresh session: copy checkpoints from an attached previous output, else from the HF backup."""
    if any((RUN / "done").glob("*.json")) or any(RUN.glob("*/last_state.json")):
        print("Resuming from", RUN)
        return
    for prev in sorted(glob.glob("/kaggle/input/*/bosc_run")) + sorted(glob.glob("/kaggle/input/*/*/bosc_run")):
        print("Restoring from attached output:", prev)
        shutil.copytree(prev, RUN, dirs_exist_ok=True)
        out_prev = Path(prev).parent / "bosc_out"
        if out_prev.exists():
            shutil.copytree(out_prev, OUT, dirs_exist_ok=True)
        return
    repo = hf_repo()
    if repo:
        try:
            from huggingface_hub import snapshot_download
            snapshot_download(repo_id=repo, local_dir=str(WORK), token=HF_TOKEN,
                              allow_patterns=["bosc_run/**", "bosc_out/**"])
            print("Restored from HF backup:", repo)
        except Exception as e:
            print("Nothing restored from HF:", e)


def is_done(name):
    return (RUN / "done" / f"{name}.json").exists()


def mark_done(name, info=None):
    (RUN / "done").mkdir(exist_ok=True)
    (RUN / "done" / f"{name}.json").write_text(json.dumps(info or {}, indent=1, default=float))


def save_json(path, obj, compact=False):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, separators=(",", ":")) if compact else json.dumps(obj, indent=1, default=float),
                    encoding="utf-8")


def make_zip():
    tmp = ZIP.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(OUT.rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(OUT).as_posix())
    os.replace(tmp, ZIP)
    print(f"{ZIP.name}: {ZIP.stat().st_size / 1e6:.1f} MB")


def stage_end(name, info=None):
    mark_done(name, info)
    save_json(OUT / "data-eval/results/metrics_v2.json", RES)
    make_zip()
    sync(f"after {name}")


restore()
RES = json.loads((OUT / "data-eval/results/metrics_v2.json").read_text()) if (OUT / "data-eval/results/metrics_v2.json").exists() else {}
RES.setdefault("rows", {})
RES["config"] = {k: v for k, v in CFG.items()}
RES["config"]["QUICK"] = QUICK

# %% [markdown]
# ## Data
# - Template calls from `backend/gen_calls.py`. Train/dev/test use different phrase banks.
# - BothBosu/scam-dialogue from Hugging Face, with the same 85/15 train/dev cut as `train.py`.
# - Plus any attached `bosc_*.jsonl` files.
# - **calib** = 20% of the training calls, which the teacher never sees (Stage 3 and isotonic are trained on it).

# %%
if not (REPO_DIR / "backend" / "bosc_core.py").exists():
    sh(f"git clone --depth 1 {CFG['REPO']} {REPO_DIR}")
sys.path.insert(0, str(REPO_DIR / "backend"))
# @@INLINE backend/stream_features.py
GEN = REPO_DIR / "data-eval" / "generated"
RAW = REPO_DIR / "data-eval" / "raw"
if not (GEN / "train.jsonl").exists():
    sh(f'cd "{REPO_DIR / "backend"}" && python gen_calls.py "{GEN}"')
RAW.mkdir(parents=True, exist_ok=True)
for f in ("train", "test"):
    p = RAW / f"scam-dialogue_{f}.csv"
    if not p.exists():
        import urllib.request
        urllib.request.urlretrieve(
            f"https://huggingface.co/datasets/BothBosu/scam-dialogue/resolve/main/scam-dialogue_{f}.csv", p)

from bosc_core import Model, StreamingScorer, TacticDetector  # noqa: E402
from train import call_metrics, caller_utts, load_hf, load_jsonl, pick_threshold, summarize  # noqa: E402

TACTICS_JSON = json.loads((REPO_DIR / "frontend/model/tactics.json").read_text(encoding="utf-8"))
DET = TacticDetector(TACTICS_JSON)


def find_inputs(pattern):
    if not ON_KAGGLE:
        return sorted(glob.glob(str(WORK / "inputs" / pattern)))
    return sorted(glob.glob(f"/kaggle/input/**/{pattern}", recursive=True))


def load_many(pattern):
    calls = []
    for p in find_inputs(pattern):
        calls += load_jsonl(p)
        print(f"  + {len(load_jsonl(p))} calls from {p}")
    return calls


gen_train = load_jsonl(GEN / "train.jsonl")
gen_dev = load_jsonl(GEN / "dev.jsonl")
gen_test = load_jsonl(GEN / "test.jsonl")
hf_tr = load_hf(RAW / "scam-dialogue_train.csv", "train")
hf_test = load_hf(RAW / "scam-dialogue_test.csv", "test")
random.Random(7).shuffle(hf_tr)                       # identical to train.py
cut = int(0.85 * len(hf_tr))
hf_train, hf_dev = hf_tr[:cut], hf_tr[cut:]
extra_train = load_many("bosc_train_*.jsonl")

pool = gen_train + hf_train + extra_train
rng = random.Random(21)
by_type = {}
for c in pool:
    by_type.setdefault(c["type"], []).append(c)
teacher_train, calib = [], []
for t, cs in sorted(by_type.items()):
    rng.shuffle(cs)
    k = int(round(CFG["CALIB_FRAC"] * len(cs)))
    calib += cs[:k]
    teacher_train += cs[k:]
rng.shuffle(teacher_train)
rng.shuffle(calib)

SPLIT_CALLS = {"teacher_train": teacher_train, "calib": calib, "dev": gen_dev + hf_dev,
               "test_gen_heldout_phrasing": gen_test, "test_hf": hf_test}
for name, pat in (("lockbox_human", "lockbox*.jsonl"), ("redteam", "redteam*.jsonl"), ("realaudio", "realaudio*.jsonl")):
    cs = load_many(pat)
    if cs:
        SPLIT_CALLS[name] = cs
if CFG.get("MAX_CALLS"):
    for k in SPLIT_CALLS:
        SPLIT_CALLS[k] = SPLIT_CALLS[k][:CFG["MAX_CALLS"]]
TEST_SPLITS = [k for k in SPLIT_CALLS if k not in ("teacher_train", "calib", "dev")]
EVAL_SPLITS = ["dev"] + TEST_SPLITS

from stream_features import TACTICS, LABEL_ORDER  # noqa: E402


def build_split(calls, W=CFG["WINDOW"]):
    items, slices, regex = [], [], []
    for ci, c in enumerate(calls):
        u = [t for t in c["turns"] if t["speaker"] == "caller"]
        fs = c["first_suspicious_caller_turn"]
        has_tac = c.get("source") != "hf"
        a = len(items)
        rr = []
        for i in range(len(u)):
            tac = set(u[i].get("tactics") or [])
            items.append({"call": ci, "step": i, "utts": [t["text"] for t in u[max(0, i - W + 1): i + 1]],
                          "y": [int(c["label"] == 1 and fs is not None and i >= fs)] + [int(t in tac) for t in TACTICS],
                          "mask": [1] + [int(has_tac)] * 8})
            rr.append(sorted(DET.detect(u[i]["text"])))
        slices.append((a, len(items)))
        regex.append(rr)
    return {"calls": calls, "items": items, "slices": slices, "regex": regex,
            "Y": np.array([it["y"] for it in items], dtype=np.float32).reshape(-1, 9),
            "M": np.array([it["mask"] for it in items], dtype=np.float32).reshape(-1, 9)}


S = {k: build_split(v) for k, v in SPLIT_CALLS.items()}


def texts(split):
    return [CFG["JOIN"].join(it["utts"]) for it in S[split]["items"]]


def per_call(probs, split):
    return [probs[a:b].tolist() for a, b in S[split]["slices"]]


for k, v in S.items():
    n_scam = sum(c["label"] == 1 for c in v["calls"])
    print(f"{k:28s} calls {len(v['calls']):5d} (scam {n_scam:4d})  windows {len(v['items']):6d}")
RES["data"] = {k: {"calls": len(v["calls"]), "scam_calls": int(sum(c["label"] == 1 for c in v["calls"])),
                   "windows": len(v["items"])} for k, v in S.items()}

# %% [markdown]
# ## Shared: Stage 3 code (the same file as `backend/stream_features.py`) + evaluation helpers

# %%
import stream_features as sf  # noqa: E402

from sklearn.metrics import average_precision_score, f1_score, roc_auc_score  # noqa: E402


def sig(x):
    return 1.0 / (1.0 + np.exp(-np.asarray(x, dtype=np.float64)))


def dev_key(s):
    e = s["median_earliness_turns"]
    return (round(s["recall_pause"], 3), round(s["recall_warn"], 3), -(e if e is not None else 99))


def thresholds_from_dev(dev_traces):
    peaks = [max(t) if t else 0.0 for t, c in zip(dev_traces, S["dev"]["calls"]) if c["label"] == 0]
    pause = pick_threshold(peaks, 0.02)
    return min(pick_threshold(peaks, 0.05), pause), pause


def window_scores(probs, split):
    y = S[split]["Y"][:, 0]
    if len(set(y.tolist())) < 2:
        return None, None
    return float(roc_auc_score(y, probs[:, 0])), float(average_precision_score(y, probs[:, 0]))


def tactic_macro_f1(probs, split):
    M, Y = S[split]["M"], S[split]["Y"]
    rows = M[:, 1] > 0
    if not rows.any():
        return None
    return float(f1_score(Y[rows, 1:], (probs[rows, 1:] > 0.5).astype(int), average="macro", zero_division=0))


def evaluate(trace_fn, probs_by_split=None):
    """trace_fn(split) -> per-call risk traces. Thresholds from dev only (policy in backend/README.md §3)."""
    dv = trace_fn("dev")
    warn, pause = thresholds_from_dev(dv)
    out = {"thresholds": {"warn": warn, "pause": pause}}
    for sp in EVAL_SPLITS:
        tr = dv if sp == "dev" else trace_fn(sp)
        rows = call_metrics(tr, S[sp]["calls"], warn, pause)
        s = summarize(rows)
        if probs_by_split is not None and sp in probs_by_split:
            s["window_roc_auc"], s["window_pr_auc"] = window_scores(probs_by_split[sp], sp)
            s["tactic_macro_f1"] = tactic_macro_f1(probs_by_split[sp], sp)
        per = {}
        for r in rows:
            per.setdefault(r["type"], []).append(r["pause_at"] is not None)
        s["per_type_flag_rate_pause"] = {t: float(np.mean(v)) for t, v in sorted(per.items())}
        out[sp] = s
    return out


def simple_traces(P_calls, R_calls, tw, up=0.6, down=0.15):
    """Stage 0-style scorer on any window model: raw = (1-tw)·p + tw·min(1, regex pressure tactics/4)."""
    out = []
    for P, R in zip(P_calls, R_calls):
        risk, seen, tr = 0.0, set(), []
        for i in range(len(P)):
            seen |= set(R[i])
            raw = (1 - tw) * P[i][0] + tw * min(1.0, len(seen & sf.PRESSURE) / 4.0)
            risk += (up if raw > risk else down) * (raw - risk)
            tr.append(risk)
        out.append(tr)
    return out


def eval_window_model(probs_by_split, label):
    """probs_by_split: split -> (N, 9) sigmoids. Picks the tactic weight on dev, evaluates every split."""
    best = None
    for tw in (0.0, 0.15, 0.3):
        def fn(sp, tw=tw):
            return simple_traces(per_call(probs_by_split[sp], sp), S[sp]["regex"], tw)
        ev = evaluate(fn, probs_by_split)
        if best is None or dev_key(ev["dev"]) > dev_key(best[1]["dev"]):
            best = (tw, ev)
    tw, ev = best
    ev["tactic_weight"] = tw
    print(f"[{label}] tw={tw}  dev recall@PAUSE {ev['dev']['recall_pause']:.3f} FA {ev['dev']['false_alarm_pause']:.3f}"
          + "".join(f" | {sp} {ev[sp]['recall_pause']:.3f}/{ev[sp]['false_alarm_pause']:.3f}" for sp in TEST_SPLITS))
    return ev


def step_labels(split):
    return [S[split]["Y"][a:b, 0].astype(int).tolist() for a, b in S[split]["slices"]]


def smooth(qcalls, up, down):
    out = []
    for qs in qcalls:
        r, tr = 0.0, []
        for q in qs:
            r += (up if q > r else down) * (q - r)
            tr.append(r)
        out.append(tr)
    return out


def run_stage3(probs_by_split, label):
    """Train the stream model on calib predictions, tune smoothing + pick LGBM vs EMA on dev, evaluate."""
    Pc = per_call(probs_by_split["calib"], "calib")
    X, y, w, g = sf.step_dataset(Pc, S["calib"]["regex"], step_labels("calib"))
    booster, iso, cal = sf.fit_stream(X, y, w, g)

    def qcalls(mode, sp):
        P = per_call(probs_by_split[sp], sp)
        if mode == "ema":
            return [[row[0] for row in p] for p in P]
        out = []
        for p, r in zip(P, S[sp]["regex"]):
            F = np.array(sf.call_features(p, r))
            out.append(iso.predict(booster.predict_proba(F)[:, 1]).tolist() if len(F) else [])
        return out

    best = {}
    qdev = {m: qcalls(m, "dev") for m in ("lgbm", "ema")}
    for mode in ("lgbm", "ema"):
        for up in (0.5, 0.6, 0.8):
            for down in (0.1, 0.15, 0.25):
                tr = smooth(qdev[mode], up, down)
                warn, pause = thresholds_from_dev(tr)
                s = summarize(call_metrics(tr, S["dev"]["calls"], warn, pause))
                if mode not in best or dev_key(s) > best[mode][0]:
                    best[mode] = (dev_key(s), up, down)
    mode = "lgbm" if best["lgbm"][0] >= best["ema"][0] else "ema"
    _, up, down = best[mode]
    sm = sf.make_export(mode, booster.booster_ if mode == "lgbm" else None, iso if mode == "lgbm" else None, up, down,
                        extra={"window": CFG["WINDOW"], "join": CFG["JOIN"], "max_length": CFG["STUDENT_MAXLEN"],
                               "calibration": cal})
    if mode == "ema":
        print(f"[{label}] LightGBM did not beat a plain EMA of p_scam on dev, so the EMA ships (a valid result).")

    def fn(sp):
        return sf.traces(sm, per_call(probs_by_split[sp], sp), S[sp]["regex"])
    ev = evaluate(fn, probs_by_split)
    sm["warn"], sm["pause"] = ev["thresholds"]["warn"], ev["thresholds"]["pause"]
    ev.update(mode=mode, alpha_up=up, alpha_down=down, calibration=cal,
              dev_key_lgbm=best["lgbm"][0], dev_key_ema=best["ema"][0])
    print(f"[{label}] mode={mode} up={up} down={down} ECE {cal}  dev recall@PAUSE {ev['dev']['recall_pause']:.3f} "
          f"FA {ev['dev']['false_alarm_pause']:.3f}" + "".join(
              f" | {sp} {ev[sp]['recall_pause']:.3f}/{ev[sp]['false_alarm_pause']:.3f}" for sp in TEST_SPLITS))
    return sm, ev


def stream_parity_cases(sm, probs, split, n=100):
    sc = sf.StreamScorer(sm)
    cases = []
    for P, R in list(zip(per_call(probs, split), S[split]["regex"]))[:n]:
        sc.reset()
        rows = [sc.push(P[i], R[i]) for i in range(len(P))]
        cases.append({"probs": P, "regex": R, "features": [r["features"] for r in rows],
                      "q": [r["q"] for r in rows], "risk": [r["risk"] for r in rows], "level": [r["level"] for r in rows]})
    return {"calls": cases}

# %% [markdown]
# ## Stage 0 rows (A: regex tactics only, B: TF-IDF + LR), re-evaluated on these exact splits

# %%
if not is_done("stage0"):
    mj = json.loads((REPO_DIR / "frontend/model/model.json").read_text(encoding="utf-8"))
    sc0 = StreamingScorer(Model(mj), DET, mj["scorer"])

    def stage0_traces(sp):
        out = []
        for c in S[sp]["calls"]:
            sc0.reset()
            out.append([sc0.push(u)["risk"] for u in caller_utts(c)])
        return out

    def rules_traces(sp):
        return simple_traces([[[0.0] * 9 for _ in r] for r in S[sp]["regex"]], S[sp]["regex"], 1.0)

    RES["rows"]["A"] = {"system": "Regex tactics only", **evaluate(rules_traces)}
    RES["rows"]["B"] = {"system": "TF-IDF + LR (Stage 0, shipped today)", **evaluate(stage0_traces)}
    for k in ("A", "B"):
        r = RES["rows"][k]
        print(k, r["system"], "| dev", round(r["dev"]["recall_pause"], 3), round(r["dev"]["false_alarm_pause"], 3),
              "".join(f"| {sp} {r[sp]['recall_pause']:.3f}/{r[sp]['false_alarm_pause']:.3f} " for sp in TEST_SPLITS))
    stage_end("stage0")

# %% [markdown]
# ## Stage 1: sentence embeddings + LR (row C), + scam-library similarity (row D)

# %%
if not is_done("stage1") and not LOCAL_TEST:
    from sentence_transformers import SentenceTransformer
    from sklearn.cluster import KMeans
    from sklearn.linear_model import LogisticRegression
    from sklearn.preprocessing import StandardScaler

    emb_dir = RUN / "emb"
    emb_dir.mkdir(exist_ok=True)
    enc = SentenceTransformer(CFG["EMBEDDER"], device=DEVICE)
    enc.tokenizer.truncation_side = "left"          # keep the newest words
    prefix = "query: " if "e5" in CFG["EMBEDDER"] else ""
    E = {}
    for sp in S:
        f = emb_dir / f"{sp}.npy"
        if f.exists():
            E[sp] = np.load(f)
            continue
        t0 = time.time()
        E[sp] = enc.encode([prefix + t for t in texts(sp)], batch_size=128, normalize_embeddings=True,
                           convert_to_numpy=True, show_progress_bar=False).astype(np.float32)
        np.save(f, E[sp])
        print(f"encoded {sp}: {len(E[sp])} windows in {time.time() - t0:.0f}s")
    t0 = time.time()
    enc.encode(texts("dev")[:200], batch_size=1, normalize_embeddings=True)
    encode_ms = (time.time() - t0) / 200 * 1000

    # scam library: train scam windows at/after the first suspicious turn -> k-means -> nearest window per centroid
    ytr = S["teacher_train"]["Y"][:, 0]
    idx = np.where(ytr == 1)[0]
    km = KMeans(n_clusters=min(CFG["LIBRARY_K"], len(idx)), n_init=3, random_state=13).fit(E["teacher_train"][idx])
    nearest = [idx[np.argmax(E["teacher_train"][idx] @ c / np.linalg.norm(c))] for c in km.cluster_centers_]
    lib = E["teacher_train"][nearest]
    lib_types = [S["teacher_train"]["calls"][S["teacher_train"]["items"][i]["call"]]["type"] for i in nearest]
    type_list = sorted(set(lib_types))
    (OUT / "data-eval/results").mkdir(parents=True, exist_ok=True)
    tt = texts("teacher_train")
    (OUT / "data-eval/results/scam_library_sample.txt").write_text(
        "\n".join(f"[{lib_types[j]}] {tt[i]}" for j, i in enumerate(nearest[:60])), encoding="utf-8")

    def lib_feats(X):
        sims = X @ lib.T
        top = np.sort(sims, axis=1)[:, -3:]
        onehot = np.zeros((len(X), len(type_list)), dtype=np.float32)
        best = np.argmax(sims, axis=1)
        for r, b in enumerate(best):
            onehot[r, type_list.index(lib_types[b])] = 1
        return np.hstack([sims.max(1, keepdims=True), top.mean(1, keepdims=True), onehot])

    LF = {sp: lib_feats(E[sp]) for sp in S}
    scaler = StandardScaler().fit(LF["teacher_train"])

    def fit_lr(Xtr, ytr_, Xdev, ydev):
        best = None
        for C in (0.25, 1.0, 4.0):
            m = LogisticRegression(C=C, class_weight="balanced", max_iter=3000).fit(Xtr, ytr_)
            ap = average_precision_score(ydev, m.predict_proba(Xdev)[:, 1])
            if best is None or ap > best[0]:
                best = (ap, C, m)
        return best

    def as_probs(p):
        out = np.zeros((len(p), 9), dtype=np.float32)
        out[:, 0] = p
        return out

    ydev = S["dev"]["Y"][:, 0]
    apC, C_C, mC = fit_lr(E["teacher_train"], ytr, E["dev"], ydev)
    probsC = {sp: as_probs(mC.predict_proba(E[sp])[:, 1]) for sp in S}
    XD = {sp: np.hstack([E[sp], scaler.transform(LF[sp])]) for sp in S}
    apD, C_D, mD = fit_lr(XD["teacher_train"], ytr, XD["dev"], ydev)
    probsD = {sp: as_probs(mD.predict_proba(XD[sp])[:, 1]) for sp in S}
    RES["rows"]["C"] = {"system": f"Embeddings ({CFG['EMBEDDER'].split('/')[-1]}) + LR", "C": C_C,
                        "encode_ms_per_window_cpu_or_gpu": encode_ms, **eval_window_model(probsC, "C")}
    RES["rows"]["D"] = {"system": "Embeddings + LR + scam library", "C": C_D, **eval_window_model(probsD, "D")}

    # leave-one-scam-type-out (row C config, template data)
    loto = {}
    tr_calls = S["teacher_train"]["calls"]
    for st in sorted({c["type"] for c in gen_test if c["label"] == 1}):
        keep = np.array([tr_calls[it["call"]]["type"] != st for it in S["teacher_train"]["items"]])
        m = LogisticRegression(C=C_C, class_weight="balanced", max_iter=3000).fit(E["teacher_train"][keep], ytr[keep])
        pd_ = as_probs(m.predict_proba(E["dev"])[:, 1])
        pt_ = as_probs(m.predict_proba(E["test_gen_heldout_phrasing"])[:, 1])
        tw = RES["rows"]["C"]["tactic_weight"]
        dev_ok = [i for i, c in enumerate(S["dev"]["calls"]) if c["type"] != st]
        dtr = simple_traces(per_call(pd_, "dev"), S["dev"]["regex"], tw)
        peaks = [max(dtr[i]) for i in dev_ok if S["dev"]["calls"][i]["label"] == 0]
        pause = pick_threshold(peaks, 0.02)
        ttr = simple_traces(per_call(pt_, "test_gen_heldout_phrasing"), S["test_gen_heldout_phrasing"]["regex"], tw)
        hits = [max(t) >= pause for t, c in zip(ttr, S["test_gen_heldout_phrasing"]["calls"]) if c["type"] == st and t]
        loto[st] = float(np.mean(hits)) if hits else None
    RES["rows"]["C"]["leave_one_type_out_recall_pause"] = loto
    print("LOTO (row C):", loto)
    del enc
    stage_end("stage1")

# %% [markdown]
# ## Training loop shared by the teacher (Stage 2) and the student (Stage 4)
# - Plain PyTorch, so it doesn't depend on the `Trainer` API version.
# - Checkpoints every `CKPT_EVERY_STEPS` and at each epoch end (`last_model.pt`, `last_optim.pt`, `last_state.json`), and resumes mid-epoch.
# - The best epoch by dev window PR-AUC is saved with `save_pretrained` to `best/`.

# %%
def augment(utts, rng):
    utts = list(utts)
    if len(utts) > 1 and rng.random() < 0.1:          # drop one older utterance (never the newest: labels belong to it)
        utts.pop(rng.randrange(len(utts) - 1))
    s = CFG["JOIN"].join(utts)
    if rng.random() < 0.3:
        s = s.lower()
    if rng.random() < 0.3:                             # ASR-like: no punctuation
        s = "".join(ch for ch in s if ch.isalnum() or ch.isspace() or ch == "|")
    return s


def load_tokenizer(model_id):
    from transformers import AutoTokenizer
    try:
        tok = AutoTokenizer.from_pretrained(model_id, use_fast=True)
    except Exception as e:
        print("fast tokenizer failed, using slow:", e)
        tok = AutoTokenizer.from_pretrained(model_id, use_fast=False)
    tok.truncation_side = "left"
    return tok


@torch.no_grad() if torch else (lambda f: f)
def predict_logits(model, tok, txts, maxlen, bs=128):
    model.eval()
    order = np.argsort([len(t) for t in txts])
    out = np.zeros((len(txts), 9), dtype=np.float32)
    use_amp = DEVICE == "cuda"
    for i in range(0, len(txts), bs):
        idx = order[i:i + bs]
        enc = tok([txts[j] for j in idx], truncation=True, max_length=maxlen, padding=True, return_tensors="pt").to(DEVICE)
        with torch.autocast(device_type="cuda", dtype=torch.float16, enabled=use_amp):
            out[idx] = model(**enc).logits.float().cpu().numpy()
    return out


def train_model(tag, model_id, split, *, epochs, lr, bs, maxlen, seed, teacher_logits=None):
    """Multi-label (scam + 8 tactics) fine-tuning with masked tactic loss; KD when teacher_logits is given."""
    import torch.nn.functional as F
    from transformers import AutoModelForSequenceClassification, get_linear_schedule_with_warmup

    ck = RUN / tag
    ck.mkdir(parents=True, exist_ok=True)
    if (ck / "best" / "done.json").exists() and (ck / "best" / "config.json").exists():
        print(f"[{tag}] already trained")
        return json.loads((ck / "best" / "done.json").read_text())
    random.seed(seed); np.random.seed(seed); torch.manual_seed(seed)
    tok = load_tokenizer(model_id)
    model = AutoModelForSequenceClassification.from_pretrained(
        model_id, num_labels=9, problem_type="multi_label_classification", ignore_mismatched_sizes=True,
        id2label=dict(enumerate(LABEL_ORDER)), label2id={l: i for i, l in enumerate(LABEL_ORDER)})
    # transformers v5 keeps the checkpoint dtype (fp16 for some hub models). Master weights must be fp32;
    # autocast handles fp16 compute. Otherwise GradScaler fails with "Attempting to unscale FP16 gradients".
    model = model.float().to(DEVICE)
    items = S[split]["items"]
    Y = torch.tensor(S[split]["Y"])
    Mk = torch.tensor(S[split]["M"])
    TL = torch.tensor(teacher_logits) if teacher_logits is not None else None
    n = len(items)
    spe = math.ceil(n / bs)
    total = spe * epochs
    no_decay = ("bias", "LayerNorm.weight", "layernorm", "layer_norm")
    groups = [{"params": [p for nm, p in model.named_parameters() if not any(x in nm for x in no_decay)], "weight_decay": 0.01},
              {"params": [p for nm, p in model.named_parameters() if any(x in nm for x in no_decay)], "weight_decay": 0.0}]
    opt = torch.optim.AdamW(groups, lr=lr)
    sched = get_linear_schedule_with_warmup(opt, int(0.06 * total), total)
    use_amp = DEVICE == "cuda"
    # bf16 only on real bf16 hardware (A100/L4/H100); T4/P100 use fp16 + GradScaler
    amp_dtype = torch.bfloat16 if (use_amp and torch.cuda.get_device_capability()[0] >= 8) else torch.float16
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp and amp_dtype == torch.float16)
    pos = float(Y[:, 0].sum())
    pos_weight = torch.tensor([(n - pos) / max(pos, 1.0)], device=DEVICE)
    state = {"epoch": 0, "step_in_epoch": 0, "global": 0, "best": -1.0, "bad": 0, "stopped": False, "history": [],
             "nonfinite": 0}

    def save_last():
        torch.save(model.state_dict(), ck / "last_model.tmp")
        os.replace(ck / "last_model.tmp", ck / "last_model.pt")
        torch.save({"opt": opt.state_dict(), "sched": sched.state_dict(), "scaler": scaler.state_dict()}, ck / "last_optim.tmp")
        os.replace(ck / "last_optim.tmp", ck / "last_optim.pt")
        (ck / "last_state.json").write_text(json.dumps(state))

    if (ck / "last_state.json").exists() and (ck / "last_model.pt").exists():
        state = json.loads((ck / "last_state.json").read_text())
        model.load_state_dict(torch.load(ck / "last_model.pt", map_location=DEVICE))
        if (ck / "last_optim.pt").exists():
            o = torch.load(ck / "last_optim.pt", map_location=DEVICE, weights_only=False)
            opt.load_state_dict(o["opt"]); sched.load_state_dict(o["sched"]); scaler.load_state_dict(o["scaler"])
        else:                                         # restored from HF without optimizer: fast-forward the schedule
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                for _ in range(state["global"]):
                    sched.step()
        print(f"[{tag}] resumed at epoch {state['epoch']} step {state['step_in_epoch']} (global {state['global']})")

    dev_txt = texts("dev")
    t_start = time.time()
    while state["epoch"] < epochs and not state["stopped"]:
        ep = state["epoch"]
        perm = np.random.default_rng(seed * 1000 + ep).permutation(n)
        model.train()
        for b in range(state["step_in_epoch"], spe):
            idx = perm[b * bs:(b + 1) * bs]
            # augmentation is per (epoch, batch) seed so a resumed run sees the same batches
            rng = random.Random(seed * 1_000_003 + ep * 10_007 + b)
            batch_txt = [augment(items[i]["utts"], rng) for i in idx]
            enc = tok(batch_txt, truncation=True, max_length=maxlen, padding=True, return_tensors="pt").to(DEVICE)
            y = Y[idx].to(DEVICE)
            mk = Mk[idx].to(DEVICE)
            with torch.autocast(device_type="cuda", dtype=amp_dtype, enabled=use_amp):
                logits = model(**enc).logits
            logits = logits.float()
            hard_scam = F.binary_cross_entropy_with_logits(logits[:, 0], y[:, 0], pos_weight=pos_weight)
            bt = F.binary_cross_entropy_with_logits(logits[:, 1:], y[:, 1:], reduction="none") * mk[:, 1:]
            hard_tac = bt.sum() / mk[:, 1:].sum().clamp(min=1.0)
            if TL is None:
                loss = hard_scam + 1.0 * hard_tac
            else:
                T, a = CFG["KD_T"], CFG["KD_ALPHA"]
                tl = TL[idx].to(DEVICE)
                kd = F.binary_cross_entropy_with_logits(logits / T, torch.sigmoid(tl / T)) * T * T
                loss = a * kd + (1 - a) * (hard_scam + hard_tac)
            if not torch.isfinite(loss):
                state["nonfinite"] += 1
                opt.zero_grad(set_to_none=True)
                if state["nonfinite"] > 50:
                    raise RuntimeError(f"[{tag}] loss keeps going NaN. Lower the LR, or use roberta-base as the teacher.")
                continue
            scaler.scale(loss).backward()
            scaler.unscale_(opt)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(opt)
            scaler.update()
            opt.zero_grad(set_to_none=True)
            sched.step()
            state["step_in_epoch"] = b + 1
            state["global"] += 1
            if state["global"] % 50 == 0:
                el = time.time() - t_start
                print(f"[{tag}] ep {ep + 1}/{epochs} step {b + 1}/{spe} loss {loss.item():.4f} ({el / 60:.1f} min)")
            if state["global"] % CFG["CKPT_EVERY_STEPS"] == 0:
                save_last()
        # epoch end: dev window PR-AUC, keep best, early stopping
        dl = predict_logits(model, tok, dev_txt, maxlen)
        roc, pr = window_scores(sig(dl), "dev")
        tf1 = tactic_macro_f1(sig(dl), "dev")
        state["history"].append({"epoch": ep + 1, "dev_pr_auc": pr, "dev_roc_auc": roc, "dev_tactic_macro_f1": tf1})
        print(f"[{tag}] epoch {ep + 1}: dev PR-AUC {pr:.4f} ROC-AUC {roc:.4f} tactic macro-F1 {tf1}")
        if pr > state["best"]:
            state["best"], state["bad"] = pr, 0
            model.save_pretrained(ck / "best")
            tok.save_pretrained(ck / "best")
        else:
            state["bad"] += 1
            if state["bad"] >= CFG["PATIENCE"]:
                state["stopped"] = True
        state["epoch"], state["step_in_epoch"] = ep + 1, 0
        save_last()
        sync(f"{tag} epoch {ep + 1}")
    info = {"best_dev_pr_auc": state["best"], "history": state["history"], "model_id": model_id, "seed": seed,
            "minutes": (time.time() - t_start) / 60}
    (ck / "best" / "done.json").write_text(json.dumps(info, indent=1))
    for f in ("last_optim.pt",):                      # free disk; the best weights are in best/
        (ck / f).unlink(missing_ok=True)
    del model, opt
    torch.cuda.empty_cache() if DEVICE == "cuda" else None
    return info


def load_best(tag):
    from transformers import AutoModelForSequenceClassification
    d = RUN / tag / "best"
    return AutoModelForSequenceClassification.from_pretrained(d).float().to(DEVICE), load_tokenizer(str(d))

# %% [markdown]
# ## Stage 2: teacher (row E). Window model with 9 logits: scam + 8 tactics.

# %%
TP = RUN / "teacher_preds"
if not is_done("stage2"):
    TP.mkdir(exist_ok=True)
    if LOCAL_TEST:   # plumbing check only: fake "teacher" from Stage 0 p + regex tactics
        mj = json.loads((REPO_DIR / "frontend/model/model.json").read_text(encoding="utf-8"))
        m0 = Model(mj)
        for sp in S:
            rows = []
            for it in S[sp]["items"]:
                p = min(max(m0.proba(" ".join(it["utts"])), 1e-6), 1 - 1e-6)
                found = set(DET.detect(it["utts"][-1]))
                rows.append([math.log(p / (1 - p))] + [2.0 if t in found else -3.0 for t in TACTICS])
            np.save(TP / f"{sp}.npy", np.array(rows, dtype=np.float32).reshape(-1, 9))
        best_tag = "fake_teacher"
        teacher_runs = {}
    else:
        teacher_runs = {}
        for mid in CFG["TEACHERS"]:
            tag = f"teacher_{mid.split('/')[-1]}_s{CFG['TEACHER_SEEDS'][0]}"
            lr = 3e-5 if "small" in mid else CFG["TEACHER_LR"]
            teacher_runs[tag] = train_model(tag, mid, "teacher_train", epochs=CFG["TEACHER_EPOCHS"], lr=lr,
                                            bs=CFG["TEACHER_BS"], maxlen=CFG["TEACHER_MAXLEN"], seed=CFG["TEACHER_SEEDS"][0])
        best_tag = max(teacher_runs, key=lambda t: teacher_runs[t]["best_dev_pr_auc"])
        best_mid = teacher_runs[best_tag]["model_id"]
        for seed in CFG["TEACHER_SEEDS"][1:]:
            tag = f"teacher_{best_mid.split('/')[-1]}_s{seed}"
            teacher_runs[tag] = train_model(tag, best_mid, "teacher_train", epochs=CFG["TEACHER_EPOCHS"],
                                            lr=3e-5 if "small" in best_mid else CFG["TEACHER_LR"], bs=CFG["TEACHER_BS"],
                                            maxlen=CFG["TEACHER_MAXLEN"], seed=seed)
        model, tok = load_best(best_tag)
        for sp in S:
            np.save(TP / f"{sp}.npy", predict_logits(model, tok, texts(sp), CFG["TEACHER_MAXLEN"]))
            print("teacher preds", sp)
        del model
    TPROBS = {sp: sig(np.load(TP / f"{sp}.npy")) for sp in S}
    seeds_pr = [v["best_dev_pr_auc"] for k, v in teacher_runs.items() if k.startswith(best_tag.rsplit("_s", 1)[0])]
    RES["rows"]["E"] = {"system": f"Teacher {best_tag}", "teacher_runs": teacher_runs,
                        "dev_pr_auc_mean": float(np.mean(seeds_pr)) if seeds_pr else None,
                        "dev_pr_auc_std": float(np.std(seeds_pr)) if seeds_pr else None,
                        "dev_window_f1": float(f1_score(S["dev"]["Y"][:, 0], TPROBS["dev"][:, 0] > 0.5)),
                        **eval_window_model(TPROBS, "E")}
    RES["teacher_tag"] = best_tag
    stage_end("stage2", {"best_tag": best_tag})
TPROBS = {sp: sig(np.load(TP / f"{sp}.npy")) for sp in S}

# %% [markdown]
# ## Stage 3: streaming call-level model on teacher outputs (row F)

# %%
if not is_done("stage3"):
    smF, evF = run_stage3(TPROBS, "F")
    RES["rows"]["F"] = {"system": "Teacher + stream model (LightGBM + isotonic)" if evF["mode"] == "lgbm"
                        else "Teacher + EMA (LightGBM lost on dev)", **evF}
    save_json(RUN / "stream_model_teacher.json", smF, compact=True)
    stage_end("stage3")

# %% [markdown]
# ## Stage 4: distil into MiniLM → ONNX → int8. Retrain Stage 3 on the int8 student outputs (row G = what ships).

# %%
STU_DIR = OUT / "frontend/model/student"


def export_onnx(best_dir, path):
    from transformers import AutoModelForSequenceClassification
    m = AutoModelForSequenceClassification.from_pretrained(best_dir, attn_implementation="eager").float().eval().cpu()
    tok = load_tokenizer(str(best_dir))

    class Wrap(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, input_ids, attention_mask, token_type_ids):
            return self.m(input_ids=input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids).logits

    enc = tok(["hello sir | this is from the bank, kindly share the otp"], return_tensors="pt")
    tti = enc.get("token_type_ids", torch.zeros_like(enc["input_ids"]))
    names = ["input_ids", "attention_mask", "token_type_ids"]
    kw = dict(input_names=names, output_names=["logits"], opset_version=17, do_constant_folding=True,
              dynamic_axes={**{k: {0: "batch", 1: "sequence"} for k in names}, "logits": {0: "batch"}})
    args = (enc["input_ids"], enc["attention_mask"], tti)
    try:
        torch.onnx.export(Wrap(m), args, str(path), dynamo=False, **kw)
    except TypeError:
        torch.onnx.export(Wrap(m), args, str(path), **kw)
    with torch.no_grad():
        ref = Wrap(m)(*args).numpy()
    return ref, {k: v.numpy() for k, v in zip(names, args)}


def ort_session(path, threads=None):
    import onnxruntime as ort
    so = ort.SessionOptions()
    if threads:
        so.intra_op_num_threads = threads
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def encode_ids(tok, text, maxlen):
    return tok(text, truncation=True, max_length=maxlen)["input_ids"]


def ort_logits(sess, ids):
    n = len(ids)
    feed = {"input_ids": np.array([ids], dtype=np.int64), "attention_mask": np.ones((1, n), dtype=np.int64),
            "token_type_ids": np.zeros((1, n), dtype=np.int64)}
    names = {i.name for i in sess.get_inputs()}
    return sess.run(["logits"], {k: v for k, v in feed.items() if k in names})[0][0]


if not is_done("stage4") and not LOCAL_TEST:
    from onnxruntime.quantization import QuantType, quantize_dynamic
    stag = f"student_{CFG['STUDENT'].split('/')[-1]}_s13"
    tl_train = np.load(TP / "teacher_train.npy")
    sinfo = train_model(stag, CFG["STUDENT"], "teacher_train", epochs=CFG["STUDENT_EPOCHS"], lr=CFG["STUDENT_LR"],
                        bs=CFG["STUDENT_BS"], maxlen=CFG["STUDENT_MAXLEN"], seed=13, teacher_logits=tl_train)
    best_dir = RUN / stag / "best"
    model, tok = load_best(stag)
    sp_fp32 = {sp: sig(predict_logits(model, tok, texts(sp), CFG["STUDENT_MAXLEN"])) for sp in ("dev",)}
    del model

    # ONNX export + int8 dynamic quantisation
    onnx_dir = RUN / "student_onnx"
    onnx_dir.mkdir(exist_ok=True)
    ref, feed = export_onnx(best_dir, onnx_dir / "model.onnx")
    quantize_dynamic(str(onnx_dir / "model.onnx"), str(onnx_dir / "model_quantized.onnx"), weight_type=QuantType.QInt8)
    s32, s8 = ort_session(onnx_dir / "model.onnx"), ort_session(onnx_dir / "model_quantized.onnx")
    ids0 = feed["input_ids"][0].tolist()
    onnx_check = {"torch_vs_onnx_fp32_max_abs": float(np.abs(ort_logits(s32, ids0) - ref[0]).max()),
                  "fp32_vs_int8_max_abs": float(np.abs(ort_logits(s32, ids0) - ort_logits(s8, ids0)).max())}
    print("ONNX check:", onnx_check)

    # int8 predictions exactly as the browser computes them (one window at a time, no padding)
    tok = load_tokenizer(str(best_dir))
    SPROBS = {}
    for sp in S:
        if sp == "teacher_train":
            continue
        f = RUN / "student_preds" / f"{sp}.npy"
        if f.exists():
            SPROBS[sp] = np.load(f)
            continue
        t0 = time.time()
        L = np.array([ort_logits(s8, encode_ids(tok, t, CFG["STUDENT_MAXLEN"])) for t in texts(sp)], dtype=np.float32)
        f.parent.mkdir(exist_ok=True)
        np.save(f, sig(L).astype(np.float32))
        SPROBS[sp] = np.load(f)
        print(f"int8 student preds {sp}: {len(L)} windows in {time.time() - t0:.0f}s")

    # gate: student dev window F1 >= teacher F1 - 1 point
    ydev = S["dev"]["Y"][:, 0]
    f1_t = float(f1_score(ydev, TPROBS["dev"][:, 0] > 0.5))
    f1_s = float(f1_score(ydev, sp_fp32["dev"][:, 0] > 0.5))
    f1_q = float(f1_score(ydev, SPROBS["dev"][:, 0] > 0.5))
    gate = {"teacher_dev_f1": f1_t, "student_fp32_dev_f1": f1_s, "student_int8_dev_f1": f1_q,
            "passed": f1_s >= f1_t - 0.01, "int8_drop_ok": f1_q >= f1_s - 0.005,
            "table": {name: {"window_f1": float(f1_score(ydev, P["dev"][:, 0] > 0.5)),
                             "pr_auc": window_scores(P["dev"], "dev")[1],
                             "tactic_macro_f1": tactic_macro_f1(P["dev"], "dev")}
                      for name, P in (("teacher", TPROBS), ("student_fp32", sp_fp32), ("student_int8", SPROBS))}}
    print("Gate:", gate)
    if not gate["passed"]:
        print("GATE FAILED: student is more than 1 F1 point below the teacher. See backend/README.md §8 for what to try "
              "(STUDENT_MAXLEN=192, more epochs). The files are still exported so the demo has something to run.")

    # latency: onnxruntime CPU, single thread, one window at a time
    s1 = ort_session(onnx_dir / "model_quantized.onnx", threads=1)
    dev_t = texts("dev")
    lat = []
    for t in dev_t[:60]:
        ids = encode_ids(tok, t, CFG["STUDENT_MAXLEN"])
        t0 = time.perf_counter()
        ort_logits(s1, ids)
        lat.append((time.perf_counter() - t0) * 1000)
    lat = lat[10:]
    latency = {"ort_cpu_1thread_ms_median": float(np.median(lat)), "ort_cpu_1thread_ms_p95": float(np.percentile(lat, 95)),
               "int8_mb": (onnx_dir / "model_quantized.onnx").stat().st_size / 1e6,
               "fp32_mb": (onnx_dir / "model.onnx").stat().st_size / 1e6}
    print("Latency:", latency)

    # Stage 3 retrained on int8 student outputs -> row G (this ships)
    SPROBS_ALL = dict(SPROBS)
    smG, evG = run_stage3(SPROBS_ALL, "G")
    RES["rows"]["G"] = {"system": f"Student {CFG['STUDENT'].split('/')[-1]} int8 + stream model (ships)", **evG,
                        "gate": gate, "latency": latency, "onnx_check": onnx_check, "student_train": sinfo}
    RES["rows"]["G_window_only"] = {"system": "Student int8, simple scorer (no stream model)", **eval_window_model(SPROBS_ALL, "G-simple")}

    # ---- browser files (Transformers.js layout)
    if STU_DIR.exists():
        shutil.rmtree(STU_DIR)
    (STU_DIR / "onnx").mkdir(parents=True)
    shutil.copy(onnx_dir / "model_quantized.onnx", STU_DIR / "onnx" / "model_quantized.onnx")
    for f in best_dir.iterdir():
        if f.name.startswith("tokenizer") or f.name in ("special_tokens_map.json", "vocab.txt", "config.json"):
            shutil.copy(f, STU_DIR / f.name)
    tc = json.loads((STU_DIR / "tokenizer_config.json").read_text())
    tc.update(truncation_side="left", model_max_length=CFG["STUDENT_MAXLEN"])
    save_json(STU_DIR / "tokenizer_config.json", tc)
    save_json(STU_DIR / "label_order.json", LABEL_ORDER)
    smG["model_sha256_16"] = hashlib.sha256((STU_DIR / "onnx" / "model_quantized.onnx").read_bytes()).hexdigest()[:16]
    smG["student"] = CFG["STUDENT"]
    save_json(OUT / "frontend/model/stream_model.json", smG, compact=True)

    # ---- parity cases for node (backend/parity_stream.mjs, backend/parity_onnx.mjs)
    save_json(OUT / "backend/stream_parity_cases.json", stream_parity_cases(smG, SPROBS_ALL["dev"], "dev"), compact=True)
    rng = random.Random(11)
    pt = rng.sample(dev_t, min(200, len(dev_t))) + ["", "OTP", "o.t.p 1,23,456 ok ₹50,000 now!",
                                                    " | ".join(["please stay on the line sir"] * 40)]
    save_json(OUT / "backend/student_parity_cases.json",
              {"max_length": CFG["STUDENT_MAXLEN"],
               "cases": [{"text": t, "ids": encode_ids(tok, t, CFG["STUDENT_MAXLEN"]),
                          "logits": ort_logits(s8, encode_ids(tok, t, CFG["STUDENT_MAXLEN"])).tolist()} for t in pt]},
              compact=True)
    stage_end("stage4", {"gate": gate})
elif not is_done("stage4") and LOCAL_TEST:
    # plumbing check: ship the fake-teacher stream model so the export + parity path is exercised
    smG, evG = run_stage3(TPROBS, "G(local)")
    RES["rows"]["G"] = {"system": "LOCAL TEST (fake teacher, not a real result)", **evG}
    save_json(OUT / "backend/stream_parity_cases.json", stream_parity_cases(smG, TPROBS["dev"], "dev"), compact=True)
    save_json(OUT / "stream_model_LOCALTEST.json", smG, compact=True)
    stage_end("stage4")

# %% [markdown]
# ## Report: `data-eval/results/ablation.md` (+ `metrics_v2.json`) and the download zip

# %%
def pct(x):
    return "n/a" if x is None or x != x else f"{100 * x:.1f}%"


def pci(s, key):
    lo, hi = s.get(key + "_ci", [None, None])
    return f"{pct(s[key])} [{pct(lo)}–{pct(hi)}]"


def write_report():
    L = ["# BOSC: ablation (Stages 0–4)", "",
         f"Generated {time.strftime('%Y-%m-%d %H:%M')} by `backend/kaggle/bosc_kaggle.ipynb`"
         + (" — **QUICK smoke test, numbers are meaningless**" if QUICK else ""), "",
         "> Every set below is synthetic unless it's named lockbox / redteam / realaudio. "
         "Don't headline synthetic numbers. Thresholds were picked on dev (genuine false alarms ≤2% PAUSE / ≤5% WARN) "
         "and applied unchanged everywhere.", "",
         "Data: " + ", ".join(f"{k} {v['calls']} calls / {v['windows']} windows" for k, v in RES["data"].items()), ""]
    order = [k for k in ("A", "B", "C", "D", "E", "F", "G_window_only", "G") if k in RES["rows"]]
    for sp in EVAL_SPLITS:
        L += [f"## {sp}", "", "| Row | System | Recall @PAUSE | False alarm @PAUSE | Recall @WARN | False alarm @WARN | "
              "Earliness (turns) | PAUSE by first ask | Window PR-AUC | Tactic macro-F1 |", "|---|---|---|---|---|---|---|---|---|---|"]
        for k in order:
            r = RES["rows"][k]
            if sp not in r:
                continue
            s = r[sp]
            L.append(f"| {k} | {r['system']} | {pci(s, 'recall_pause')} | {pci(s, 'false_alarm_pause')} | "
                     f"{pci(s, 'recall_warn')} | {pci(s, 'false_alarm_warn')} | {s.get('median_earliness_turns')} | "
                     f"{pct(s.get('pause_by_first_ask_turn'))} | "
                     f"{'n/a' if s.get('window_pr_auc') is None else round(s['window_pr_auc'], 3)} | "
                     f"{'n/a' if s.get('tactic_macro_f1') is None else round(s['tactic_macro_f1'], 3)} |")
        L.append("")
    g = RES["rows"].get("G", {})
    if "gate" in g:
        L += ["## Teacher vs student vs int8 (dev windows)", "", "| Model | Window F1 | PR-AUC | Tactic macro-F1 |", "|---|---|---|---|"]
        for name, v in g["gate"]["table"].items():
            L.append(f"| {name} | {v['window_f1']:.3f} | {v['pr_auc']:.3f} | {v['tactic_macro_f1']} |")
        L += ["", f"Gate (student F1 ≥ teacher F1 − 1 point): **{'PASSED' if g['gate']['passed'] else 'FAILED'}**", "",
              "## Size and speed (onnxruntime CPU, 1 thread, one window)", "",
              f"- int8 model {g['latency']['int8_mb']:.1f} MB (fp32 {g['latency']['fp32_mb']:.1f} MB)",
              f"- {g['latency']['ort_cpu_1thread_ms_median']:.1f} ms median, {g['latency']['ort_cpu_1thread_ms_p95']:.1f} ms p95 per window "
              "(Kaggle CPU; measure on the demo phones too → `latency.md`)",
              f"- ONNX check: {g['onnx_check']}", ""]
    if "F" in RES["rows"] or "G" in RES["rows"]:
        L += ["## Stream model", ""]
        for k in ("F", "G"):
            if k in RES["rows"]:
                r = RES["rows"][k]
                L.append(f"- {k}: mode **{r['mode']}**, α_up {r['alpha_up']}, α_down {r['alpha_down']}, "
                         f"WARN {r['thresholds']['warn']:.3f}, PAUSE {r['thresholds']['pause']:.3f}, calibration {r['calibration']}")
        L.append("")
    if "C" in RES["rows"] and "leave_one_type_out_recall_pause" in RES["rows"]["C"]:
        L += ["## Leave-one-scam-type-out (row C, recall @PAUSE on the unseen type)", "", "| Type | Recall |", "|---|---|"]
        L += [f"| {t} | {pct(v)} |" for t, v in RES["rows"]["C"]["leave_one_type_out_recall_pause"].items()]
        L.append("")
    if "E" in RES["rows"] and RES["rows"]["E"].get("dev_pr_auc_mean") is not None:
        e = RES["rows"]["E"]
        L += [f"Teacher dev PR-AUC over seeds: {e['dev_pr_auc_mean']:.4f} ± {e['dev_pr_auc_std']:.4f}", ""]
    L += ["## Not done in this notebook", "",
          "- Real ASR copies (edge-tts → phone codec → faster-whisper). Training uses text-level ASR noise (lowercase, no punctuation, dropped utterance) instead.",
          "- LLM-generated training data (source A in backend/README.md §4). Attach it as `bosc_train_*.jsonl` and re-run.",
          "- Lockbox / red-team / real-audio rows appear only when those files are attached."]
    p = OUT / "data-eval/results/ablation.md"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L[:40]))


write_report()
save_json(OUT / "data-eval/results/metrics_v2.json", RES)
make_zip()
sync("final")
print("\nDONE. Download bosc_latest.zip from the Output tab and unzip it at the repo root.")
print("Then: npm run parity && node backend/parity_stream.mjs && (npm i -D @huggingface/transformers@3.0.2; node backend/parity_onnx.mjs)")
