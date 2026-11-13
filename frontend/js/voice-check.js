// Main-thread side of voice-worker.js. One clip in flight; at most two waiting (newest kept), so it never
// falls behind a live call. analyseClip() is fire-and-forget (results via onResult); printOf() is awaited
// (used for enrolling a trusted voice).
let worker = null, ready = false, busy = false, queue = [], handlers = {}, seq = 1, awaiting = new Map();

export function initVoiceCheck(h) {
  handlers = h;
  if (worker) return;
  worker = new Worker(new URL("./voice-worker.js", import.meta.url), { type: "module" });
  worker.onmessage = (e) => {
    const m = e.data;
    if (m.type === "ready") { ready = true; handlers.onReady?.(m); pump(); }
    else if (m.type === "error") { handlers.onError?.(m.message); }
    else if (m.type === "result") {
      busy = false;
      const w = awaiting.get(m.id);
      if (w) { awaiting.delete(m.id); m.error ? w.reject(new Error(m.error)) : w.resolve(m); }
      else if (!m.error) handlers.onResult?.(m);
      pump();
    }
  };
  worker.postMessage({ type: "load" });
}

export function analyseClip(audio, who, wantPrint = true) {
  if (!worker) return;
  queue.push({ type: "clip", id: seq++, audio, who, wantPrint });
  while (queue.length > 2) queue.shift();
  pump();
}

export function printOf(audio) {
  if (!worker) return Promise.reject(new Error("voice worker not started"));
  const id = seq++;
  return new Promise((resolve, reject) => {
    awaiting.set(id, { resolve, reject });
    queue.push({ type: "clip", id, audio, who: "enroll", wantPrint: true });
    pump();
  });
}

export function clearVoiceQueue() { queue = []; }

function pump() {
  if (!ready || busy || !queue.length) return;
  const m = queue.shift();
  busy = true;
  worker.postMessage(m, [m.audio.buffer]);
}
