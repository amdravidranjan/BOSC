import { PRESSURE } from "./bosc-core.js";
import { loadScorer } from "./detector-onnx.js";
import { UtteranceChunker, getAudioContext } from "./audio.js";

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const PEER_PREFIX = "bosc-ait26-";

// ------------------------------------------------------------------ settings
const DEFAULTS = {
  model: "onnx-community/whisper-tiny.en", forceWasm: false, tname: "", tnum: "",
  avg: 1500, sens: 0.008, speak: true, code: "",
};
const settings = { ...DEFAULTS };
try { Object.assign(settings, JSON.parse(localStorage.getItem("bosc-settings") || "{}")); } catch {}
function saveSettings() { try { localStorage.setItem("bosc-settings", JSON.stringify(settings)); } catch {} }

function toast(msg, ms = 2600) {
  const t = document.createElement("div");
  t.className = "toast";
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), ms);
}
const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

// ------------------------------------------------------------------ routing
const VIEWS = ["home", "caller", "bosc", "pay", "summary"];
const inited = new Set();
function show(view) {
  if (!VIEWS.includes(view)) view = "home";
  for (const v of VIEWS) $("#v-" + v).hidden = v !== view;
  if (!inited.has(view)) { inited.add(view); ({ caller: initCaller, bosc: initBosc, pay: initPay })[view]?.(); }
  if (view === "pay") refreshPay();
  if (view === "summary") renderSummary();
  window.scrollTo(0, 0);
}
function go(view) { if (location.hash !== "#" + view) location.hash = view; else show(view); }
window.addEventListener("hashchange", () => show(location.hash.slice(1)));
document.addEventListener("click", (e) => {
  const b = e.target.closest("[data-go]");
  if (b) { e.preventDefault(); go(b.dataset.go); }
});

let SCRIPTS = [];
const scriptsReady = fetch("demo/scripts.json").then((r) => r.json()).then((j) => (SCRIPTS = j.scripts));

