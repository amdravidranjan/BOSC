// Synthetic / cloned voice check (EXPERIMENTAL) in a module Web Worker.
// Model: ai8shiro/deepfake-audio-wav2vec2-ONNX (wav2vec2 audio classifier, labels real/fake, MIT).
// Trained on ASVspoof 2021 PA (replayed speech), so it is not proven on modern voice-cloning tools:
// the app shows it as a hint, never as proof. Audio stays on the device; only the model files are downloaded.
import { pipeline, env } from "https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.0.2";

env.allowLocalModels = false;
env.useBrowserCache = true;

const MODEL = "ai8shiro/deepfake-audio-wav2vec2-ONNX";
const MAX_SECONDS = 6;
let clf = null;

self.onmessage = async (e) => {
  const m = e.data;
  if (m.type === "load") {
    try {
      const files = {};
      clf = await pipeline("audio-classification", MODEL, {
        dtype: "q4", device: "wasm",
        progress_callback: (p) => {
          if (p.status === "progress" && p.total) {
            files[p.file] = [p.loaded, p.total];
            let l = 0, t = 0;
            for (const [a, b] of Object.values(files)) { l += a; t += b; }
            self.postMessage({ type: "progress", loaded: l, total: t });
          }
        },
      });
      self.postMessage({ type: "ready" });
    } catch (err) {
      self.postMessage({ type: "error", message: String(err && err.message || err) });
    }
  } else if (m.type === "classify") {
    try {
      const audio = m.audio.length > 16000 * MAX_SECONDS ? m.audio.subarray(0, 16000 * MAX_SECONDS) : m.audio;
      const t0 = performance.now();
      const out = await clf(audio, { top_k: 2 });
      const fake = (out.find((o) => o.label.toLowerCase() === "fake") || { score: 0 }).score;
      self.postMessage({ type: "result", id: m.id, fake, ms: Math.round(performance.now() - t0), sec: audio.length / 16000 });
    } catch (err) {
      self.postMessage({ type: "result", id: m.id, error: String(err && err.message || err) });
    }
  }
};
