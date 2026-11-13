// Main-thread side of the voice check: loads voice-worker.js on first use, keeps at most one clip in
// flight (newer clips replace a waiting one, so it never falls behind the call), reports each result.
let worker = null, ready = false, busy = false, waiting = null, handlers = {};

export function initVoiceCheck(h) { handlers = h; }

export function loadVoiceCheck() {
  if (worker) return;
  worker = new Worker(new URL("./voice-worker.js", import.meta.url), { type: "module" });
  handlers.onStatus?.("loading", 0);
  worker.onmessage = (e) => {
    const m = e.data;
    if (m.type === "progress") handlers.onStatus?.("loading", m.loaded / m.total);
    else if (m.type === "ready") { ready = true; handlers.onStatus?.("ready"); send(); }
    else if (m.type === "error") { handlers.onStatus?.("error", 0, m.message); worker.terminate(); worker = null; }
    else if (m.type === "result") {
      busy = false;
      if (!m.error) handlers.onResult?.(m.fake, m.ms, m.sec);
      send();
    }
  };
  worker.postMessage({ type: "load" });
}

export function checkVoice(audio) {
  if (!worker) loadVoiceCheck();
  waiting = audio;
  send();
}

export function clearVoiceQueue() { waiting = null; }

function send() {
  if (!ready || busy || !waiting) return;
  const audio = waiting;
  waiting = null;
  busy = true;
  worker.postMessage({ type: "classify", audio, id: Date.now() }, [audio.buffer]);
}