// ================================================================== CALLER
let cPeer = null, cCall = null, cStream = null, cTimer = null, cLine = 0;
function initCaller() {
  scriptsReady.then(() => {
    const sel = $("#c-script");
    sel.innerHTML = SCRIPTS.map((s) => `<option value="${s.id}">${esc(s.title)}</option>`).join("");
    sel.onchange = () => { cLine = 0; renderPrompter(); };
    renderPrompter();
  });
  $("#c-prev").onclick = () => { cLine = Math.max(0, cLine - 1); renderPrompter(); };
  $("#c-next").onclick = () => { cLine++; renderPrompter(); };
  $("#c-code").value = (() => { try { return localStorage.getItem("bosc-last-code") || ""; } catch { return ""; } })();
  $("#c-call").onclick = callerDial;
  $("#c-hang").onclick = () => callerEnd(true);
  $("#c-mute").onclick = () => {
    if (!cStream) return;
    const tr = cStream.getAudioTracks()[0];
    tr.enabled = !tr.enabled;
    $("#c-mute").textContent = tr.enabled ? "Mute" : "Unmute";
  };
  setCState("Connecting…");
  cPeer = new Peer({ debug: 1 });
  cPeer.on("open", () => setCState("Ready"));
  cPeer.on("disconnected", () => { try { cPeer.reconnect(); } catch {} });
  cPeer.on("error", (err) => {
    if (err.type === "peer-unavailable") toast("No BOSC phone is waiting with that code.");
    else toast("Call error: " + err.type);
    callerEnd(false);
  });
}
function renderPrompter() {
  const s = SCRIPTS.find((x) => x.id === $("#c-script").value) || SCRIPTS[0];
  if (!s) return;
  if (cLine >= s.lines.length) { $("#c-prompter").innerHTML = `<span class="n">End of script</span>Hang up when ready.`; return; }
  $("#c-prompter").innerHTML = `<span class="n">Line ${cLine + 1} of ${s.lines.length} · ${esc(s.kind)}</span>${esc(s.lines[cLine])}`;
}
function setCState(t, live = false) { const p = $("#c-state"); p.lastChild.textContent = t; p.classList.toggle("live", live); }
async function callerDial() {
  const code = $("#c-code").value.trim();
  if (!/^\d{4}$/.test(code)) return toast("Enter the 4-digit code shown on the BOSC phone.");
  try { localStorage.setItem("bosc-last-code", code); } catch {}
  try {
    cStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
  } catch { return toast("Microphone permission is needed to call."); }
  setCState("Ringing…");
  cCall = cPeer.call(PEER_PREFIX + code, cStream);
  cCall.on("stream", (remote) => {
    const a = $("#c-remote");
    a.srcObject = remote;
    a.play().catch(() => {});
    setCState("In call", true);
    $("#c-dial").hidden = true;
    $("#c-incall").hidden = false;
    const t0 = Date.now();
    clearInterval(cTimer);
    cTimer = setInterval(() => { $("#c-timer").textContent = fmtTime(Date.now() - t0); }, 500);
  });
  cCall.on("close", () => callerEnd(false));
  cCall.on("error", () => callerEnd(false));
}
function callerEnd(local) {
  if (local && cCall) try { cCall.close(); } catch {}
  cCall = null;
  if (cStream) cStream.getTracks().forEach((t) => t.stop());
  cStream = null;
  clearInterval(cTimer);
  $("#c-dial").hidden = false;
  $("#c-incall").hidden = true;
  $("#c-mute").textContent = "Mute";
  setCState(cPeer && cPeer.open ? "Ready" : "Idle");
}
const fmtTime = (ms) => { const s = Math.floor(ms / 1000); return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`; };

// ================================================================== BOSC
let core = null;           // {scorer, detector, model}
let worker = null, asrReady = false, asrBusy = false, asrDevice = "";
let pending = [];          // audio chunks waiting for ASR
let chunker = null;
let session = null;        // {source, start, stop()}
let lastSession = null;
let peer = null, incoming = null, activeCall = null, micStream = null;
let spokenLevels = new Set();
let wakeLock = null;

async function initBosc() {
  core = await loadScorer("model/");
  $("#b-detector-status").textContent = core.kind;
  buildMeter();
  buildChips();
  renderRisk();
  // source tabs
  $$("#b-src button").forEach((b) => (b.onclick = () => {
    $$("#b-src button").forEach((x) => x.classList.toggle("on", x === b));
    for (const s of ["call", "mic", "script"]) $("#b-src-" + s).hidden = s !== b.dataset.src;
  }));
  scriptsReady.then(() => {
    $("#b-script").innerHTML = SCRIPTS.map((s) => `<option value="${s.id}">${esc(s.title)}</option>`).join("");
  });
  $("#b-mic-start").onclick = startMic;
  $("#b-file").onchange = (e) => e.target.files[0] && startFile(e.target.files[0]);
  $("#b-script-start").onclick = startScript;
  $("#b-hang").onclick = () => endSession("hangup");
  $("#b-type-form").onsubmit = (e) => {
    e.preventDefault();
    const v = $("#b-type").value.trim();
    if (!v) return;
    if (!session) startSession("typed");
    addUtterance(v, { typed: true });
    $("#b-type").value = "";
  };
  $("#in-accept").onclick = acceptCall;
  $("#in-reject").onclick = () => { $("#incoming").hidden = true; try { incoming && incoming.close(); } catch {} incoming = null; };
  // settings
  $("#b-settings-btn").onclick = openSettings;
  $("#set-close").onclick = closeSettings;
  updateTrusted();
  chunker = new UtteranceChunker({ onChunk, onLevel, minRms: +settings.sens });
  setBState("Guarding");
  initPeer();
  loadAsr();
}

// ---------------------------------------------------------------- settings UI
function openSettings() {
  $("#set-model").value = settings.model; $("#set-wasm").checked = !!settings.forceWasm;
  $("#set-tname").value = settings.tname; $("#set-tnum").value = settings.tnum;
  $("#set-avg").value = settings.avg; $("#set-sens").value = String(settings.sens);
  $("#set-speak").checked = !!settings.speak;
  $("#settings").hidden = false;
}
function closeSettings() {
  const prevModel = settings.model, prevWasm = settings.forceWasm;
  settings.model = $("#set-model").value; settings.forceWasm = $("#set-wasm").checked;
  settings.tname = $("#set-tname").value.trim(); settings.tnum = $("#set-tnum").value.trim();
  settings.avg = Math.max(1, +$("#set-avg").value || 1500); settings.sens = +$("#set-sens").value;
  settings.speak = $("#set-speak").checked;
  saveSettings();
  if (chunker) chunker.minRms = settings.sens;
  updateTrusted();
  $("#settings").hidden = true;
  if (prevModel !== settings.model || prevWasm !== settings.forceWasm) loadAsr();
}
function updateTrusted() {
  const href = settings.tnum ? "tel:" + settings.tnum.replace(/[^\d+]/g, "") : "tel:";
  const label = settings.tname ? `Call ${settings.tname}` : "Call family";
  $("#b-trusted").href = href; $("#b-trusted").textContent = label;
  $("#pause-family").href = href; $("#pause-family").textContent = settings.tname ? `Call ${settings.tname}` : "Call my trusted contact";
  for (const a of [$("#b-trusted"), $("#pause-family")]) {
    a.onclick = settings.tnum ? null : (e) => { e.preventDefault(); toast("Add a trusted contact number in Settings (⚙)."); };
  }
}
function setBState(t, live = false) { const p = $("#b-state"); p.lastChild.textContent = t; p.classList.toggle("live", live); }

// ---------------------------------------------------------------- ASR worker
function loadAsr() {
  if (worker) worker.terminate();
  asrReady = false; asrBusy = false;
  worker = new Worker(new URL("./asr-worker.js", import.meta.url), { type: "module" });
  $("#b-asr-text").textContent = "Loading speech model (first time downloads once, then works offline)…";
  $("#b-asr-prog").hidden = false;
  worker.onmessage = (e) => {
    const m = e.data;
    if (m.type === "progress") {
      $("#b-asr-prog i").style.width = `${Math.round((100 * m.loaded) / m.total)}%`;
    } else if (m.type === "ready") {
      asrReady = true; asrDevice = m.device;
      $("#b-asr-prog").hidden = true;
      const name = settings.model.split("/")[1];
      $("#b-asr-text").textContent = `Speech model ready on this device: ${name} · ${m.device === "webgpu" ? "GPU" : "CPU"} · warm-up ${m.warmupMs} ms`;
      pump();
    } else if (m.type === "error") {
      $("#b-asr-prog").hidden = true;
      $("#b-asr-text").textContent = "Speech model failed to load: " + m.message;
    } else if (m.type === "text") {
      asrBusy = false;
      if (m.text && session) addUtterance(m.text, { asrMs: m.ms, dur: m.dur });
      setListen(session ? "listening" : "idle");
      pump();
    }
  };
  worker.postMessage({ type: "load", model: settings.model, forceWasm: settings.forceWasm });
}
function onChunk(audio) {
  pending.push(audio);
  // never keep more than ~30 s of audio waiting
  let total = pending.reduce((s, a) => s + a.length, 0);
  while (total > 16000 * 30 && pending.length > 1) total -= pending.shift().length;
  pump();
}
function pump() {
  if (!asrReady || asrBusy || !pending.length) return;
  let take = [], n = 0;
  while (pending.length && n + pending[0].length <= 16000 * 14) { n += pending[0].length; take.push(pending.shift()); }
  if (!take.length) take.push(pending.shift());
  const audio = new Float32Array(take.reduce((s, a) => s + a.length, 0));
  let o = 0;
  for (const a of take) { audio.set(a, o); o += a.length; }
  asrBusy = true;
  setListen("transcribing…");
  worker.postMessage({ type: "transcribe", audio, id: Date.now() }, [audio.buffer]);
}
let levelT = 0;
function onLevel(v, speech) {
  const now = performance.now();
  if (now - levelT < 60) return;
  levelT = now;
  $("#b-level").style.width = `${Math.round(v * 100)}%`;
  if (!asrBusy && session && session.source !== "script") setListen(speech ? "hearing speech" : "listening");
}
function setListen(t) { $("#b-listen").textContent = t; }

// ---------------------------------------------------------------- sessions
function startSession(source, stop = () => {}) {
  if (session) endSession("replaced", true);
  core.scorer.reset();
  spokenLevels = new Set();
  session = { source, start: Date.now(), stop, log: [] };
  $("#b-transcript").innerHTML = `<div class="empty">Listening… the caller's words will appear here.</div>`;
  $("#b-idle").hidden = true;
  renderRisk();
  const names = { call: "In call", mic: "Listening (mic)", file: "Replaying file", script: "Demo script", typed: "Typed test" };
  setBState(names[source] || "Active", true);
  setListen(source === "script" || source === "typed" ? "text input" : "listening");
  $("#b-privacy").textContent = source === "script" || source === "typed"
    ? "Text-only demo. No audio is used."
    : "Listening now. Audio is processed on this phone and discarded. Nothing is recorded.";
  try { navigator.wakeLock?.request("screen").then((w) => (wakeLock = w)).catch(() => {}); } catch {}
}
function endSession(reason, silent = false) {
  if (!session) return;
  const s = session;
  session = null;
  try { s.stop(); } catch {}
  if (chunker) chunker.detach();
  pending = [];
  try { wakeLock && wakeLock.release(); } catch {}
  lastSession = { ...s, end: Date.now(), history: core.scorer.history.slice(), seen: [...core.scorer.seen],
    warn: core.scorer.warn, pause: core.scorer.pause, reason };
  core.scorer.cancelPending?.();
  $("#b-idle").hidden = false;
  setBState("Guarding");
  setListen("idle");
  $("#b-level").style.width = "0";
  $("#b-privacy").textContent = "Listening only while a call is active. Audio is processed on this phone and discarded.";
  if (!silent && lastSession.history.length) go("summary");
}

