import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { AutoTokenizer, AutoModelForSequenceClassification, Tensor, env } from '@huggingface/transformers';
import { studentTokenize } from '../frontend/js/student-tokenizer.js';

env.allowRemoteModels = false;
env.allowLocalModels = true;
env.localModelPath = fileURLToPath(new URL('../frontend/model/', import.meta.url));
const tokenizer = await AutoTokenizer.from_pretrained('student');
const model = await AutoModelForSequenceClassification.from_pretrained('student', { dtype: 'q8', device: 'cpu' });
const cases = JSON.parse(readFileSync(new URL('./onnx_parity_cases.json', import.meta.url), 'utf8'));
let maxError = 0;
for (const c of cases) {
  const inputs = await studentTokenize(tokenizer, c.text, Tensor);
  for (const key of ['input_ids', 'attention_mask', 'token_type_ids']) {
    assert.deepEqual(Array.from(inputs[key].data, Number), c[key], `${key}: ${c.text.slice(0, 80)}`);
  }
  const { logits } = await model(inputs);
  Array.from(logits.data).forEach((z, i) => { maxError = Math.max(maxError, Math.abs(z - c.logits[i])); });
}
await model.dispose();
assert.ok(maxError < 1e-4, `ONNX logit error ${maxError}`);
console.log(`ONNX PARITY OK: ${cases.length} tokenized windows; max logit error ${maxError}`);
