// Checks that frontend/js/stream-model.js gives the same outputs as backend/stream_features.py.
// Run: node backend/parity_stream.mjs [stream_model.json] [stream_parity_cases.json]
// Defaults: frontend/model/stream_model.json and backend/stream_parity_cases.json (both written by the Kaggle notebook).
import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const sm = JSON.parse(readFileSync(process.argv[2] || join(here, "..", "frontend", "model", "stream_model.json"), "utf8"));
const cases = JSON.parse(readFileSync(process.argv[3] || join(here, "stream_parity_cases.json"), "utf8"));
const { StreamModel } = await import(pathToFileURL(join(here, "..", "frontend", "js", "stream-model.js")).href);

const m = new StreamModel(sm);
let fErr = 0, qErr = 0, rErr = 0, levelMismatch = 0, steps = 0;
for (const c of cases.calls) {
  m.reset();
  c.probs.forEach((p, i) => {
    const o = m.push(p, c.regex[i]);
    o.features.forEach((v, k) => { fErr = Math.max(fErr, Math.abs(v - c.features[i][k])); });
    qErr = Math.max(qErr, Math.abs(o.q - c.q[i]));
    rErr = Math.max(rErr, Math.abs(o.risk - c.risk[i]));
    if (o.level !== c.level[i]) levelMismatch++;
    steps++;
  });
}
console.log(`mode ${sm.mode}, trees ${(sm.trees || []).length}, calls ${cases.calls.length}, steps ${steps}`);
console.log(`features max |diff| ${fErr.toExponential(2)}, q ${qErr.toExponential(2)}, risk ${rErr.toExponential(2)}, level mismatches ${levelMismatch}`);
const ok = fErr < 1e-9 && qErr < 1e-6 && rErr < 1e-6 && levelMismatch === 0;
console.log(ok ? "STREAM PARITY OK" : "STREAM PARITY FAILED");
process.exit(ok ? 0 : 1);