// ---- WebRTC call (BOSC side answers)
function newCode() { return String(Math.floor(1000 + Math.random() * 9000)); }
function initPeer() {
  if (!settings.code) { settings.code = newCode(); saveSettings(); }
  if (typeof Peer === "undefined") { $("#b-peer-status").textContent = "Call library failed to load (offline?). Use This mic or Demo script."; return; }
  peer = new Peer(PEER_PREFIX + settings.code, { debug: 1 });
  $("#b-code").textContent = "····";
  peer.on("open", () => {
    $("#b-code").textContent = settings.code;
    $("#b-peer-status").textContent = "Waiting for a call. Keep this screen open.";
  });
  peer.on("call", (call) => {
    if (activeCall) { try { call.close(); } catch {} return; }
    incoming = call;
    $("#in-from").textContent = "Unknown caller";
    $("#incoming").hidden = false;
    try { navigator.vibrate?.([300, 200, 300]); } catch {}
  });
  peer.on("disconnected", () => { $("#b-peer-status").textContent = "Reconnecting…"; try { peer.reconnect(); } catch {} });
  peer.on("error", (err) => {
    if (err.type === "unavailable-id") {
      settings.code = newCode(); saveSettings();
      try { peer.destroy(); } catch {}
      initPeer();
    } else if (err.type === "network" || err.type === "server-error" || err.type === "socket-error") {
      $("#b-peer-status").textContent = "Can't reach the signalling server. Check internet, or use This mic.";
    } else {
      $("#b-peer-status").textContent = "Call error: " + err.type;
    }
  });
}
async function acceptCall() {
  $("#incoming").hidden = true;
  const call = incoming;
  incoming = null;
  if (!call) return;
  const ctx = getAudioContext();
  ctx.resume();
  try {
    micStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
  } catch {
    toast("Microphone permission is needed to talk. Answering listen-only.");
    micStream = null;
  }
  activeCall = call;
  call.answer(micStream || undefined);
  startSession("call", () => {
    try { call.close(); } catch {}
    if (micStream) micStream.getTracks().forEach((t) => t.stop());
    micStream = null;
    activeCall = null;
    const a = $("#b-remote"); a.srcObject = null;
  });
  let attached = false;
  call.on("stream", (remote) => {
    if (attached) return; // PeerJS can fire "stream" once per track
    attached = true;
    // Chrome only feeds a remote WebRTC stream into Web Audio if it is also attached to a media element
    const a = $("#b-remote");
    a.srcObject = remote;
    a.play().catch(() => {});
    const src = ctx.createMediaStreamSource(remote);
    chunker.attach(ctx, src);
  });
  call.on("close", () => endSession("remote-hangup"));
  call.on("error", () => endSession("error"));
}

