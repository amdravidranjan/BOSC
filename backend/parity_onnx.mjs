// Checks the browser student model against the Kaggle (Python + onnxruntime) outputs:
// tokenizer ids (with left truncation) and int8 logits.
// Run once: npm i -D @huggingface/transformers@3.0.2
// Then:     node backend/parity_onnx.mjs
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { AutoTokenizer, AutoModelForSequenceClassification, Tensor, env } from "@huggingface/transformers";

const here = dirname(fileURLToPath(import.meta.url));
env.allowRemoteModels = false;
env.allowLocalModels = true;
env.localModelPath = join(here, "..", "frontend", "model") + "/";
const cases = JSON.parse(readFileSync(join(here, "student_parity_cases.json"), "utf8"));
const maxLen = cases.max_length;

const tok = await AutoTokenizer.from_pretrained("student");
const model = await AutoModelForSequenceClassification.from_pretrained("student", { dtype: "q8" });

// same as frontend/js/nlp-worker.js
function encode(text) {
  let ids = Array.from(tok(text, { add_special_tokens: true }).input_ids.data, Number);
  if (ids.length > maxLen) ids = [ids[0], ...ids.slice(ids.length - (maxLen - 1))];
  return ids;
}

let idMismatch = 0, maxDiff = 0, maxProbDiff = 0, flips = 0;
const sig = (z) => 1 / (1 + Math.exp(-z));
const ms = [];
for (const c of cases.cases) {
  const ids = encode(c.text);
  if (ids.join() !== c.ids.join()) { idMismatch++; if (idMismatch <= 5) console.log("ids differ:", JSON.stringify(c.text.slice(0, 80))); }
  const n = c.ids.length;
  const t0 = performance.now();
  const out = await model({
    input_ids: new Tensor("int64", BigInt64Array.from(c.ids.map(BigInt)), [1, n]),
    attention_mask: new Tensor("int64", new BigInt64Array(n).fill(1n), [1, n]),
    token_type_ids: new Tensor("int64", new BigInt64Array(n), [1, n]),
  });
  ms.push(performance.now() - t0);
  Array.from(out.logits.data, Number).forEach((v, k) => {
    maxDiff = Math.max(maxDiff, Math.abs(v - c.logits[k]));
    maxProbDiff = Math.max(maxProbDiff, Math.abs(sig(v) - sig(c.logits[k])));
    if ((sig(v) > 0.5) !== (sig(c.logits[k]) > 0.5)) flips++;
  });
}
ms.sort((a, b) => a - b);
console.log(`cases ${cases.cases.length}: tokenizer mismatches ${idMismatch}, logits max |diff| ${maxDiff.toExponential(2)}, ` +
  `prob max |diff| ${maxProbDiff.toExponential(2)}, 0.5-decision flips ${flips}, median ${ms[ms.length >> 1].toFixed(1)} ms`);
// int8 kernels differ slightly between onnxruntime (Python) and onnxruntime-web/node, so compare probabilities:
// first browser check (run full_b4a87631) gave logits diff 0.08, probability diff 0.004, 0 flips.
const ok = idMismatch === 0 && maxProbDiff < 0.01 && flips === 0;
console.log(ok ? "ONNX PARITY OK" : "ONNX PARITY FAILED");
process.exit(ok ? 0 : 1);
