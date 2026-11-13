// Trusted voices (impersonation check). The user saves a short voice-print of family members in Settings.
// On a call, when the caller claims to be one of them ("it's me, your daughter", or says their name),
// the caller's voice-print is compared with the saved one.
import { printSimilarity, meanPrint } from "./voice-features.js";
import { settings, saveSettings } from "./settings.js";

export const RELATIONS = ["son", "daughter", "grandson", "granddaughter", "nephew", "niece", "brother", "sister",
  "mother", "father", "mom", "mum", "dad", "papa", "mummy", "amma", "appa", "husband", "wife", "friend", "neighbour", "neighbor", "boss", "colleague"];

// thresholds from the voice-print evaluation (same speaker ~0.88, different ~0.42)
export const MATCH = 0.72, MISMATCH = 0.5;

export function trustedVoices() { return Array.isArray(settings.voices) ? settings.voices : []; }

export function saveTrustedVoice(name, relation, print) {
  const v = trustedVoices().filter((x) => x.name.toLowerCase() !== name.toLowerCase());
  v.push({ name, relation: relation || "", print: Array.from(print), at: Date.now() });
  settings.voices = v;
  saveSettings();
}

export function removeTrustedVoice(name) {
  settings.voices = trustedVoices().filter((x) => x.name !== name);
  saveSettings();
}

// Did the caller claim to be someone? Returns {relation?, name?} or null.
export function detectClaim(text) {
  const t = text.toLowerCase();
  const rel = new RegExp(`\\b(?:this is|it'?s me,?|it'?s|i am|i'm|its)\\s+(?:your\\s+)?(${RELATIONS.join("|")})\\b`).exec(t)
    || new RegExp(`\\byour\\s+(${RELATIONS.join("|")})\\s+(?:here|speaking|calling)\\b`).exec(t);
  if (rel) return { relation: rel[1] };
  for (const v of trustedVoices()) {
    const n = v.name.toLowerCase();
    if (n.length >= 3 && new RegExp(`\\b(?:this is|it'?s me,?|it'?s|i am|i'm)\\s+${n.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`).test(t)) return { name: v.name };
  }
  return null;
}

function relationOf(v) { return (v.relation || v.name).toLowerCase(); }

// Compare the caller's accumulated prints with the saved voices.
// Returns {claimed, best: {name, sim}, verdict: "match"|"mismatch"|"unclear"|null}
export function evaluateVoice(callerPrints, claim, models) {
  if (!callerPrints.length) return { claimed: null, best: null, verdict: null };
  const p = meanPrint(callerPrints);
  const voices = trustedVoices();
  let best = null;
  for (const v of voices) {
    const sim = printSimilarity(p, v.print, models);
    if (!best || sim > best.sim) best = { name: v.name, sim };
  }
  let claimed = null;
  if (claim) {
    claimed = voices.find((v) => (claim.name && v.name.toLowerCase() === claim.name.toLowerCase())
      || (claim.relation && (relationOf(v) === claim.relation || aliases(relationOf(v)).includes(claim.relation)))) || null;
  }
  let verdict = null;
  if (claimed) {
    const sim = printSimilarity(p, claimed.print, models);
    verdict = sim >= MATCH ? "match" : sim <= MISMATCH ? "mismatch" : "unclear";
    return { claimed: { name: claimed.name, sim }, best, verdict, nPrints: callerPrints.length };
  }
  return { claimed: null, best, verdict: best && best.sim >= MATCH ? "known" : null, nPrints: callerPrints.length };
}

function aliases(rel) {
  const groups = [["mother", "mom", "mum", "mummy", "amma"], ["father", "dad", "papa", "appa"], ["neighbour", "neighbor"]];
  for (const g of groups) if (g.includes(rel)) return g;
  return [rel];
}
