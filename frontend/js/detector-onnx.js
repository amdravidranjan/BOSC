import { loadScorer as loadBasic, TacticDetector } from './bosc-core.js';
import { FeatureState, StreamState } from './stream-model.js';

export class NeuralScorer {
  constructor(infer, detector, config) {
    this.infer = infer; this.detector = detector; this.config = config;
    this.warn = config.warn; this.pause = config.pause; this.generation = 0;
    this.reset();
  }
  reset() {
    this.generation++; this.utts = []; this.seen = new Set(); this.history = [];
    this.features = new FeatureState(); this.stream = new StreamState(this.config);
    this.risk = 0; this.queue = Promise.resolve();
  }
  level(r = this.risk) { return r >= this.pause ? 'PAUSE' : r >= this.warn ? 'WARN' : 'SAFE'; }
  cancelPending() { this.generation++; this.queue = Promise.resolve(); }
  push(text) {
    const generation = this.generation;
    const task = this.queue.then(async () => {
      if (generation !== this.generation) throw new DOMException('Call ended', 'AbortError');
      const utterances = [...this.utts, text];
      const p = await this.infer(utterances.slice(-4).join(' | '));
      if (generation !== this.generation) throw new DOMException('Call ended', 'AbortError');
      this.utts = utterances;
      const tactics = this.detector.detect(text);
      for (const k of Object.keys(tactics)) this.seen.add(k);
      const features = this.features.push(p, tactics);
      const result = this.stream.push(features, p);
      this.risk = result.risk;
      const out = { p: p[0], raw: this.config.mode === 'ema' ? p[0] : result.calibrated,
        risk: this.risk, level: this.level(), tactics };
      this.history.push({ t: Date.now(), text, ...out });
      return out;
    });
    this.queue = task.catch(() => {});
    return task;
  }
}

export async function loadScorer(base = 'model/') {
  let worker;
  try {
    const releaseResponse = await fetch(base + 'release.json');
    if (!releaseResponse.ok) throw new Error('No neural release');
    const release = await releaseResponse.json();
    if (!release.enabled) throw new Error('Neural release did not pass validation');
    const [config, tactics] = await Promise.all(['stream_model.json', 'tactics.json'].map(
      async f => { const r = await fetch(base + f); if (!r.ok) throw new Error(f); return r.json(); }));
    worker = new Worker(new URL('./nlp-worker.js', import.meta.url), { type: 'module' });
    let sequence = 0;
    const pending = new Map();
    worker.onmessage = ({ data: m }) => {
      const job = pending.get(m.id);
      if (!job) return;
      pending.delete(m.id); clearTimeout(job.timer);
      m.error ? job.reject(new Error(m.error)) : job.resolve(m);
    };
    worker.onerror = e => {
      for (const job of pending.values()) { clearTimeout(job.timer); job.reject(new Error(e.message)); }
      pending.clear();
    };
    const request = payload => new Promise((resolve, reject) => {
      const id = ++sequence;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('Detector timed out')); }, 120000);
      pending.set(id, { resolve, reject, timer }); worker.postMessage({ ...payload, id });
    });
    await request({ type: 'load', base: new URL(base, document.baseURI).href });
    const detector = new TacticDetector(tactics);
    return { scorer: new NeuralScorer(async text => (await request({ type: 'predict', text })).probabilities,
      detector, config), detector, model: config, kind: 'Neural detector' };
  } catch (error) {
    worker?.terminate();
    console.warn('Using basic detector:', error);
    return { ...await loadBasic(base), kind: 'Basic detector' };
  }
}
