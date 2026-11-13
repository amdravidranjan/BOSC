// Audio capture -> 16 kHz mono -> energy VAD -> utterance chunks (Float32Array).
// Nothing is stored: chunks go to the on-device ASR worker and are dropped.

const SR = 16000;
const FRAME = 480; // 30 ms at 16 kHz
const workletReady = new WeakMap(); // AudioContext -> addModule() promise

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
  // Uses an AudioWorklet where available, else the deprecated ScriptProcessor.
  // Async (the worklet module loads once per AudioContext); a detach() during loading cancels it.
  async attach(ctx, node) {
    this.detach();
    const gen = this.gen;
    this.ctx = ctx;
    this.ratio = ctx.sampleRate / SR;
    let proc = null;
    if (ctx.audioWorklet && typeof AudioWorkletNode !== "undefined") {
      try {
        if (!workletReady.has(ctx)) workletReady.set(ctx, ctx.audioWorklet.addModule(new URL("./chunk-worklet.js", import.meta.url)));
        await workletReady.get(ctx);
        if (gen !== this.gen) return;   // detached while the module loaded
        proc = new AudioWorkletNode(ctx, "bosc-chunk-tap", {
          numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1],
          channelCount: 1, channelCountMode: "explicit", channelInterpretation: "speakers",
        });
        proc.port.onmessage = (e) => this.feed(e.data);
        this.mode = "worklet";
      } catch (err) {
        workletReady.delete(ctx);
        console.warn("AudioWorklet unavailable, using ScriptProcessor.", err);
        proc = null;
      }
    }
    if (!proc) {
      proc = ctx.createScriptProcessor(4096, 1, 1);
      proc.onaudioprocess = (e) => this.feed(e.inputBuffer.getChannelData(0));
      this.mode = "script";
    }
    this.proc = proc;
    node.connect(proc);
    // keep the tap pulled by the graph; its output is silent
    this.sink = ctx.createGain();
    this.sink.gain.value = 0;
    proc.connect(this.sink).connect(ctx.destination);
    this.node = node;
  }
  detach() {
    this.gen = (this.gen || 0) + 1;
    try { this.node && this.proc && this.node.disconnect(this.proc); } catch {}
    try { this.proc && this.proc.disconnect(); } catch {}
    try { this.sink && this.sink.disconnect(); } catch {}
    if (this.proc && this.proc.port) this.proc.port.onmessage = null;
    this.proc = null;
    this.sink = null;
    this.node = null;
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