// ---- local microphone
async function startMic() {
  const ctx = getAudioContext();
  ctx.resume();
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: true, autoGainControl: true } });
  } catch { return toast("Microphone permission was denied."); }
  startSession("mic", () => stream.getTracks().forEach((t) => t.stop()));
  chunker.attach(ctx, ctx.createMediaStreamSource(stream));
}

// ---- replay an audio file (played aloud + analysed, same pipeline)
async function startFile(file) {
  const ctx = getAudioContext();
  ctx.resume();
  let buf;
  try { buf = await ctx.decodeAudioData(await file.arrayBuffer()); } catch { return toast("Couldn't read that audio file."); }
  const src = ctx.createBufferSource();
  src.buffer = buf;
  src.connect(ctx.destination);
  startSession("file", () => { try { src.stop(); } catch {} });
  chunker.attach(ctx, src);
  src.onended = () => {
    chunker.flush();
    // let the last chunk finish transcribing before ending
    const wait = () => (asrBusy || pending.length) ? setTimeout(wait, 300) : session && session.source === "file" && endSession("file-ended");
    setTimeout(wait, 500);
  };
  src.start();
  $("#b-file").value = "";
}

// ---- scripted demo (text only)
function startScript() {
  const s = SCRIPTS.find((x) => x.id === $("#b-script").value);
  if (!s) return;
  let i = 0, timer = null;
  startSession("script", () => clearTimeout(timer));
  const scriptSession = session;
  const step = async () => {
    if (session !== scriptSession) return;
    if (i >= s.lines.length) { timer = setTimeout(() => endSession("script-ended"), 2500); return; }
    await addUtterance(s.lines[i++], { script: s.id });
    if (session !== scriptSession) return;
    timer = setTimeout(step, 2800);
  };
  timer = setTimeout(step, 600);
}

