// Run the browser's WASM engine under Node: kernels, logits, and full calls.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import * as ort from 'onnxruntime-web';
import { AutoTokenizer, env } from '@huggingface/transformers';
import { studentTokenize } from '../frontend/js/student-tokenizer.js';
import { NeuralScorer } from '../frontend/js/detector-onnx.js';
import { TacticDetector } from '../frontend/js/bosc-core.js';

const read = p => JSON.parse(readFileSync(new URL(p, import.meta.url), 'utf8'));
ort.env.wasm.numThreads = 1;
ort.env.wasm.wasmPaths = new URL('../node_modules/onnxruntime-web/dist/', import.meta.url).href;
ort.env.wasm.wasmBinary = new Uint8Array(readFileSync(new URL('../node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.wasm', import.meta.url)));
const session = await ort.InferenceSession.create(new Uint8Array(readFileSync(
  new URL('../frontend/model/student/onnx/model_quantized.onnx', import.meta.url))), { executionProviders: ['wasm'] });
const cases = read('./onnx_parity_cases.json');
let maxLogitError = 0;
const timings = [];
for (const c of cases) {
  const inputs = Object.fromEntries(['input_ids', 'attention_mask', 'token_type_ids'].map(k =>
    [k, new ort.Tensor('int64', BigInt64Array.from(c[k], BigInt), [1, c[k].length])]));
  const start = performance.now();
  const { logits } = await session.run(inputs);
  timings.push(performance.now() - start);
  Array.from(logits.data).forEach((z, i) => { maxLogitError = Math.max(maxLogitError, Math.abs(z - c.logits[i])); });
}
assert.ok(maxLogitError < 1e-4, `WASM logit error ${maxLogitError}`);
env.allowRemoteModels = false;
env.allowLocalModels = true;
env.localModelPath = fileURLToPath(new URL('../frontend/model/', import.meta.url));
const tokenizer = await AutoTokenizer.from_pretrained('student');
const cfg = read('../frontend/model/stream_model.json');
const scorer = new NeuralScorer(async text => {
  const inputs = await studentTokenize(tokenizer, text, ort.Tensor);
  const { logits } = await session.run(inputs);
  return Array.from(logits.data, z => 1 / (1 + Math.exp(-z)));
}, new TacticDetector(read('../frontend/model/tactics.json')), cfg);
let maxRiskError = 0, steps = 0;
for (const call of read('./stream_parity_cases.json').slice(0, 10)) {
  scorer.reset();
  for (const row of call) {
    const got = await scorer.push(row.text);
    maxRiskError = Math.max(maxRiskError, Math.abs(got.risk - row.risk));
    const level = row.risk >= cfg.pause ? 'PAUSE' : row.risk >= cfg.warn ? 'WARN' : 'SAFE';
    assert.equal(got.level, level);
    steps++;
  }
}
assert.ok(maxRiskError < 1e-4, `WASM stream error ${maxRiskError}`);
await session.release();
const sorted = timings.slice(5).sort((a, b) => a - b);
const metrics = { scope: 'Single-thread browser WASM engine under Node on this Mac; not a phone measurement',
  windows: cases.length, stream_calls: 10, stream_turns: steps, max_logit_error: maxLogitError,
  max_risk_error: maxRiskError, median_ms: sorted[Math.floor(sorted.length * .5)],
  p95_ms: sorted[Math.floor(sorted.length * .95)] };
writeFileSync(new URL('../data-eval/results/wasm_validation.json', import.meta.url), JSON.stringify(metrics, null, 2) + '\n');
console.log('WASM PARITY OK', metrics);
