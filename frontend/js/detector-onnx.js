// BOSC Stage 4 detector: student model (nlp-worker.js) + Stage 3 stream model (stream-model.js).
// Same contract as bosc-core.js loadScorer(), except push() is async and may resolve to null
// (when reset() was called while it was waiting). Owned by Backend.
//
// loadStudent(base) resolves to null when the model files are not deployed or fail to load;
// the app then keeps the Stage 0 detector ("basic detector").
import { loadScorer as loadBasicScorer, ruleFloor } from "./bosc-core.js";
import { StreamModel, TACTICS, sigmoid } from "./stream-model.js";

const WINDOW = 4;
const JOIN = " | ";

class StudentScorer {
  constructor(worker, sm, detector) {
    this.worker = worker;
    this.sm = new StreamModel(sm);
    this.detector = detector;
    this.gen = 0;
    this.nextId = 1;
    this.waiting = new Map();
    worker.addEventListener("message", (e) => {
      const m = e.data;
      if (m.type !== "result") return;
      const w = this.waiting.get(m.id);
      if (!w) return;
      this.waiting.delete(m.id);
      m.error ? w.reject(new Error(m.error)) : w.resolve(m);
    });
    this.reset();
  }
  get warn() { return this.sm.warn; }
  get pause() { return this.sm.pause; }
  get risk() { return this.sm.risk; }
  reset() {
    this.gen++;
    this.utts = [];
    this.seen = new Set();
    this.history = [];
    this.sm.reset();
    this.chain = Promise.resolve();
  }
  level(r) { return this.sm.level(r); }
  infer(text) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.waiting.set(id, { resolve, reject });
      this.worker.postMessage({ type: "run", id, text });
    });
  }
  // Serialised: utterances are scored strictly in arrival order.
  push(text) {
    const gen = this.gen;
    const p = this.chain.then(() => this._push(text, gen));
    this.chain = p.catch(() => {});
    return p;
  }
  async _push(text, gen) {
    if (gen !== this.gen) return null;
    this.utts.push(text);
    const win = this.utts.slice(-WINDOW).join(JOIN);
    const { logits, ms } = await this.infer(win);
    if (gen !== this.gen) return null;
    const probs = logits.map(sigmoid);
    const tactics = this.detector.detect(text);
    const regex = Object.keys(tactics);
    for (const t of regex) this.seen.add(t);
    TACTICS.forEach((t, k) => { if (probs[k + 1] > 0.5) this.seen.add(t); });
    const o = this.sm.push(probs, regex);
    // rule floor (see bosc-core.js ruleFloor): applied to the scorer state so the meter, Shield and summary agree
    const floor = ruleFloor(this.sm.fs.regexSeen);
    if (floor === "PAUSE") { this.sm.risk = Math.max(this.sm.risk, this.sm.pause); this.sm.latched = true; }
    else if (floor === "WARN") this.sm.risk = Math.max(this.sm.risk, this.sm.warn);
    o.risk = this.sm.risk;
    o.level = this.sm.level();
    const tacticProbs = Object.fromEntries(TACTICS.map((t, k) => [t, probs[k + 1]]));
    const out = { p: probs[0], raw: o.q, risk: o.risk, tactics, level: o.level, tacticProbs, floor, nlpMs: Math.round(ms) };
    this.history.push({ t: Date.now(), text, ...out });
    return out;
  }
}

export async function loadStudent(base = "model/", { timeoutMs = 90000 } = {}) {
  const abs = new URL(base, location.href).href;
  let sm, order;
  try {
    const [a, b] = await Promise.all([fetch(abs + "stream_model.json"), fetch(abs + "student/label_order.json")]);
    if (!a.ok || !b.ok) return null;
    [sm, order] = [await a.json(), await b.json()];
  } catch { return null; }
  if (order.join() !== ["scam", ...TACTICS].join()) { console.warn("BOSC: student label order mismatch", order); return null; }
  const basic = await loadBasicScorer(base);
  const worker = new Worker(new URL("./nlp-worker.js", import.meta.url), { type: "module" });
  const ok = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), timeoutMs);
    worker.addEventListener("message", function on(e) {
      if (e.data.type === "ready") { clearTimeout(timer); worker.removeEventListener("message", on); resolve(true); }
      if (e.data.type === "error") { clearTimeout(timer); console.warn("BOSC student:", e.data.error); resolve(false); }
    });
    worker.postMessage({ type: "load", base: abs, maxLen: sm.max_length || 128 });
  });
  if (!ok) { worker.terminate(); return null; }
  return { scorer: new StudentScorer(worker, sm, basic.detector), detector: basic.detector, model: sm, kind: "student" };
}

// Contract used by frontend/js/bosc.js: resolves to {scorer, detector, model, kind} or rejects,
// in which case the app keeps the basic (Stage 0) detector.
export async function loadScorer(base = "model/") {
  const s = await loadStudent(base);
  if (!s) throw new Error("student model not deployed (frontend/model/student/ + stream_model.json) or failed to load");
  return s;
}
