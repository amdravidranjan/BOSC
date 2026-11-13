// BOSC student model (MiniLM, int8 ONNX) in a module Web Worker. Owned by Backend.
// Loads frontend/model/student/ from this site only (no remote models). Text never leaves the device.
import { AutoTokenizer, AutoModelForSequenceClassification, Tensor, env } from "https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.0.2";

env.allowRemoteModels = false;
env.allowLocalModels = true;

let tok = null, model = null, maxLen = 128;

// Left truncation, same as the Python tokenizer with truncation_side="left":
// [CLS] + the newest (maxLen - 2) tokens + [SEP].
function encode(text) {
  const enc = tok(text, { add_special_tokens: true });
  let ids = Array.from(enc.input_ids.data, Number);
  if (ids.length > maxLen) ids = [ids[0], ...ids.slice(ids.length - (maxLen - 1))];
  return ids;
}

async function run(text) {
  const ids = encode(text);
  const n = ids.length;
  const out = await model({
    input_ids: new Tensor("int64", BigInt64Array.from(ids.map(BigInt)), [1, n]),
    attention_mask: new Tensor("int64", new BigInt64Array(n).fill(1n), [1, n]),
    token_type_ids: new Tensor("int64", new BigInt64Array(n), [1, n]),
  });
  return Array.from(out.logits.data, Number);
}

self.onmessage = async (e) => {
  const m = e.data;
  if (m.type === "load") {
    try {
      env.localModelPath = m.base;          // absolute URL of frontend/model/
      maxLen = m.maxLen || 128;
      tok = await AutoTokenizer.from_pretrained("student");
      model = await AutoModelForSequenceClassification.from_pretrained("student", { dtype: "q8", device: "wasm" });
      const t0 = performance.now();
      await run("hello, this is a warm-up call");
      self.postMessage({ type: "ready", warmMs: Math.round(performance.now() - t0) });
    } catch (err) {
      self.postMessage({ type: "error", error: String(err && err.message || err) });
    }
  } else if (m.type === "run") {
    try {
      const t0 = performance.now();
      const logits = await run(m.text);
      self.postMessage({ type: "result", id: m.id, logits, ms: performance.now() - t0 });
    } catch (err) {
      self.postMessage({ type: "result", id: m.id, error: String(err && err.message || err) });
    }
  } else if (m.type === "ids") {
    // used by parity checks
    self.postMessage({ type: "ids", id: m.id, ids: encode(m.text) });
  }
};
