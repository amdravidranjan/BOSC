// Checks that web/js/bosc-core.js gives the same outputs as train/bosc_core.py.
// Run: node train/parity_test.mjs   (after python train/train.py)
import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const core = await import(pathToFileURL(join(here, "..", "web", "js", "bosc-core.js")).href);
const mj = JSON.parse(readFileSync(join(here, "..", "web", "model", "model.json"), "utf8"));
const tj = JSON.parse(readFileSync(join(here, "..", "web", "model", "tactics.json"), "utf8"));
const cases = JSON.parse(readFileSync(join(here, "parity_cases.json"), "utf8"));

const model = new core.Model(mj);
const det = new core.TacticDetector(tj);
let maxErr = 0, tacticMismatch = 0;
for (const c of cases.proba) maxErr = Math.max(maxErr, Math.abs(model.proba(c.text) - c.p));
for (const c of cases.tactics) {
  const got = Object.keys(det.detect(c.text)).sort().join(",");
  if (got !== c.found.join(",")) { tacticMismatch++; console.log("tactic mismatch:", JSON.stringify(c.text), got, "vs", c.found); }
}
const sc = new core.StreamingScorer(model, det, mj.scorer);
let streamErr = 0;
cases.stream.utterances.forEach((u, i) => { streamErr = Math.max(streamErr, Math.abs(sc.push(u).risk - cases.stream.risk[i])); });

console.log(`proba cases ${cases.proba.length}, max |diff| = ${maxErr.toExponential(2)}`);
console.log(`tactic cases ${cases.tactics.length}, mismatches = ${tacticMismatch}`);
console.log(`stream turns ${cases.stream.utterances.length}, max |diff| = ${streamErr.toExponential(2)}`);
const ok = maxErr < 1e-9 && tacticMismatch === 0 && streamErr < 1e-9;
console.log(ok ? "PARITY OK" : "PARITY FAILED");
process.exit(ok ? 0 : 1);