// ---------------------------------------------------------------- scoring + UI
const LEVEL_WORD = { SAFE: "Safe", WARN: "Caution", PAUSE: "High risk" };
// Map raw risk onto a 0..1 display scale so the meter's bands line up with the thresholds.
function display(r) {
  const { warn, pause } = core.scorer;
  if (r < warn) return 0.5 * (r / warn);
  if (r < pause) return 0.5 + 0.2 * ((r - warn) / (pause - warn));
  return Math.min(1, 0.7 + 0.3 * ((r - pause) / (1 - pause)) * 1.6);
}
function buildMeter() { $("#b-meter").innerHTML = "<i></i>".repeat(10); }
function buildChips() {
  const order = ["authority_claim", "threat", "urgency", "secrecy_isolation", "stay_on_line", "credential_request", "remote_access", "payment_instruction"];
  $("#b-chips").innerHTML = order.map((t) => `<span class="chip" data-t="${t}">${esc(core.detector.labels[t])}</span>`).join("");
}
function advice(seen, level) {
  const has = (t) => seen.has(t);
  let tip;
  if (has("stay_on_line") || (has("authority_claim") && has("threat"))) tip = "Real police or officials never arrest anyone or demand money over a phone or video call.";
  else if (has("remote_access")) tip = "Don't install any app a caller asks for. Screen sharing lets them see your OTPs.";
  else if (has("credential_request")) tip = "Never share an OTP, PIN or CVV with a caller. Banks never ask for it.";
  else if (has("payment_instruction")) tip = "Don't send money to an account or UPI ID a caller gives you.";
  else if (has("secrecy_isolation")) tip = "Scammers ask you to keep it secret. Talk to someone you trust first.";
  else if (has("threat") || has("urgency")) tip = "Pressure to act fast is a warning sign. You can hang up and call back on an official number.";
  else tip = "Don't share OTPs or send money because of this call.";
  if (level === "PAUSE") return "This call matches a known scam pattern. " + tip;
  if (level === "WARN") return "Be careful. " + tip;
  return seen.size ? "Listening. Nothing alarming yet." : "No pressure tactics detected.";
}
function renderRisk() {
  const sc = core.scorer;
  const lvl = sc.level();
  const d = display(sc.risk);
  $("#b-risk").dataset.level = lvl;
  $("#b-word").textContent = LEVEL_WORD[lvl];
  $("#b-score").textContent = `risk ${Math.round(d * 100)}/100`;
  const on = Math.round(d * 10);
  const cls = lvl === "PAUSE" ? "d" : lvl === "WARN" ? "w" : "s";
  $$("#b-meter i").forEach((el, i) => { el.className = i < on ? "on " + cls : ""; });
  $("#b-warning").textContent = advice(sc.seen, lvl);
  $$("#b-chips .chip").forEach((c) => c.classList.toggle("on", sc.seen.has(c.dataset.t)));
}
function highlight(text, tactics) {
  const spans = Object.values(tactics).flat().sort((a, b) => a[0] - b[0]);
  let out = "", i = 0;
  for (const [s, e] of spans) {
    if (s < i) continue;
    out += esc(text.slice(i, s)) + "<mark>" + esc(text.slice(s, e)) + "</mark>";
    i = e;
  }
  return out + esc(text.slice(i));
}
async function addUtterance(text, meta = {}) {
  if (!session) startSession("typed");
  const currentSession = session;
  const prev = core.scorer.level();
  let r;
  try { r = await core.scorer.push(text); }
  catch (error) {
    if (session === currentSession && error.name !== "AbortError") setListen("Detector error: " + error.message);
    return;
  }
  if (session !== currentSession) return;
  session.log.push({ t: Date.now() - session.start, text, p: +r.p.toFixed(4), risk: +r.risk.toFixed(4), level: r.level, tactics: Object.keys(r.tactics), ...meta });
  const box = $("#b-transcript");
  box.querySelector(".empty")?.remove();
  const el = document.createElement("div");
  el.className = "utt";
  el.dataset.level = r.level;
  const tl = Object.keys(r.tactics).filter((t) => t !== "authority_claim" || r.level !== "SAFE").map((t) => core.detector.labels[t]);
  const lat = meta.asrMs ? ` · speech-to-text ${meta.asrMs} ms for ${meta.dur.toFixed(1)} s` : "";
  el.innerHTML = `${highlight(text, r.tactics)}<div class="meta">${tl.length ? esc(tl.join(" · ")) + " · " : ""}risk ${Math.round(display(r.risk) * 100)}${lat}</div>`;
  box.appendChild(el);
  box.scrollTop = box.scrollHeight;
  renderRisk();
  const lvl = r.level;
  const rank = { SAFE: 0, WARN: 1, PAUSE: 2 };
  if (rank[lvl] > rank[prev] && !spokenLevels.has(lvl)) {
    spokenLevels.add(lvl);
    try { navigator.vibrate?.(lvl === "PAUSE" ? [400, 150, 400, 150, 400] : [250]); } catch {}
    if (settings.speak && "speechSynthesis" in window) {
      try {
        speechSynthesis.cancel();
        const u = new SpeechSynthesisUtterance(lvl === "PAUSE" ? "Warning. This call looks like a scam. Do not share O T P or send money." : "Be careful with this call.");
        u.lang = "en-IN"; u.rate = 0.95;
        speechSynthesis.speak(u);
      } catch {}
    }
  }
}

