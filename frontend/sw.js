// Service worker: lets the app open offline after one visit.
// - Own files: network first (so a new deploy shows up at once), cache as fallback.
// - Pinned CDN libraries (jsDelivr URLs with an exact @version): cache first; they never change.
// - Everything else (PeerJS signalling, Hugging Face model files) is not touched.
//   Transformers.js keeps the Whisper model in its own cache ("transformers-cache"), which we never delete.
// Nothing user-generated (transcripts, logs) is ever cached here.
const CACHE = "bosc-shell-v1";
const CDN = "bosc-cdn-v1";
const SHELL = [
  "./", "index.html", "manifest.json", "icon.svg", "css/app.css",
  "js/app.js", "js/util.js", "js/settings.js", "js/state.js", "js/router.js",
  "js/caller.js", "js/bosc.js", "js/shield.js", "js/summary.js",
  "js/audio.js", "js/chunk-worklet.js", "js/asr-worker.js", "js/bosc-core.js",
  "js/detector-onnx.js", "js/stream-model.js", "js/nlp-worker.js",
  "js/victim-cues.js", "js/voice-check.js", "js/voice-worker.js",
  "model/model.json", "model/tactics.json", "demo/scripts.json",
];
const NETWORK_WAIT_MS = 3500;

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => Promise.all(SHELL.map((u) => c.add(u).catch(() => {})))).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k.startsWith("bosc-") && k !== CACHE && k !== CDN).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin === self.location.origin) e.respondWith(networkFirst(req));
  else if (url.hostname === "cdn.jsdelivr.net" && /@\d+\.\d+\.\d+/.test(url.pathname)) e.respondWith(cacheFirst(req));
});

async function networkFirst(req) {
  const cache = await caches.open(CACHE);
  const cached = await cache.match(req, { ignoreSearch: true })
    || (req.mode === "navigate" ? await cache.match("index.html") : undefined);
  const net = fetch(req).then((res) => {
    if (res.ok) cache.put(req, res.clone()).catch(() => {});
    return res;
  });
  if (!cached) return net;
  // slow hotspot: fall back to the cached copy instead of hanging
  const timeout = new Promise((r) => setTimeout(() => r(cached), NETWORK_WAIT_MS));
  return Promise.race([net.catch(() => cached), timeout]);
}

async function cacheFirst(req) {
  const cache = await caches.open(CDN);
  const hit = await cache.match(req);
  if (hit) return hit;
  const res = await fetch(req);
  if (res.ok || res.type === "opaque") cache.put(req, res.clone()).catch(() => {});
  return res;
}
