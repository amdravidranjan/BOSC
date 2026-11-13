// State shared between the BOSC, Pay (Shield) and Summary screens.

export const PEER_PREFIX = "bosc-ait26-";

export const state = {
  core: null,            // {scorer, detector, model} from bosc-core.js or detector-onnx.js
  detectorKind: "basic", // "basic" (Stage 0) | "advanced" (ONNX)
  session: null,         // {source, start, stop(), log: []}
  lastSession: null,
  scripts: [],
};

export const scriptsReady = fetch("demo/scripts.json")
  .then((r) => r.json())
  .then((j) => (state.scripts = j.scripts))
  .catch(() => (state.scripts = []));

export const LEVEL_WORD = { SAFE: "Safe", WARN: "Caution", PAUSE: "High risk" };

// Map raw risk onto a 0..1 display scale so the meter's bands line up with the thresholds.
export function display(r) {
  const { warn, pause } = state.core.scorer;
  if (r < warn) return 0.5 * (r / warn);
  if (r < pause) return 0.5 + 0.2 * ((r - warn) / (pause - warn));
  return Math.min(1, 0.7 + 0.3 * ((r - pause) / (1 - pause)) * 1.6);
}

// Add a non-speech event (pay attempt, override, screen off…) to the current or last session log.
export function logEvent(event, extra = {}) {
  const s = state.session || state.lastSession;
  if (!s || !s.log) return;
  s.log.push({ t: Date.now() - s.start, event, ...extra });
}