// ================================================================== PAY (Shield)
let waitTimer = null, mediumTimer = null, payAllowedAt = 0;
function initPay() {
  $("#p-amt").oninput = () => { $("#p-amt").value = $("#p-amt").value.replace(/[^\d]/g, ""); refreshPay(); };
  $("#p-pay").onclick = onPay;
  $("#p-again").onclick = () => { $("#p-done").hidden = true; $("#p-form").hidden = false; refreshPay(); };
  $("#pause-cancel").onclick = () => { closePause(); toast("Payment cancelled. Good call."); go("bosc"); };
  $("#pause-wait").onclick = () => {
    closePause();
    payAllowedAt = Date.now() + 60000;
    tickWait();
  };
  $("#pause-override").onclick = () => { closePause(); logOverride(); paid(); };
  for (const id of ["#p-screenshare", "#p-newpayee", "#p-payee", "#p-upi"]) $(id).addEventListener("input", refreshPay);
}
function shieldEvaluate() {
  const sc = core ? core.scorer : null;
  const onCall = !!session;
  const callLevel = sc && onCall ? sc.level() : "SAFE";
  const callRisk = sc && onCall ? display(sc.risk) : 0;
  const amount = +$("#p-amt").value || 0;
  const ratio = amount / Math.max(1, +settings.avg || 1500);
  const newPayee = $("#p-newpayee").checked;
  const screen = $("#p-screenshare").checked;
  const kw = /(safe|secure|verification|rbi|police|customs|escrow|refund|court|cbi)/i.test($("#p-payee").value + " " + $("#p-upi").value);
  // Hand-set logistic weights (documented; a production model needs real fraud outcomes from a partner)
  const z = -4.0 + 1.2 * onCall + 5.0 * callRisk + 0.8 * newPayee + 0.9 * Math.min(3, Math.max(0, Math.log10(ratio))) + 2.2 * screen + 1.0 * kw;
  const p = 1 / (1 + Math.exp(-z));
  let level = p >= 0.7 ? "HIGH" : p >= 0.35 ? "MEDIUM" : "LOW";
  if (callLevel === "PAUSE") level = "HIGH";
  else if (callLevel === "WARN" && level === "LOW") level = "MEDIUM";
  const reasons = [];
  if (onCall && callLevel !== "SAFE") reasons.push(`You are on a call that shows scam pressure (${LEVEL_WORD[callLevel].toLowerCase()}).`);
  else if (onCall) reasons.push("You are on a call right now.");
  if (sc && onCall) {
    const seen = [...sc.seen].filter((t) => PRESSURE.has(t)).map((t) => core.detector.labels[t]);
    if (seen.length) reasons.push("The caller used: " + seen.join(", ") + ".");
  }
  if (screen) reasons.push("Screen sharing or a remote-control app is active.");
  if (ratio >= 5) reasons.push(`This is ${Math.round(ratio)}× your usual payment.`);
  if (newPayee) reasons.push("You have never paid this person before.");
  if (kw) reasons.push(`The payee name looks like a "safe account". Real agencies never ask for this.`);
  return { p, level, reasons, onCall, callLevel, amount };
}
function refreshPay() {
  if (!inited.has("pay")) return;
  const ev = shieldEvaluate();
  const amt = ev.amount.toLocaleString("en-IN");
  $("#p-pay").textContent = `Pay ₹${amt}`;
  $("#p-callbar").dataset.level = ev.callLevel;
  $("#p-calltext").textContent = ev.onCall
    ? `On a call · BOSC: ${LEVEL_WORD[ev.callLevel]}`
    : "Not on a call";
  $("#p-sig-list").innerHTML = `Shield score ${(ev.p * 100).toFixed(0)}/100 → <b>${ev.level}</b><br>` + ev.reasons.map(esc).join("<br>");
  if (!mediumTimer && !payAllowedAt) { $("#p-inline").hidden = true; $("#p-pay").disabled = false; }
}
function onPay() {
  const ev = shieldEvaluate();
  if (Date.now() < payAllowedAt) return;
  if (ev.level === "HIGH" && !(payAllowedAt && Date.now() >= payAllowedAt)) return openPause(ev);
  if (ev.level === "MEDIUM" && !mediumTimer && !payAllowedAt) {
    let n = 10;
    $("#p-inline").hidden = false;
    $("#p-pay").disabled = true;
    const tick = () => {
      $("#p-inline").textContent = `Take a moment. ${ev.reasons[0] || "This payment looks unusual."} You can pay in ${n} s.`;
      if (n-- <= 0) { clearInterval(mediumTimer); mediumTimer = null; $("#p-pay").disabled = false; $("#p-inline").textContent = "If you are sure, tap Pay again."; payAllowedAt = Date.now(); return; }
    };
    tick();
    mediumTimer = setInterval(tick, 1000);
    return;
  }
  paid();
}
function openPause(ev) {
  $("#pause-reasons").innerHTML = ev.reasons.map((r) => `<li>${esc(r)}</li>`).join("");
  $("#pause-advice").textContent = session ? advice(core.scorer.seen, "PAUSE").replace("This call matches a known scam pattern. ", "") : "Take a minute. Check with someone you trust before sending money.";
  $("#pause").hidden = false;
  try { navigator.vibrate?.([200, 100, 200]); } catch {}
}
function closePause() { $("#pause").hidden = true; }
function tickWait() {
  clearInterval(waitTimer);
  $("#p-inline").hidden = false;
  $("#p-pay").disabled = true;
  waitTimer = setInterval(() => {
    const left = Math.ceil((payAllowedAt - Date.now()) / 1000);
    if (left <= 0) {
      clearInterval(waitTimer);
      $("#p-pay").disabled = false;
      $("#p-inline").textContent = "Still sure? If the caller is pressuring you, hang up first.";
      return;
    }
    $("#p-inline").textContent = `Paused for ${left} s. Use this time to hang up or call someone you trust.`;
  }, 250);
}
function logOverride() {
  (session || lastSession)?.log?.push({ t: Date.now() - (session || lastSession).start, event: "user_override_pay" });
}
function paid() {
  $("#p-form").hidden = true;
  $("#p-done").hidden = false;
  payAllowedAt = 0;
  clearInterval(waitTimer);
  $("#p-inline").hidden = true;
}

