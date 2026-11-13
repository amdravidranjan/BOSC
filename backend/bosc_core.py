"""BOSC scoring core (Python reference). frontend/js/bosc-core.js must match this exactly.

Pipeline per caller utterance:
  window text (last W caller utterances) -> tokens -> TF-IDF (sublinear, l2) -> logistic regression p
  tactics seen so far (regex)            -> tactic score
  raw = (1 - tw) * p + tw * min(1, n_distinct_pressure_tactics / 4)
  risk = rise-fast / fall-slow smoothing of raw  -> level via WARN / PAUSE thresholds
"""
import json
import math
import re

_ACRO = re.compile(r"\b([a-z])\.([a-z])\.([a-z])\.?")
_NUM = re.compile(r"[0-9][0-9,.]*")
_TOK = re.compile(r"[a-z]{2,}")


def tokenize(text):
    t = text.lower().replace("’", "").replace("'", "")
    t = _ACRO.sub(r"\1\2\3", t)
    t = _NUM.sub(" num ", t)
    return _TOK.findall(t)


def analyzer(text):
    toks = tokenize(text)
    return toks + [toks[i] + " " + toks[i + 1] for i in range(len(toks) - 1)]


class TacticDetector:
    PRESSURE = {"threat", "urgency", "secrecy_isolation", "credential_request",
                "remote_access", "payment_instruction", "stay_on_line"}

    def __init__(self, spec):
        self.tactics = {}
        for name, t in spec["tactics"].items():
            self.tactics[name] = [re.compile(p, re.IGNORECASE) for p in t["patterns"]]

    def detect(self, text):
        found = {}
        for name, pats in self.tactics.items():
            spans = []
            for p in pats:
                for m in p.finditer(text):
                    if m.end() > m.start():
                        spans.append((m.start(), m.end()))
            if spans:
                found[name] = spans
        return found


class Model:
    def __init__(self, model_json):
        self.vocab = {t: i for i, t in enumerate(model_json["vocab"])}
        self.idf = model_json["idf"]
        self.coef = model_json["coef"]
        self.intercept = model_json["intercept"]

    def proba(self, text):
        counts = {}
        for g in analyzer(text):
            j = self.vocab.get(g)
            if j is not None:
                counts[j] = counts.get(j, 0) + 1
        vals = {j: (1.0 + math.log(c)) * self.idf[j] for j, c in counts.items()}
        norm = math.sqrt(sum(v * v for v in vals.values())) or 1.0
        z = self.intercept + sum(self.coef[j] * v / norm for j, v in vals.items())
        return 1.0 / (1.0 + math.exp(-z))


ASKS = {"credential_request", "payment_instruction", "remote_access"}
PRESSURE_ONLY = {"threat", "secrecy_isolation", "stay_on_line"}   # urgency alone is too common in genuine calls


def rule_floor(seen):
    """Minimum level from the combination of tactics seen so far in the call (regex tactics).
    Catches the textbook pattern even when the model scores the wording low (short, garbled real speech).
    Measured on dev/test genuine calls: PAUSE rules 0% false alarms, WARN rules 0-9% (the 9% are
    'warning a friend about a scam' calls, which mention every scam word)."""
    asks = seen & ASKS
    auth = "authority_claim" in seen
    if (auth and asks and seen & PRESSURE_ONLY) or ("threat" in seen and "payment_instruction" in seen):
        return "PAUSE"
    if (auth and asks) or len(asks) >= 2:
        return "WARN"
    return None


# Immediate, explainable part of the score (0-1 "display" scale, where 0.5 = WARN and 0.7 = PAUSE):
# each distinct pressure tactic heard so far adds its weight, so the meter moves on the first suspicious line
# even before the text model is sure. Tuned on the synthetic dev/test sets (see evaluate_scoring.py).
TACTIC_WEIGHT = {"authority_claim": 0.12, "urgency": 0.12, "stay_on_line": 0.18, "secrecy_isolation": 0.22,
                 "threat": 0.22, "credential_request": 0.2, "payment_instruction": 0.2, "remote_access": 0.35}
BUMP_CAP = 0.95
FLOOR_SCORE = {"WARN": 0.55, "PAUSE": 0.75}
EXTRA_SCORE = {"WARN": 0.55, "PAUSE": 0.8}
WARN_SCORE, PAUSE_SCORE = 0.5, 0.7


def display(r, warn, pause):
    """Model risk -> display scale (WARN threshold -> 0.5, PAUSE threshold -> 0.7). Same as the JS."""
    if r < warn:
        return 0.5 * (r / warn)
    if r < pause:
        return 0.5 + 0.2 * ((r - warn) / (pause - warn))
    return min(1.0, 0.7 + 0.3 * ((r - pause) / (1 - pause)) * 1.6)


def combine_score(risk, warn, pause, seen, extra=0.0):
    bump = min(BUMP_CAP, sum(TACTIC_WEIGHT.get(t, 0.0) for t in seen))
    floor = rule_floor(seen)
    return min(0.99, max(display(risk, warn, pause), bump, FLOOR_SCORE.get(floor, 0.0), extra))


def level_of(score):
    return "PAUSE" if score >= PAUSE_SCORE else "WARN" if score >= WARN_SCORE else "SAFE"


class StreamingScorer:
    def __init__(self, model, detector, cfg):
        self.model = model
        self.detector = detector
        self.window = cfg["window"]
        self.tw = cfg["tactic_weight"]
        self.up = cfg["alpha_up"]
        self.down = cfg["alpha_down"]
        self.warn = cfg["warn"]
        self.pause = cfg["pause"]
        self.reset()

    def reset(self):
        self.utts = []
        self.seen = set()
        self.risk = 0.0      # model risk (what the parity test checks)
        self.extra = 0.0     # raised from outside the caller's words (victim cues, voice checks)

    def score(self):
        return combine_score(self.risk, self.warn, self.pause, self.seen, self.extra)

    def level(self, r=None):
        if r is not None:
            return "PAUSE" if r >= self.pause else "WARN" if r >= self.warn else "SAFE"
        return level_of(self.score())

    def raise_to(self, level, text="", side="me"):
        if level in EXTRA_SCORE:
            self.extra = max(self.extra, EXTRA_SCORE[level])
        return {"p": 0.0, "raw": 0.0, "risk": self.risk, "tactics": {}, "score": self.score(), "level": self.level(), "side": side}

    def push(self, text):
        self.utts.append(text)
        win = " ".join(self.utts[-self.window:])
        p = self.model.proba(win)
        tactics = self.detector.detect(text) if self.detector else {}
        self.seen |= set(tactics)
        n = len(self.seen & TacticDetector.PRESSURE)
        raw = (1 - self.tw) * p + self.tw * min(1.0, n / 4.0)
        a = self.up if raw > self.risk else self.down
        self.risk = self.risk + a * (raw - self.risk)
        return {"p": p, "raw": raw, "risk": self.risk, "tactics": tactics, "score": self.score(), "level": self.level(),
                "floor": rule_floor(self.seen)}


def load(model_path, tactics_path, cfg_override=None):
    with open(model_path, encoding="utf-8") as f:
        mj = json.load(f)
    with open(tactics_path, encoding="utf-8") as f:
        tj = json.load(f)
    cfg = dict(mj["scorer"])
    if cfg_override:
        cfg.update(cfg_override)
    return StreamingScorer(Model(mj), TacticDetector(tj), cfg)
