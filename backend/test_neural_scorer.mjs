import assert from 'node:assert/strict';
import { NeuralScorer } from '../frontend/js/detector-onnx.js';

const config = { mode: 'ema', trees: [], isotonic: { x: [0, 1], y: [0, 1] },
  alpha_up: .8, alpha_down: .1, warn: .3, pause: .6 };
const detector = { detect: () => ({}) };
const p = q => [q, ...Array(8).fill(0)];
const scorer = new NeuralScorer(async () => p(.99), detector, config);
await Promise.all([scorer.push('one'), scorer.push('two')]);
assert.deepEqual(scorer.utts, ['one', 'two']);
assert.equal(scorer.level(), 'PAUSE');
scorer.infer = async () => p(0);
for (let i = 0; i < 20; i++) await scorer.push('calm');
assert.equal(scorer.level(), 'PAUSE', 'PAUSE must latch');
let finish;
scorer.infer = () => new Promise(resolve => { finish = resolve; });
const pending = scorer.push('stale').then(() => false, e => e.name === 'AbortError');
await new Promise(resolve => setTimeout(resolve, 0));
scorer.reset();
finish(p(.99));
assert.ok(await pending);
assert.equal(scorer.history.length, 0);
assert.equal(scorer.level(), 'SAFE');
console.log('SCORER TESTS OK: ordered pushes, latched PAUSE, reset cancels stale inference');
