// BOSC scoring core (browser + Node). Must match train/bosc_core.py exactly —
// train/parity_test.mjs checks this on 200+ strings.

const ACRO = /\b([a-z])\.([a-z])\.([a-z])\.?/g;
const NUM = /[0-9][0-9,.]*/g;
const TOK = /[a-z]{2,}/g;

export function tokenize(text) {
  let t = text.toLowerCase().replace(/’/g, "").replace(/'/g, "");
  t = t.replace(ACRO, "$1$2$3");
  t = t.replace(NUM, " num ");
  return t.match(TOK) || [];
}

export function analyzer(text) {
  const toks = tokenize(text);
  const out = toks.slice();
  for (let i = 0; i < toks.length - 1; i++) out.push(toks[i] + " " + toks[i + 1]);
  return out;
}

export const PRESSURE = new Set(["threat", "urgency", "secrecy_isolation", "credential_request",
  "remote_access", "payment_instruction", "stay_on_line"]);

export class TacticDetector {
  constructor(spec) {
    this.labels = {};
    this.tactics = {};
    for (const [name, t] of Object.entries(spec.tactics)) {
      this.labels[name] = t.label;
      this.tactics[name] = t.patterns.map((p) => new RegExp(p, "gi"));
    }
  }
  // returns {tactic: [[start, end], ...]}
  detect(text) {
    const found = {};
    for (const [name, pats] of Object.entries(this.tactics)) {
      const spans = [];
      for (const re of pats) {
        re.lastIndex = 0;
        let m;
        while ((m = re.exec(text)) !== null) {
          if (m[0].length === 0) { re.lastIndex++; continue; }
          spans.push([m.index, m.index + m[0].length]);
        }
      }
      if (spans.length) found[name] = spans;
    }
    return found;
  }
}

export class Model {
  constructor(mj) {
    this.vocab = new Map(mj.vocab.map((t, i) => [t, i]));
    this.idf = mj.idf;
    this.coef = mj.coef;
    this.intercept = mj.intercept;
  }
  proba(text) {
    const counts = new Map();
    for (const g of analyzer(text)) {
      const j = this.vocab.get(g);
      if (j !== undefined) counts.set(j, (counts.get(j) || 0) + 1);
    }
    let norm2 = 0;
    const vals = [];
    for (const [j, c] of counts) {
      const v = (1 + Math.log(c)) * this.idf[j];
      vals.push([j, v]);
      norm2 += v * v;
    }
    const norm = Math.sqrt(norm2) || 1;
    let z = this.intercept;
    for (const [j, v] of vals) z += (this.coef[j] * v) / norm;
    return 1 / (1 + Math.exp(-z));
  }
  // top contributing n-grams for explanation
  explain(text, k = 5) {
    const counts = new Map();
    for (const g of analyzer(text)) {
      const j = this.vocab.get(g);
      if (j !== undefined) counts.set(g, (counts.get(g) || 0) + 1);
    }
    return [...counts.entries()]
      .map(([g, c]) => [g, this.coef[this.vocab.get(g)] * (1 + Math.log(c)) * this.idf[this.vocab.get(g)]])
      .filter(([, w]) => w > 0).sort((a, b) => b[1] - a[1]).slice(0, k).map(([g]) => g);
  }
}

const ASKS = new Set(["credential_request", "payment_instruction", "remote_access"]);
const PRESSURE_ONLY = new Set(["threat", "secrecy_isolation", "stay_on_line"]);   // urgency alone is too common in genuine calls

// Minimum level from the combination of regex tactics seen so far in the call. Same as rule_floor() in
// backend/bosc_core.py. Catches the textbook pattern even when the model scores the wording low.
export function ruleFloor(seen) {
  let asks = 0;
  for (const t of seen) if (ASKS.has(t)) asks++;
  let pressure = false;
  for (const t of seen) if (PRESSURE_ONLY.has(t)) pressure = true;
  const auth = seen.has("authority_claim");
  if ((auth && asks > 0 && pressure) || (seen.has("threat") && seen.has("payment_instruction"))) return "PAUSE";
  if ((auth && asks > 0) || asks >= 2) return "WARN";
  return null;
}

// Immediate, explainable part of the score (display scale: 0.5 = WARN, 0.7 = PAUSE). Same as bosc_core.py.
export const TACTIC_WEIGHT = { authority_claim: 0.12, urgency: 0.12, stay_on_line: 0.18, secrecy_isolation: 0.22,
  threat: 0.22, credential_request: 0.2, payment_instruction: 0.2, remote_access: 0.35 };
const BUMP_CAP = 0.95, FLOOR_SCORE = { WARN: 0.55, PAUSE: 0.75 }, EXTRA_SCORE = { WARN: 0.55, PAUSE: 0.8 };
export const WARN_SCORE = 0.5, PAUSE_SCORE = 0.7;

export function displayRisk(r, warn, pause) {
  if (r < warn) return 0.5 * (r / warn);
  if (r < pause) return 0.5 + 0.2 * ((r - warn) / (pause - warn));
  return Math.min(1, 0.7 + 0.3 * ((r - pause) / (1 - pause)) * 1.6);
}
export function combineScore(risk, warn, pause, seen, extra = 0) {
  let bump = 0;
  for (const t of seen) bump += TACTIC_WEIGHT[t] || 0;
  bump = Math.min(BUMP_CAP, bump);
  const floor = ruleFloor(seen);
  return Math.min(0.99, Math.max(displayRisk(risk, warn, pause), bump, FLOOR_SCORE[floor] || 0, extra));
}
export function levelOf(score) { return score >= PAUSE_SCORE ? "PAUSE" : score >= WARN_SCORE ? "WARN" : "SAFE"; }
export function extraFor(level) { return EXTRA_SCORE[level] || 0; }

export class StreamingScorer {
  constructor(model, detector, cfg) {
    this.model = model;
    this.detector = detector;
    this.window = cfg.window;
    this.tw = cfg.tactic_weight;
    this.up = cfg.alpha_up;
    this.down = cfg.alpha_down;
    this.warn = cfg.warn;
    this.pause = cfg.pause;
    this.reset();
  }
  reset() {
    this.utts = [];
    this.seen = new Set();
    this.risk = 0;       // model risk (what the parity test checks)
    this.extra = 0;      // raised from outside the caller's words (victim cues, voice checks)
    this.history = [];
  }
  score() { return combineScore(this.risk, this.warn, this.pause, this.seen, this.extra); }
  level(r) {
    if (r !== undefined) return r >= this.pause ? "PAUSE" : r >= this.warn ? "WARN" : "SAFE";
    return levelOf(this.score());
  }
  push(text) {
    this.utts.push(text);
    const win = this.utts.slice(-this.window).join(" ");
    const p = this.model.proba(win);
    const tactics = this.detector ? this.detector.detect(text) : {};
    for (const t of Object.keys(tactics)) this.seen.add(t);
    let n = 0;
    for (const t of this.seen) if (PRESSURE.has(t)) n++;
    const raw = (1 - this.tw) * p + this.tw * Math.min(1, n / 4);
    const a = raw > this.risk ? this.up : this.down;
    this.risk = this.risk + a * (raw - this.risk);
    const out = { p, raw, risk: this.risk, tactics, score: this.score(), level: this.level(), floor: ruleFloor(this.seen) };
    this.history.push({ t: Date.now(), text, ...out });
    return out;
  }
  // Raise the call's risk to at least WARN / PAUSE from a signal outside the caller's words
  // (the victim's own replies in Android mode, or the voice check). null = just record the line.
  raiseTo(level, text = "", side = "me") {
    this.extra = Math.max(this.extra, extraFor(level));
    const out = { p: 0, raw: 0, risk: this.risk, tactics: {}, score: this.score(), level: this.level(), side };
    this.history.push({ t: Date.now(), text, ...out });
    return out;
  }
}

export async function loadScorer(base = "model/") {
  const [mj, tj] = await Promise.all([
    fetch(base + "model.json").then((r) => r.json()),
    fetch(base + "tactics.json").then((r) => r.json()),
  ]);
  const det = new TacticDetector(tj);
  return { scorer: new StreamingScorer(new Model(mj), det, mj.scorer), detector: det, model: mj };
}
