// Voice analysis in a module Web Worker: synthetic-voice score + voice-print, from acoustic features.
// No model download: the small logistic model and the voice-print statistics live in model/voice-models.json
// (trained on Windows/Google TTS vs. real speech; see backend/README.md). Audio never leaves the device.
import { voiceFeatures, voicePrint, syntheticScore } from "./voice-features.js";

let models = null;

self.onmessage = async (e) => {
  const m = e.data;
  if (m.type === "load") {
    try {
      models = await (await fetch(new URL("../model/voice-models.json", import.meta.url))).json();
      self.postMessage({ type: "ready", threshold: models.synthetic.threshold, note: models.note });
    } catch (err) {
      self.postMessage({ type: "error", message: String(err && err.message || err) });
    }
  } else if (m.type === "clip") {
    const t0 = performance.now();
    try {
      const audio = m.audio.length > 16000 * 12 ? m.audio.subarray(0, 16000 * 12) : m.audio;
      const feat = voiceFeatures(audio);
      const synth = feat && models ? syntheticScore(pick(feat), models.synthetic) : null;
      const print = m.wantPrint === false ? null : voicePrint(audio);
      self.postMessage({ type: "result", id: m.id, who: m.who, synth, print, sec: audio.length / 16000, ms: Math.round(performance.now() - t0) });
    } catch (err) {
      self.postMessage({ type: "result", id: m.id, who: m.who, error: String(err && err.message || err) });
    }
  }
};

// voiceFeatures() returns all 21 features; the model uses the channel-robust subset, by name.
const ALL = ["dur", "voiced_ratio", "f0_mean", "f0_std_rel", "f0_range_rel", "jitter", "shimmer", "clarity_mean", "clarity_std",
  "centroid_mean", "centroid_std", "flat_mean", "flat_std", "roll_mean", "energy_std_rel", "pause_ratio", "pause_count_ps",
  "mel_slope", "mel_hf_ratio", "dmel_mean", "f0_jump_rate"];
function pick(feat) {
  return models.synthetic.features.map((n) => feat[ALL.indexOf(n)]);
}
