// BOSC Stage 3: streaming call-level model (features -> trees -> isotonic -> smoothing, latched PAUSE).
// Must match backend/stream_features.py exactly. Checked by backend/parity_stream.mjs.
// Owned by Backend.

export const TACTICS = ["urgency", "threat", "secrecy_isolation", "authority_claim", "credential_request",
  "remote_access", "payment_instruction", "stay_on_line"];
const PRESSURE = new Set(["threat", "urgency", "secrecy_isolation", "credential_request",
  "remote_access", "payment_instruction", "stay_on_line"]);

export class FeatureState {
  constructor() { this.reset(); }
  reset() {
    this.t = -1; this.pmax = 0; this.psum = 0; this.tmax = new Array(8).fill(0); this.nwin = 0; this.ema = 0;
    this.firstAuth = -1; this.firstThreat = -1; this.firstAsk = -1; this.firstAny = -1;
    this.regexSeen = new Set();
  }
  step(probs, regexTactics = []) {
    this.t += 1;
    const t = this.t;
    const p = probs[0];
    const tac = probs.slice(1, 9);
    if (p > this.pmax) this.pmax = p;
    this.psum += p;
    for (let k = 0; k < 8; k++) if (tac[k] > this.tmax[k]) this.tmax[k] = tac[k];
    if (p > 0.5) this.nwin += 1;
    this.ema = t === 0 ? p : 0.5 * p + 0.5 * this.ema;
    if (this.firstAuth < 0 && tac[3] > 0.5) this.firstAuth = t;
    if (this.firstThreat < 0 && tac[1] > 0.5) this.firstThreat = t;
    if (this.firstAsk < 0 && (tac[4] > 0.5 || tac[5] > 0.5 || tac[6] > 0.5)) this.firstAsk = t;
    if (this.firstAny < 0 && Math.max(...tac) > 0.5) this.firstAny = t;
    for (const r of regexTactics) this.regexSeen.add(r);
    let nOver = 0;
    for (let k = 0; k < 8; k++) if (this.tmax[k] > 0.5) nOver += 1;
    const ab = this.firstAuth >= 0 && this.firstThreat >= 0 && this.firstAuth <= this.firstThreat ? 1 : 0;
    const ta = this.firstThreat >= 0 && this.firstAsk >= 0 && this.firstThreat <= this.firstAsk ? 1 : 0;
    const since = this.firstAny >= 0 ? t - this.firstAny : -1;
    let regexN = 0;
    for (const r of this.regexSeen) if (PRESSURE.has(r)) regexN += 1;
    return [p, ...tac, this.pmax, this.psum / (t + 1), ...this.tmax, nOver, this.nwin, this.ema, ab, ta, since, t, regexN];
  }
}

export function sigmoid(z) {
  if (z >= 0) return 1 / (1 + Math.exp(-z));
  const e = Math.exp(z);
  return e / (1 + e);
}

export function interp(x, X, Y) {
  const n = X.length;
  if (n === 0) return x;
  if (x <= X[0]) return Y[0];
  if (x >= X[n - 1]) return Y[n - 1];
  let lo = 0, hi = n - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (X[mid] <= x) lo = mid; else hi = mid;
  }
  return Y[lo] + (Y[hi] - Y[lo]) * (x - X[lo]) / (X[hi] - X[lo]);
}

export function treeRaw(trees, x) {
  let s = 0;
  for (let node of trees) {
    while (node.v === undefined) {
      const v = x[node.f];
      node = Number.isNaN(v) ? (node.dl ? node.l : node.r) : (v <= node.th ? node.l : node.r);
    }
    s += node.v;
  }
  return s;
}

export class StreamModel {
  constructor(sm) {
    this.sm = sm;
    this.trees = sm.trees || [];
    this.isox = sm.iso ? sm.iso.x : [];
    this.isoy = sm.iso ? sm.iso.y : [];
    this.up = sm.alpha_up; this.down = sm.alpha_down;
    this.warn = sm.warn; this.pause = sm.pause;
    this.reset();
  }
  reset() { this.fs = new FeatureState(); this.risk = 0; this.latched = false; }
  qFromFeatures(f) {
    if (this.sm.mode === "ema") return f[0];
    return interp(sigmoid(treeRaw(this.trees, f)), this.isox, this.isoy);
  }
  level(r) {
    if (r === undefined) { if (this.latched) return "PAUSE"; r = this.risk; }
    return r >= this.pause ? "PAUSE" : r >= this.warn ? "WARN" : "SAFE";
  }
  push(probs, regexTactics = []) {
    const f = this.fs.step(probs, regexTactics);
    const q = this.qFromFeatures(f);
    const a = q > this.risk ? this.up : this.down;
    this.risk = this.risk + a * (q - this.risk);
    if (this.risk >= this.pause) this.latched = true;
    return { q, risk: this.risk, level: this.level(), features: f };
  }
}
