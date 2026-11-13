// State shared between the BOSC, Pay (Shield), Summary and Guardian screens.

export const PEER_PREFIX = "bosc-ait26-";

export const state = {
  core: null,            // {scorer, detector, model} from bosc-core.js or detector-onnx.js
  detectorKind: "basic", // "basic" (Stage 0) | "advanced" (ONNX)
  session: null,         // see startSession() in bosc.js
  lastSession: null,     // the finished session, with .report
  scripts: [],
};

export const scriptsReady = fetch("demo/scripts.json")
  .then((r) => r.json())
  .then((j) => (state.scripts = j.scripts))
  .catch(() => (state.scripts = []));

// Words a 67-year-old reads in one glance.
export const LEVEL_WORD = { SAFE: "Looks fine", WARN: "Be careful", PAUSE: "Likely scam" };
export const LEVEL_RANK = { SAFE: 0, WARN: 1, PAUSE: 2 };

// Add a non-speech event (pay attempt, override, screen off…) to the current or last session log.
export function logEvent(event, extra = {}) {
  const s = state.session || state.lastSession;
  if (!s || !s.log) return;
  s.log.push({ t: Date.now() - s.start, event, ...extra });
}
