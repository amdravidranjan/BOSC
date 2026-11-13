// Causal call features and LightGBM + isotonic evaluation. Python: stage3_stream.py.
export function treeValue(node, features) {
  if (node.leaf_value !== undefined) return node.leaf_value;
  if (node.decision_type !== "<=") throw new Error("Unsupported tree decision");
  return treeValue(features[node.split_feature] <= Number(node.threshold) ? node.left_child : node.right_child, features);
}

export function rawScore(config, features) {
  const z = config.trees.reduce((s, t) => s + treeValue(t.tree_structure, features), 0);
  return 1 / (1 + Math.exp(-z));
}

export function calibrate(config, p) {
  const { x, y } = config.isotonic;
  if (p <= x[0]) return y[0];
  for (let i = 1; i < x.length; i++) {
    if (p <= x[i]) return y[i - 1] + (p - x[i - 1]) / (x[i] - x[i - 1]) * (y[i] - y[i - 1]);
  }
  return y[y.length - 1];
}

export class FeatureState {
  constructor() { this.reset(); }
  reset() { this.rows = []; this.first = new Map(); this.ema = 0; this.regex = new Set(); }
  push(p, tactics) {
    const t = this.rows.length;
    this.rows.push(p);
    for (let k = 1; k < 9; k++) if (p[k] > .5 && !this.first.has(k)) this.first.set(k, t);
    for (const k of Object.keys(tactics)) this.regex.add(k);
    this.ema = .5 * p[0] + .5 * this.ema;
    const maxima = p.map((_, k) => Math.max(...this.rows.map(r => r[k])));
    const authority = this.first.get(4), threat = this.first.get(2);
    const authorityBefore = Number(authority !== undefined && threat !== undefined && authority < threat);
    const threatBefore = Number(threat !== undefined && [5, 6, 7].some(k => this.first.has(k) && threat < this.first.get(k)));
    const first = this.first.size ? Math.min(...this.first.values()) : t;
    return [...p, maxima[0], this.rows.reduce((s, r) => s + r[0], 0) / this.rows.length,
      ...maxima.slice(1), maxima.slice(1).filter(q => q > .5).length,
      this.rows.filter(r => r[0] > .5).length, this.ema, authorityBefore, threatBefore, t - first, t, this.regex.size];
  }
}

export class StreamState {
  constructor(config) { this.config = config; this.reset(); }
  reset() { this.risk = 0; this.latched = false; }
  push(features, p) {
    const raw = rawScore(this.config, features);
    const calibrated = calibrate(this.config, raw);
    const q = this.config.mode === "ema" ? p[0] : calibrated;
    const a = q > this.risk ? this.config.alpha_up : this.config.alpha_down;
    this.risk += a * (q - this.risk);
    if (this.risk >= this.config.pause) this.latched = true;
    if (this.latched) this.risk = Math.max(this.risk, this.config.pause);
    return { raw, calibrated, risk: this.risk };
  }
}
