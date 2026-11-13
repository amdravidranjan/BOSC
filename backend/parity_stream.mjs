import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { FeatureState, StreamState } from '../frontend/js/stream-model.js';
import { TacticDetector } from '../frontend/js/bosc-core.js';

const read = p => JSON.parse(readFileSync(new URL(p, import.meta.url), 'utf8'));
const cfg = read('../frontend/model/stream_model.json');
const calls = read('./stream_parity_cases.json');
const detector = new TacticDetector(read('../frontend/model/tactics.json'));
let maxError = 0, steps = 0;
for (const call of calls) {
  const fs = new FeatureState(), ss = new StreamState(cfg);
  for (const row of call) {
    const f = fs.push(row.probabilities, detector.detect(row.text));
    assert.equal(f.length, row.features.length);
    f.forEach((v, i) => assert.ok(Math.abs(v - row.features[i]) < 1e-6, `feature ${i}`));
    const result = ss.push(f, row.probabilities);
    for (const key of ['raw', 'calibrated', 'risk']) maxError = Math.max(maxError, Math.abs(result[key] - row[key]));
    steps++;
  }
}
assert.ok(maxError < 1e-6, `error ${maxError}`);
console.log(`STREAM PARITY OK: ${calls.length} calls, ${steps} steps, max error ${maxError}`);
