import { AutoTokenizer, AutoModelForSequenceClassification, Tensor, env } from 'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.0.2';
import { studentTokenize } from './student-tokenizer.js';
env.allowRemoteModels = false;
env.allowLocalModels = true;
let tokenizer, model;
async function handle(m) {
  try {
    if (m.type === 'load') {
      env.localModelPath = m.base;
      tokenizer = await AutoTokenizer.from_pretrained('student');
      model = await AutoModelForSequenceClassification.from_pretrained('student', { dtype: 'q8', device: 'wasm' });
      self.postMessage({ id: m.id, ready: true });
    } else {
      const inputs = await studentTokenize(tokenizer, m.text, Tensor);
      const out = await model(inputs);
      self.postMessage({ id: m.id, probabilities: Array.from(out.logits.data, z => 1 / (1 + Math.exp(-z))) });
    }
  } catch (e) { self.postMessage({ id: m.id, error: String(e.message || e) }); }
}
let queue = Promise.resolve();
self.onmessage = ({ data }) => { queue = queue.then(() => handle(data)); };
