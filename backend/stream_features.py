"""Stage 3: streaming call-level model (features -> LightGBM -> isotonic -> smoothing, latched PAUSE).

Python reference. frontend/js/stream-model.js must match it exactly; backend/parity_stream.mjs checks that.
Used by the Kaggle notebook (inlined) and by backend/test_stream_local.py.

Per caller utterance t the window model gives 9 sigmoids: [scam, 8 tactics in TACTICS order].
"""
import json
import math
import time

TACTICS = ["urgency", "threat", "secrecy_isolation", "authority_claim", "credential_request",
           "remote_access", "payment_instruction", "stay_on_line"]
LABEL_ORDER = ["scam"] + TACTICS
FEATURES = (["p_scam"] + [f"tac_{t}" for t in TACTICS] + ["p_max", "p_mean"]
            + [f"tacmax_{t}" for t in TACTICS]
            + ["n_tactics_over_half", "n_windows_p_over_half", "ema_p", "authority_before_threat",
               "threat_before_ask", "since_first_tactic", "t", "regex_n"])
PRESSURE = {"threat", "urgency", "secrecy_isolation", "credential_request",
            "remote_access", "payment_instruction", "stay_on_line"}
LGBM_PARAMS = dict(n_estimators=300, learning_rate=0.05, num_leaves=15, min_child_samples=20,
                   subsample=0.8, subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0,
                   random_state=13, verbose=-1)


