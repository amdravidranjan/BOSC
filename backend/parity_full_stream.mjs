import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { AutoTokenizer, AutoModelForSequenceClassification, Tensor, env } from '@huggingface/transformers';
import { studentTokenize } from '../frontend/js/student-tokenizer.js';
import { NeuralScorer } from '../frontend/js/detector-onnx.js';
import { TacticDetector } from '../frontend/js/bosc-core.js';

const read = p => JSON.parse(readFileSync(new URL(p, import.meta.url), 'utf8'));
env.allowRemoteModels = false;
env.allowLocalModels = true;
env.localModelPath = fileURLToPath(new URL('../frontend/model/', import.meta.url));
const tokenizer = await AutoTokenizer.from_pretrained('student');
const model = await AutoModelForSequenceClassification.from_pretrained('student', { dtype: 'q8', device: 'cpu' });
const config = read('../frontend/model/stream_model.json');
const detector = new TacticDetector(read('../frontend/model/tactics.json'));
const infer = async text => {
  const inputs = await studentTokenize(tokenizer, text, Tensor);
  const out = await model(inputs);
  return Array.from(out.logits.data, z => 1 / (1 + Math.exp(-z)));
};
const scorer = new NeuralScorer(infer, detector, config);
const cases = read('./stream_parity_cases.json').slice(0, 10);
let maxError = 0, steps = 0;
for (const call of cases) {
  scorer.reset();
  for (const row of call) {
    const got = await scorer.push(row.text);
    maxError = Math.max(maxError, Math.abs(got.risk - row.risk));
    const level = row.risk >= config.pause ? 'PAUSE' : row.risk >= config.warn ? 'WARN' : 'SAFE';
    assert.equal(got.level, level);
    steps++;
  }
}
await model.dispose();
assert.ok(maxError < 1e-4, `full stream risk error ${maxError}`);
console.log(`FULL STREAM PARITY OK: ${cases.length} calls, ${steps} turns, max risk error ${maxError}`);