// ================================================================== SUMMARY
function renderSummary() {
  const s = lastSession;
  if (!s) { $("#s-verdict").textContent = "No call yet"; $("#s-graph").innerHTML = ""; return; }
  const h = s.history;
  const peak = Math.max(0, ...h.map((x) => x.risk));
  const lvl = peak >= s.pause ? "PAUSE" : peak >= s.warn ? "WARN" : "SAFE";
  $("#s-verdict").textContent = { SAFE: "No scam pattern detected", WARN: "Some warning signs", PAUSE: "Likely scam call" }[lvl];
  $("#s-meta").textContent = `${fmtTime(s.end - s.start)} · ${h.length} caller turns`;
  const W = 320, H = 140, P = 22;
  const x = (i) => P + (h.length < 2 ? 0 : (i * (W - 2 * P)) / (h.length - 1));
  const y = (r) => H - P - display(r) * (H - 2 * P);
  const pts = h.map((e, i) => `${x(i).toFixed(1)},${y(e.risk).toFixed(1)}`).join(" ");
  $("#s-graph").innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Risk over the call">
    <line class="ax" x1="${P}" y1="${H - P}" x2="${W - P}" y2="${H - P}"/>
    <line class="th-w" x1="${P}" y1="${y(s.warn)}" x2="${W - P}" y2="${y(s.warn)}"/><text x="${W - P}" y="${y(s.warn) - 4}" text-anchor="end">caution</text>
    <line class="th-p" x1="${P}" y1="${y(s.pause)}" x2="${W - P}" y2="${y(s.pause)}"/><text x="${W - P}" y="${y(s.pause) - 4}" text-anchor="end">high risk</text>
    <polyline class="ln" points="${pts}"/>
    ${h.map((e, i) => `<circle cx="${x(i)}" cy="${y(e.risk)}" r="3" fill="var(--brand)"><title>${esc(e.text)}</title></circle>`).join("")}
    <text x="${P}" y="${H - 6}">start</text><text x="${W - P}" y="${H - 6}" text-anchor="end">caller turns →</text></svg>`;
  $("#s-chips").innerHTML = s.seen.map((t) => `<span class="chip on">${esc(core.detector.labels[t])}</span>`).join("") || `<span class="small">No pressure tactics seen.</span>`;
  $("#s-download").onclick = () => {
    const lines = s.log.map((l) => JSON.stringify(l)).join("\n");
    const blob = new Blob([lines + "\n"], { type: "application/x-ndjson" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `bosc-session-${new Date(s.start).toISOString().slice(0, 19).replace(/:/g, "")}.jsonl`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };
}

// ------------------------------------------------------------------ boot
if (!window.isSecureContext) toast("This page needs HTTPS (or localhost) for the microphone.", 6000);
show(location.hash.slice(1) || "home");
// the Pay and Summary views depend on BOSC state; make sure it is initialised
if (["pay", "summary"].includes(location.hash.slice(1)) && !inited.has("bosc")) { inited.add("bosc"); initBosc(); }
