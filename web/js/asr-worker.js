// On-device speech-to-text (Whisper, English-only) in a Web Worker.
// Model files are downloaded once from the Hugging Face hub and cached by the browser;
// audio never leaves the device.
import { pipeline, env } from "https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.0.2";

env.allowLocalModels = false;
env.useBrowserCache = true;

let asr = null;
let device = "wasm";

async function hasWebGPU() {
  try {
    if (!self.navigator || !navigator.gpu) return false;
    const a = await navigator.gpu.requestAdapter();
    return !!a;
  } catch { return false; }
}

async function build(model, dev) {
  const opts = dev === "webgpu"
    ? { device: "webgpu", dtype: { encoder_model: "fp32", decoder_model_merged: "q4" } }
    : { device: "wasm", dtype: "q8" };
  const files = {};
  return pipeline("automatic-speech-recognition", model, {
    ...opts,
    progress_callback: (p) => {
      if (p.status === "progress" && p.total) {
        files[p.file] = [p.loaded, p.total];
        let l = 0, t = 0;
        for (const [a, b] of Object.values(files)) { l += a; t += b; }
        self.postMessage({ type: "progress", loaded: l, total: t });
      }
    },
  });
}

const JUNK = /^\s*(\[.*\]|\(.*\)|\*.*\*|thank you\.?|thanks for watching!?|you|bye\.?|\.+)\s*$/i;

self.onmessage = async (e) => {
  const m = e.data;
  if (m.type === "load") {
    try {
      device = m.forceWasm ? "wasm" : (await hasWebGPU()) ? "webgpu" : "wasm";
      try {
        asr = await build(m.model, device);
      } catch (err) {
        if (device !== "webgpu") throw err;
        device = "wasm";
        asr = await build(m.model, device);
      }
      const t0 = performance.now();
      await asr(new Float32Array(16000));
      self.postMessage({ type: "ready", device, warmupMs: Math.round(performance.now() - t0) });
    } catch (err) {
      self.postMessage({ type: "error", message: String(err && err.message || err) });
    }
  } else if (m.type === "transcribe") {
    if (!asr) return self.postMessage({ type: "text", id: m.id, text: "", ms: 0, dur: 0 });
    const t0 = performance.now();
    try {
      const out = await asr(m.audio);
      let text = (out && out.text || "").trim();
      if (JUNK.test(text)) text = "";
      self.postMessage({ type: "text", id: m.id, text, ms: Math.round(performance.now() - t0), dur: m.audio.length / 16000 });
    } catch (err) {
      self.postMessage({ type: "text", id: m.id, text: "", error: String(err), ms: 0, dur: 0 });
    }
  }
};
