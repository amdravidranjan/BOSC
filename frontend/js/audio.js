// Audio capture -> 16 kHz mono -> energy VAD -> utterance chunks (Float32Array).
// Nothing is stored: chunks go to the on-device ASR worker and are dropped.

const SR = 16000;
const FRAME = 480; // 30 ms at 16 kHz

export class UtteranceChunker {
  constructor({ onChunk, onLevel, minRms = 0.008, silenceMs = 650, maxMs = 8000, minMs = 400 }) {
    this.onChunk = onChunk;
    this.onLevel = onLevel || (() => {});
    this.minRms = minRms;
    this.silenceFrames = Math.round(silenceMs / 30);
    this.maxFrames = Math.round(maxMs / 30);
    this.minFrames = Math.round(minMs / 30);
    this.reset();
  }
  reset() {
    this.inBuf = [];
    this.pos = 0;
    this.frame = [];
    this.noise = 0.004;
    this.speaking = false;
    this.preroll = [];
    this.cur = [];
    this.voiced = 0;
    this.silent = 0;
  }
  attach(ctx, node) {
    this.detach();
    this.ctx = ctx;
    this.ratio = ctx.sampleRate / SR;
    this.proc = ctx.createScriptProcessor(4096, 1, 1);
    this.proc.onaudioprocess = (e) => this.feed(e.inputBuffer.getChannelData(0));
    node.connect(this.proc);
    // a ScriptProcessor only runs when connected; its output buffer is left silent
    this.sink = ctx.createGain();
    this.sink.gain.value = 0;
    this.proc.connect(this.sink).connect(ctx.destination);
    this.node = node;
  }
  detach() {
    try { this.node && this.proc && this.node.disconnect(this.proc); } catch {}
    try { this.proc && this.proc.disconnect(); } catch {}
    this.proc = null;
    this.flush();
    this.reset();
  }
  feed(input) {
    // box-filter downsample to 16 kHz, keeping the fractional position across calls
    const buf = this.inBuf.length ? Float32Array.from([...this.inBuf, ...input]) : input;
    let pos = this.pos;
    const r = this.ratio;
    while (pos + r <= buf.length) {
      const a = Math.floor(pos), b = Math.max(a + 1, Math.floor(pos + r));
      let s = 0;
      for (let i = a; i < b; i++) s += buf[i];
      this.frame.push(s / (b - a));
      if (this.frame.length === FRAME) { this.vad(Float32Array.from(this.frame)); this.frame = []; }
      pos += r;
    }
    const keep = Math.floor(pos);
    this.inBuf = Array.from(buf.subarray ? buf.subarray(keep) : buf.slice(keep));
    this.pos = pos - keep;
  }
  vad(f) {
    let e = 0;
    for (let i = 0; i < f.length; i++) e += f[i] * f[i];
    const rms = Math.sqrt(e / f.length);
    const thr = Math.max(this.minRms, this.noise * 3);
    this.onLevel(Math.min(1, rms / (thr * 3)), rms > thr);
    if (!this.speaking) {
      if (rms > thr) {
        this.speaking = true;
        this.cur = [...this.preroll, f];
        this.voiced = 1;
        this.silent = 0;
      } else {
        this.noise = 0.95 * this.noise + 0.05 * rms;
        this.preroll.push(f);
        if (this.preroll.length > 7) this.preroll.shift();
      }
      return;
    }
    this.cur.push(f);
    if (rms > thr * 0.7) { this.voiced++; this.silent = 0; } else { this.silent++; }
    if (this.silent >= this.silenceFrames) this.emit(true);
    else if (this.cur.length >= this.maxFrames) this.emit(false);
  }
  emit(ended) {
    const frames = ended ? this.cur.slice(0, this.cur.length - Math.floor(this.silenceFrames / 2)) : this.cur;
    if (this.voiced >= this.minFrames) {
      const out = new Float32Array(frames.length * FRAME);
      frames.forEach((fr, i) => out.set(fr, i * FRAME));
      this.onChunk(out);
    }
    this.cur = [];
    this.voiced = 0;
    this.silent = 0;
    this.speaking = !ended;
    this.preroll = [];
  }
  flush() {
    if (this.speaking && this.cur.length) this.emit(true);
  }
}

export function getAudioContext() {
  if (!window.__boscCtx || window.__boscCtx.state === "closed") {
    window.__boscCtx = new (window.AudioContext || window.webkitAudioContext)();
  }
  return window.__boscCtx;
}