class FeatureState:
    """Running features over one call. Only uses utterances <= t."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.t = -1
        self.pmax = 0.0
        self.psum = 0.0
        self.tmax = [0.0] * 8
        self.nwin = 0
        self.ema = 0.0
        self.first_auth = -1
        self.first_threat = -1
        self.first_ask = -1
        self.first_any = -1
        self.regex_seen = set()

    def step(self, probs, regex_tactics=()):
        self.t += 1
        t = self.t
        p = float(probs[0])
        tac = [float(x) for x in probs[1:9]]
        if p > self.pmax:
            self.pmax = p
        self.psum += p
        for k in range(8):
            if tac[k] > self.tmax[k]:
                self.tmax[k] = tac[k]
        if p > 0.5:
            self.nwin += 1
        self.ema = p if t == 0 else 0.5 * p + 0.5 * self.ema
        if self.first_auth < 0 and tac[3] > 0.5:
            self.first_auth = t
        if self.first_threat < 0 and tac[1] > 0.5:
            self.first_threat = t
        if self.first_ask < 0 and (tac[4] > 0.5 or tac[5] > 0.5 or tac[6] > 0.5):
            self.first_ask = t
        if self.first_any < 0 and max(tac) > 0.5:
            self.first_any = t
        for r in regex_tactics:
            self.regex_seen.add(r)
        n_over = 0
        for k in range(8):
            if self.tmax[k] > 0.5:
                n_over += 1
        ab = 1.0 if (self.first_auth >= 0 and self.first_threat >= 0 and self.first_auth <= self.first_threat) else 0.0
        ta = 1.0 if (self.first_threat >= 0 and self.first_ask >= 0 and self.first_threat <= self.first_ask) else 0.0
        since = float(t - self.first_any) if self.first_any >= 0 else -1.0
        regex_n = 0
        for r in self.regex_seen:
            if r in PRESSURE:
                regex_n += 1
        return ([p] + tac + [self.pmax, self.psum / (t + 1)] + list(self.tmax)
                + [float(n_over), float(self.nwin), self.ema, ab, ta, since, float(t), float(regex_n)])


def call_features(P, regex):
    """P: list of 9-prob rows for one call; regex: list of tactic-name lists, same length."""
    fs = FeatureState()
    return [fs.step(P[i], regex[i]) for i in range(len(P))]


def sigmoid(z):
    if z >= 0:
        return 1.0 / (1.0 + math.exp(-z))
    e = math.exp(z)
    return e / (1.0 + e)


def interp(x, X, Y):
    """Piecewise-linear interpolation, clipped. Same algorithm as stream-model.js."""
    n = len(X)
    if n == 0:
        return x
    if x <= X[0]:
        return Y[0]
    if x >= X[n - 1]:
        return Y[n - 1]
    lo, hi = 0, n - 1
    while hi - lo > 1:
        mid = (lo + hi) >> 1
        if X[mid] <= x:
            lo = mid
        else:
            hi = mid
    return Y[lo] + (Y[hi] - Y[lo]) * (x - X[lo]) / (X[hi] - X[lo])


# ------------------------------------------------------------------ trees

def _strip(node):
    if "leaf_value" in node:
        return {"v": node["leaf_value"]}
    assert node["decision_type"] == "<=", node["decision_type"]
    return {"f": node["split_feature"], "th": node["threshold"], "dl": bool(node.get("default_left", True)),
            "l": _strip(node["left_child"]), "r": _strip(node["right_child"])}


def export_trees(booster):
    d = booster.dump_model()
    return [_strip(t["tree_structure"]) for t in d["tree_info"]]


def tree_raw(trees, x):
    s = 0.0
    for node in trees:
        while "v" not in node:
            v = x[node["f"]]
            if v != v:  # NaN
                node = node["l"] if node["dl"] else node["r"]
            else:
                node = node["l"] if v <= node["th"] else node["r"]
        s += node["v"]
    return s


class StreamScorer:
    """Exported stream model -> per-step calibrated risk with rise/fall smoothing and latched PAUSE."""

    def __init__(self, sm):
        self.sm = sm
        self.trees = sm.get("trees") or []
        self.isox = sm.get("iso", {}).get("x", [])
        self.isoy = sm.get("iso", {}).get("y", [])
        self.up, self.down = sm["alpha_up"], sm["alpha_down"]
        self.warn, self.pause = sm["warn"], sm["pause"]
        self.reset()

    def reset(self):
        self.fs = FeatureState()
        self.risk = 0.0
        self.latched = False

    def q_from_features(self, f):
        if self.sm["mode"] == "ema":
            return f[0]
        return interp(sigmoid(tree_raw(self.trees, f)), self.isox, self.isoy)

    def level(self):
        if self.latched or self.risk >= self.pause:
            return "PAUSE"
        return "WARN" if self.risk >= self.warn else "SAFE"

    def push(self, probs, regex_tactics=()):
        f = self.fs.step(probs, regex_tactics)
        q = self.q_from_features(f)
        a = self.up if q > self.risk else self.down
        self.risk = self.risk + a * (q - self.risk)
        if self.risk >= self.pause:
            self.latched = True
        return {"q": q, "risk": self.risk, "level": self.level(), "features": f}


def traces(sm, calls_P, calls_regex):
    sc = StreamScorer(sm)
    out = []
    for P, R in zip(calls_P, calls_regex):
        sc.reset()
        out.append([sc.push(P[i], R[i])["risk"] for i in range(len(P))])
    return out


# ------------------------------------------------------------------ training

def step_dataset(calls_P, calls_regex, calls_y):
    """calls_y: per-call list of 0/1 step labels. Returns X, y, w (1/steps), group ids."""
    import numpy as np
    X, y, w, g = [], [], [], []
    for ci, (P, R, Y) in enumerate(zip(calls_P, calls_regex, calls_y)):
        F = call_features(P, R)
        X += F
        y += list(Y)
        w += [1.0 / max(1, len(F))] * len(F)
        g += [ci] * len(F)
    return np.array(X, dtype=np.float64), np.array(y), np.array(w), np.array(g)


def ece(p, y, w=None, bins=10):
    import numpy as np
    p, y = np.asarray(p), np.asarray(y)
    w = np.ones_like(p) if w is None else np.asarray(w)
    e = 0.0
    for b in range(bins):
        m = (p >= b / bins) & ((p < (b + 1) / bins) if b < bins - 1 else (p <= 1))
        if m.any():
            e += w[m].sum() / w.sum() * abs(np.average(p[m], weights=w[m]) - np.average(y[m], weights=w[m]))
    return float(e)


def fit_stream(Xc, yc, wc, gc, params=None):
    """LightGBM on calib + isotonic fitted on 5-fold out-of-fold predictions (grouped by call)."""
    import numpy as np
    from lightgbm import LGBMClassifier
    from sklearn.isotonic import IsotonicRegression
    from sklearn.model_selection import GroupKFold
    params = dict(LGBM_PARAMS, **(params or {}))
    oof = np.zeros(len(yc))
    for tr, te in GroupKFold(n_splits=5).split(Xc, yc, gc):
        m = LGBMClassifier(**params).fit(Xc[tr], yc[tr], sample_weight=wc[tr])
        oof[te] = m.predict_proba(Xc[te])[:, 1]
    # honest ECE: isotonic cross-fitted too (fit on 4 folds of OOF scores, apply to the 5th)
    cal = np.zeros(len(yc))
    for tr, te in GroupKFold(n_splits=5).split(Xc, yc, gc):
        cal[te] = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0).fit(
            oof[tr], yc[tr], sample_weight=wc[tr]).predict(oof[te])
    iso = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0).fit(oof, yc, sample_weight=wc)
    final = LGBMClassifier(**params).fit(Xc, yc, sample_weight=wc)
    return final, iso, {"ece_raw_oof": ece(oof, yc, wc), "ece_calibrated_crossfit": ece(cal, yc, wc)}


def make_export(mode, booster=None, iso=None, alpha_up=0.6, alpha_down=0.15, warn=0.5, pause=0.7, extra=None):
    sm = {"format": "bosc-stream-v1", "mode": mode, "created": time.strftime("%Y-%m-%d %H:%M"),
          "label_order": LABEL_ORDER, "feature_order": FEATURES,
          "alpha_up": alpha_up, "alpha_down": alpha_down, "warn": warn, "pause": pause}
    if mode == "lgbm":
        sm["trees"] = export_trees(booster)
        sm["iso"] = {"x": [float(v) for v in iso.X_thresholds_], "y": [float(v) for v in iso.y_thresholds_]}
    if extra:
        sm.update(extra)
    return sm


def dumps(sm):
    return json.dumps(sm, separators=(",", ":"))
