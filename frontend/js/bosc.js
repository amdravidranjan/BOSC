// Protected phone: answers the call, runs speech-to-text and the detector on-device, checks the voice,
// shows the pressure gauge, alerts the user and the guardian, and analyses the call when it ends.
import * as basicDetector from "./bosc-core.js";
import { UtteranceChunker, getAudioContext } from "./audio.js";
import { $, $$, toast, esc } from "./util.js";
import { settings, saveSettings, applyLargeText } from "./settings.js";
import { state, PEER_PREFIX, scriptsReady, LEVEL_WORD, LEVEL_RANK, logEvent } from "./state.js";
import { go } from "./router.js";
import { detectVictim, victimLevel, victimAdvice, VICTIM_CUES } from "./victim-cues.js";
import { initVoiceCheck, analyseClip, printOf, clearVoiceQueue } from "./voice-check.js";
import { detectClaim, evaluateVoice, trustedVoices, saveTrustedVoice, removeTrustedVoice } from "./trusted-voices.js";
import { buildReport, alertText } from "./report.js";
import { attachGuardianHost, guardianSend, guardianStatus, guardianNames, guardianCount } from "./guardian.js";

const ADVANCED_TIMEOUT_MS = 30000;
const VOICE_MIN_CLIPS = 3;            // clips before the synthetic-voice verdict is shown as a flag
const LAMP_ORDER = ["authority_claim", "threat", "urgency", "secrecy_isolation", "credential_request", "remote_access", "payment_instruction", "stay_on_line"];
const EXTRA_LAMPS = { impersonation: "Voice doesn't match", synthetic: "Computer-made voice" };

let worker = null, asrReady = false, asrBusy = false, voiceThreshold = 0.7, voiceModels = null;
let pending = [];                 // [{audio, side}] waiting for speech-to-text
let chunkers = {};                // side -> UtteranceChunker
let peer = null, incoming = null, activeCall = null, micStream = null;
let spokenLevels = new Set();
let wakeLock = null;
let scoreChain = Promise.resolve(), scoring = 0;
let pendingSwap = null;
let asrIds = new Map();

// ================================================================ init
export async function initBosc() {
  state.core = await basicDetector.loadScorer("model/");
  state.detectorKind = "basic";
  buildLamps();
  renderRisk();
  renderDetector("basic");
  $$("#b-side button").forEach((b) => (b.onclick = () => { settings.side = b.dataset.side; saveSettings(); renderSide(); }));
  renderSide();
  $$("#b-src button").forEach((b) => (b.onclick = () => {
    $$("#b-src button").forEach((x) => x.classList.toggle("on", x === b));
    for (const s of ["call", "mic", "script"]) $("#b-src-" + s).hidden = s !== b.dataset.src;
  }));
  scriptsReady.then(() => { $("#b-script").innerHTML = state.scripts.map((s) => `<option value="${esc(s.id)}">${esc(s.title)}</option>`).join(""); });
  $("#b-mic-start").onclick = startMic;
  $("#b-file").onchange = (e) => e.target.files[0] && startFile(e.target.files[0]);
  $("#b-script-start").onclick = startScript;
  $("#b-hang").onclick = () => endSession("hangup");
  $("#b-type-form").onsubmit = (e) => {
    e.preventDefault();
    const v = $("#b-type").value.trim();
    if (!v) return;
    if (!state.session) startSession("typed");
    addUtterance(v, $("#b-type-who").value, { typed: true });
    $("#b-type").value = "";
  };
  $("#in-accept").onclick = acceptCall;
  $("#in-reject").onclick = () => { $("#incoming").hidden = true; try { incoming && incoming.close(); } catch {} incoming = null; };
  $("#b-settings-btn").onclick = openSettings;
  $("#set-close").onclick = closeSettings;
  $("#set-voice-rec").onclick = recordTrustedVoice;
  $("#set-voice-file").onchange = (e) => e.target.files[0] && enrolFromFile(e.target.files[0]);
  updateTrusted();
  document.addEventListener("visibilitychange", onVisibility);
  chunkers = {
    caller: new UtteranceChunker({ onChunk: (a) => onChunk(a, "caller"), onLevel: (v, sp) => onLevel(v, sp, "caller"), minRms: +settings.sens }),
    me: new UtteranceChunker({ onChunk: (a) => onChunk(a, "me"), onLevel: (v, sp) => onLevel(v, sp, "me"), minRms: +settings.sens }),
  };
  setBState("Guarding");
  initPeer();
  loadAsr();
  loadAdvancedDetector();
  initVoiceCheck({
    onReady: (m) => { voiceThreshold = m.threshold || 0.7; },
    onError: (msg) => console.warn("voice worker:", msg),
    onResult: onVoiceResult,
  });
  fetch("model/voice-models.json").then((r) => r.json()).then((j) => (voiceModels = j)).catch(() => {});
  dropOldModelCache();
  maybeFirstRun();
}

// the earlier 90 MB wav2vec2 voice model is no longer used: free its space in the browser cache
async function dropOldModelCache() {
  try {
    for (const k of await caches.keys()) {
      const c = await caches.open(k);
      for (const req of await c.keys()) if (/deepfake-audio-wav2vec2/i.test(req.url)) await c.delete(req);
    }
  } catch {}
}

// ---------------------------------------------------------------- detector (basic -> advanced)
async function loadAdvancedDetector() {
  let mod;
  try { mod = await import("./detector-onnx.js"); } catch { renderDetector("basic"); return; }
  renderDetector("loading");
  try {
    const adv = await Promise.race([mod.loadScorer("model/"), new Promise((_, rej) => setTimeout(() => rej(new Error("timed out")), ADVANCED_TIMEOUT_MS))]);
    const swap = () => { state.core = adv; state.detectorKind = "advanced"; renderRisk(); renderDetector("advanced"); };
    if (state.session) { pendingSwap = swap; renderDetector("queued"); } else swap();
  } catch (err) {
    console.warn("Advanced detector not loaded; using basic.", err);
    renderDetector("basic");
  }
}
function renderDetector(kind) {
  $("#b-det-text").textContent = {
    advanced: "Scam detector: on-device AI model, ready",
    loading: "Scam detector: basic, loading the AI model…",
    queued: "Scam detector: basic now, AI model after this call",
  }[kind] || "Scam detector: basic";
}

// ---------------------------------------------------------------- first-run setup
function maybeFirstRun() {
  if (settings.setupDone || settings.tnum) return;
  let step = 1;
  const render = () => {
    $("#setup-step").textContent = `Step ${step} of 2`;
    $("#setup-1").hidden = step !== 1; $("#setup-2").hidden = step !== 2;
    $("#setup-next").textContent = step === 2 ? "Finish" : "Next";
  };
  $("#setup-tname").value = settings.tname; $("#setup-tnum").value = settings.tnum; $("#setup-avg").value = settings.avg;
  $("#setup-skip").onclick = () => { settings.setupDone = true; saveSettings(); $("#setup").hidden = true; };
  $("#setup-next").onclick = () => {
    if (step === 1) { settings.tname = $("#setup-tname").value.trim(); settings.tnum = $("#setup-tnum").value.trim(); step = 2; render(); return; }
    settings.avg = Math.max(1, +$("#setup-avg").value || 1500);
    settings.setupDone = true; saveSettings(); updateTrusted();
    $("#setup").hidden = true;
    if ("Notification" in window && Notification.permission === "default") Notification.requestPermission().catch(() => {});
  };
  render();
  $("#setup").hidden = false;
}

// ---------------------------------------------------------------- settings
function openSettings() {
  $("#set-model").value = settings.model; $("#set-wasm").checked = !!settings.forceWasm;
  $("#set-myname").value = settings.myname; $("#set-mynum").value = settings.mynum || "";
  $("#set-tname").value = settings.tname; $("#set-tnum").value = settings.tnum;
  $("#set-avg").value = settings.avg; $("#set-sens").value = String(settings.sens);
  $("#set-speak").checked = !!settings.speak; $("#set-sound").checked = !!settings.sound;
  $("#set-large").checked = !!settings.large; $("#set-voice").checked = !!settings.voiceCheck;
  renderTrustedVoices();
  $("#settings").hidden = false;
}
function closeSettings() {
  const prevModel = settings.model, prevWasm = settings.forceWasm;
  settings.model = $("#set-model").value; settings.forceWasm = $("#set-wasm").checked;
  settings.myname = $("#set-myname").value.trim().slice(0, 30); settings.mynum = $("#set-mynum").value.trim();
  settings.tname = $("#set-tname").value.trim(); settings.tnum = $("#set-tnum").value.trim();
  settings.avg = Math.max(1, +$("#set-avg").value || 1500); settings.sens = +$("#set-sens").value;
  settings.speak = $("#set-speak").checked; settings.sound = $("#set-sound").checked;
  settings.large = $("#set-large").checked; settings.voiceCheck = $("#set-voice").checked;
  settings.setupDone = true;
  saveSettings(); applyLargeText();
  for (const c of Object.values(chunkers)) c.minRms = settings.sens;
  updateTrusted();
  $("#settings").hidden = true;
  if (prevModel !== settings.model || prevWasm !== settings.forceWasm) loadAsr();
}
function updateTrusted() {
  const href = settings.tnum ? "tel:" + settings.tnum.replace(/[^\d+]/g, "") : "tel:";
  const name = settings.tname || "family";
  for (const id of ["#b-trusted", "#pause-family"]) { const a = $(id); if (a) { a.href = href; a.textContent = `Call ${name}`; } }
}
function renderTrustedVoices() {
  const v = trustedVoices();
  $("#set-voices").innerHTML = v.length
    ? v.map((x) => `<li><span>${esc(x.name)}${x.relation ? ` (${esc(x.relation)})` : ""}</span><button type="button" class="linkish" data-del="${esc(x.name)}">Remove</button></li>`).join("")
    : `<li class="quiet">No voices saved yet. Save a family member's voice so BOSC can tell when a caller only pretends to be them.</li>`;
  $$("#set-voices [data-del]").forEach((b) => (b.onclick = () => { removeTrustedVoice(b.dataset.del); renderTrustedVoices(); }));
}
async function recordTrustedVoice() {
  const name = $("#set-voice-name").value.trim();
  if (!name) return toast("Type the person's name first.");
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } }); }
  catch { return toast("Microphone permission is needed to record."); }
  const ctx = getAudioContext(); ctx.resume();
  const btn = $("#set-voice-rec");
  const chunks = [];
  const rec = new UtteranceChunker({ onChunk: (a) => chunks.push(a), minRms: +settings.sens, silenceMs: 400, maxMs: 4000, minMs: 300 });
  await rec.attach(ctx, ctx.createMediaStreamSource(stream));
  let left = 8;
  btn.disabled = true;
  const tick = setInterval(() => { btn.textContent = `Recording… ${left} s (ask ${name} to talk normally)`; if (--left < 0) clearInterval(tick); }, 1000);
  btn.textContent = `Recording… ${left} s (ask ${name} to talk normally)`;
  await new Promise((r) => setTimeout(r, 9000));
  rec.flush(); rec.detach(); stream.getTracks().forEach((t) => t.stop());
  btn.textContent = "Record 8 seconds"; btn.disabled = false;
  await enrol(name, chunks);
}
async function enrolFromFile(file) {
  const name = $("#set-voice-name").value.trim();
  $("#set-voice-file").value = "";
  if (!name) return toast("Type the person's name first.");
  const ctx = getAudioContext();
  let buf;
  try { buf = await ctx.decodeAudioData(await file.arrayBuffer()); } catch { return toast("Couldn't read that audio file."); }
  const n = Math.min(buf.length, buf.sampleRate * 20);
  const off = new OfflineAudioContext(1, Math.ceil(n * 16000 / buf.sampleRate), 16000);
  const src = off.createBufferSource(); src.buffer = buf; src.connect(off.destination); src.start();
  const x = (await off.startRendering()).getChannelData(0);
  await enrol(name, [x]);
}
async function enrol(name, chunks) {
  const total = chunks.reduce((s, a) => s + a.length, 0);
  if (total < 16000 * 2) return toast("Too little speech was heard. Try again in a quiet place.");
  const audio = new Float32Array(total); let o = 0;
  for (const a of chunks) { audio.set(a, o); o += a.length; }
  try {
    const r = await printOf(audio);
    if (!r.print) return toast("Couldn't get a clear voice-print. Try again with more talking.");
    saveTrustedVoice(name, $("#set-voice-rel").value.trim(), r.print);
    $("#set-voice-name").value = ""; $("#set-voice-rel").value = "";
    renderTrustedVoices();
    toast(`${name}'s voice is saved on this phone.`);
  } catch (e) { toast("Voice-print failed: " + e.message); }
}

function setBState(t, live = false) { const p = $("#b-state"); p.lastChild.textContent = t; p.classList.toggle("live", live); }

// ---------------------------------------------------------------- speech-to-text
function loadAsr() {
  if (worker) worker.terminate();
  asrReady = false; asrBusy = false;
  worker = new Worker(new URL("./asr-worker.js", import.meta.url), { type: "module" });
  $("#b-asr-text").textContent = "Loading the speech model (downloads once, then works offline)…";
  $("#b-asr-prog").hidden = false;
  worker.onmessage = (e) => {
    const m = e.data;
    if (m.type === "progress") $("#b-asr-prog i").style.width = `${Math.round((100 * m.loaded) / m.total)}%`;
    else if (m.type === "ready") {
      asrReady = true;
      $("#b-asr-prog").hidden = true;
      $("#b-asr-text").textContent = `Speech-to-text: ${settings.model.split("/")[1]} on this device (${m.device === "webgpu" ? "GPU" : "CPU"}), ready`;
      pump();
    } else if (m.type === "error") {
      $("#b-asr-prog").hidden = true;
      $("#b-asr-text").textContent = "Speech model failed to load: " + m.message;
    } else if (m.type === "text") {
      asrBusy = false;
      const side = asrIds.get(m.id) || "caller"; asrIds.delete(m.id);
      if (m.text && state.session) addUtterance(m.text, side, { asrMs: m.ms, dur: m.dur });
      setListen(state.session ? "listening" : "idle");
      pump();
    }
  };
  worker.postMessage({ type: "load", model: settings.model, forceWasm: settings.forceWasm });
}
function onChunk(audio, side) {
  const s = state.session;
  if (!s) return;
  if (side === "caller" && settings.voiceCheck && audio.length >= 16000 * 1.5) analyseClip(audio.slice(), "caller");
  pending.push({ audio, side });
  let total = pending.reduce((n, p) => n + p.audio.length, 0);
  while (total > 16000 * 30 && pending.length > 1) total -= pending.shift().audio.length;
  pump();
}
function pump() {
  if (!asrReady || asrBusy || !pending.length) return;
  const side = pending[0].side;
  const take = [];
  let n = 0;
  while (pending.length && pending[0].side === side && n + pending[0].audio.length <= 16000 * 14) { n += pending[0].audio.length; take.push(pending.shift().audio); }
  if (!take.length) take.push(pending.shift().audio);
  const audio = new Float32Array(take.reduce((s, a) => s + a.length, 0));
  let o = 0;
  for (const a of take) { audio.set(a, o); o += a.length; }
  asrBusy = true;
  setListen("transcribing…");
  const id = Date.now() + Math.random();
  asrIds.set(id, side);
  worker.postMessage({ type: "transcribe", audio, id }, [audio.buffer]);
}
let levelT = 0;
function onLevel(v, speech, side) {
  const now = performance.now();
  if (now - levelT < 60) return;
  levelT = now;
  $("#b-level").style.width = `${Math.round(v * 100)}%`;
  if (!asrBusy && state.session && state.session.source !== "script") setListen(speech ? (side === "me" ? "hearing you" : "hearing the caller") : "listening");
}
function setListen(t) { $("#b-listen").textContent = t; }

// ---------------------------------------------------------------- screen on
async function requestWake() {
  try { wakeLock = await navigator.wakeLock?.request("screen"); $("#b-keepon").hidden = true; }
  catch { $("#b-keepon").hidden = false; }
}
function releaseWake() { try { wakeLock && wakeLock.release(); } catch {} wakeLock = null; $("#b-keepon").hidden = true; }
function onVisibility() {
  if (!state.session) return;
  if (document.visibilityState === "visible") { requestWake(); logEvent("screen_on"); }
  else logEvent("screen_off");
}

// ---------------------------------------------------------------- sessions
function startSession(source, stop = () => {}) {
  if (state.session) endSession("replaced", true);
  state.core.scorer.reset();
  spokenLevels = new Set();
  const side = (source === "call") ? settings.side : (source === "mic") ? (settings.side === "me" ? "me" : "caller") : "caller";
  state.session = { source, side, start: Date.now(), stop, log: [], victimSeen: new Set(), voice: { n: 0, scores: [], mean: 0, flagged: false },
    prints: [], claim: null, imp: null, alerts: {} };
  $("#b-transcript").innerHTML = `<div class="empty">${side === "me" ? "Your words will appear here. BOSC warns you if you start giving away a code or money." : "What is said on the call appears here."}</div>`;
  $("#b-idle").hidden = true;
  $("#b-voice").hidden = true; $("#b-imp").hidden = true;
  renderRisk();
  const names = { call: "On a call", mic: "Listening", file: "Replaying a recording", script: "Demo script", typed: "Typed test" };
  setBState(names[source] || "Active", true);
  setListen(source === "script" || source === "typed" ? "text input" : "listening");
  $("#b-privacy").textContent = source === "script" || source === "typed"
    ? "Text-only test. No audio is used."
    : "Listening now. Audio is analysed on this phone and discarded. Nothing is recorded or sent anywhere.";
  try { getAudioContext().resume(); } catch {}
  requestWake();
  guardianSend({ type: "call", state: "started" });
  pushGuardianStatus();
}

function endSession(reason, silent = false) {
  if (!state.session) return;
  const s = state.session;
  state.session = null;
  try { s.stop(); } catch {}
  for (const c of Object.values(chunkers)) c.detach();
  pending = []; asrIds.clear();
  clearVoiceQueue();
  releaseWake();
  const sc = state.core.scorer;
  const snap = { ...s, end: Date.now(), history: sc.history.slice(), seen: [...sc.seen], finalScore: sc.score(), finalLevel: sc.level(),
    warn: sc.warn, pause: sc.pause, reason, detector: state.detectorKind, victimSeen: [...s.victimSeen] };
  snap.report = buildReport(snap);
  snap.alerts = sendAlerts(snap.report);
  state.lastSession = snap;
  $("#b-idle").hidden = false;
  setBState("Guarding");
  setListen("idle");
  $("#b-level").style.width = "0";
  $("#b-privacy").textContent = "Listening only while a call is active. Audio is analysed on this phone and discarded.";
  guardianSend({ type: "call", state: "ended" });
  if (pendingSwap) { const sw = pendingSwap; pendingSwap = null; sw(); }
  if (!silent && (snap.history.length || snap.log.length)) go("summary");
}

// after the call: alert this phone and the guardian
function sendAlerts(report) {
  const out = { device: false, guardianSent: false, guardianNames: [], guardianAck: null };
  if (report.level !== "SAFE") {
    out.device = true;
    try { navigator.vibrate?.(report.level === "PAUSE" ? [500, 150, 500, 150, 500] : [300]); } catch {}
    if ("Notification" in window && Notification.permission === "granted") {
      try { new Notification(report.verdict, { body: report.summary, tag: "bosc-report" }); } catch {}
    }
    if (settings.speak && "speechSynthesis" in window) {
      try { speechSynthesis.cancel(); const u = new SpeechSynthesisUtterance(`${report.verdict}. ${report.summary} ${report.actions[0] || ""}`); u.lang = "en-IN"; u.rate = 0.95; speechSynthesis.speak(u); } catch {}
    }
  }
  if (guardianCount()) {
    guardianSend({ type: "report", report });
    out.guardianSent = true;
    out.guardianNames = guardianNames();
  }
  return out;
}

// ---------------------------------------------------------------- WebRTC call (this phone answers)
function newCode() { return String(Math.floor(1000 + Math.random() * 9000)); }
function callerName(call) {
  const n = call && call.metadata && call.metadata.name;
  return typeof n === "string" ? n.trim().slice(0, 40) : "";
}
function initPeer() {
  if (typeof Peer === "undefined") { $("#b-peer-status").textContent = "Call library failed to load. Check the internet connection."; $("#b-code").textContent = "----"; return; }
  const code = settings.code || newCode();
  settings.code = code; saveSettings();
  $("#b-code").textContent = code;
  peer = new Peer(PEER_PREFIX + code, { debug: 1 });
  peer.on("open", () => { $("#b-peer-status").textContent = "Ready. Tell the caller, or your guardian, this code."; });
  peer.on("disconnected", () => { $("#b-peer-status").textContent = "Reconnecting…"; try { peer.reconnect(); } catch {} });
  peer.on("error", (err) => {
    if (err.type === "unavailable-id") { settings.code = newCode(); saveSettings(); setTimeout(initPeer, 300); return; }
    $("#b-peer-status").textContent = "Connection error: " + err.type;
  });
  peer.on("call", (call) => {
    if (activeCall || incoming) { try { call.close(); } catch {} return; }
    incoming = call;
    const name = callerName(call);
    $("#in-from").textContent = name || "Unknown number";
    $("#in-note").hidden = !name;
    $("#incoming").hidden = false;
    try { navigator.vibrate?.([300, 200, 300]); } catch {}
    call.on("close", () => { if (incoming === call) { incoming = null; $("#incoming").hidden = true; } });
  });
  attachGuardianHost(peer, {
    myName: () => settings.myname || "", myPhone: () => settings.mynum || "",
    onChange: (names) => { $("#b-guard-text").textContent = names.length ? `Guardian watching: ${names.join(", ")}` : "No guardian phone connected"; },
    onAck: (name) => { if (state.lastSession && state.lastSession.alerts) state.lastSession.alerts.guardianAck = name; toast(`${name} has seen the alert.`); },
  });
}
async function acceptCall() {
  $("#incoming").hidden = true;
  const call = incoming;
  incoming = null;
  if (!call) return;
  const ctx = getAudioContext();
  ctx.resume();
  try { micStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } }); }
  catch { toast("Microphone permission is needed to talk. Answering listen-only."); micStream = null; }
  activeCall = call;
  call.answer(micStream || undefined);
  startSession("call", () => {
    try { call.close(); } catch {}
    if (micStream) micStream.getTracks().forEach((t) => t.stop());
    micStream = null; activeCall = null;
    $("#b-remote").srcObject = null;
  });
  const name = callerName(call);
  if (name) logEvent("caller_name", { name });
  const side = state.session.side;
  if ((side === "me" || side === "both") && micStream) chunkers.me.attach(ctx, ctx.createMediaStreamSource(micStream));
  else if (side === "me") toast("Android mode needs the microphone to hear your replies.");
  let attached = false;
  call.on("stream", (remote) => {
    if (attached) return;
    attached = true;
    const a = $("#b-remote");           // Chrome only feeds a remote stream to Web Audio when a media element plays it too
    a.srcObject = remote;
    a.play().catch(() => {});
    if (side !== "me") chunkers.caller.attach(ctx, ctx.createMediaStreamSource(remote));
  });
  call.on("close", () => endSession("remote-hangup"));
  call.on("error", () => endSession("error"));
}

// ---------------------------------------------------------------- local microphone / file / script
async function startMic() {
  const ctx = getAudioContext();
  ctx.resume();
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: true, autoGainControl: true } }); }
  catch { return toast("Microphone permission was denied."); }
  startSession("mic", () => stream.getTracks().forEach((t) => t.stop()));
  const side = state.session.side === "me" ? "me" : "caller";
  chunkers[side].attach(ctx, ctx.createMediaStreamSource(stream));
}
async function startFile(file) {
  const ctx = getAudioContext();
  ctx.resume();
  let buf;
  try { buf = await ctx.decodeAudioData(await file.arrayBuffer()); } catch { return toast("Couldn't read that audio file."); }
  $("#b-file").value = "";
  const src = ctx.createBufferSource();
  src.buffer = buf;
  src.connect(ctx.destination);
  startSession("file", () => { try { src.stop(); } catch {} });
  const sess = state.session;
  src.onended = () => {
    chunkers.caller.flush();
    const wait = () => (asrBusy || pending.length || scoring) ? setTimeout(wait, 300) : state.session === sess && endSession("file-ended");
    setTimeout(wait, 500);
  };
  await chunkers.caller.attach(ctx, src);
  if (state.session !== sess) return;
  src.start();
}
function startScript() {
  const s = state.scripts.find((x) => x.id === $("#b-script").value);
  if (!s) return;
  let i = 0, timer = null;
  startSession("script", () => clearInterval(timer));
  const sess = state.session;
  const step = () => {
    if (state.session !== sess) return clearInterval(timer);
    if (i >= s.lines.length) { clearInterval(timer); setTimeout(() => state.session === sess && endSession("script-ended"), 2500); return; }
    addUtterance(s.lines[i++], "caller", { script: s.id });
  };
  step();
  timer = setInterval(step, 3200);
}

// ---------------------------------------------------------------- risk UI
function buildLamps() {
  const labels = state.core.detector.labels;
  $("#b-lamps").innerHTML = LAMP_ORDER.map((t) => `<li data-t="${t}"><i></i><span>${esc(labels[t] || t)}</span></li>`).join("")
    + Object.entries(EXTRA_LAMPS).map(([k, l]) => `<li data-t="${k}" hidden><i></i><span>${esc(l)}</span></li>`).join("");
}
export function advice(seen, level) {
  const has = (t) => seen.has(t);
  let tip;
  if (has("remote_access")) tip = "Never install an app or share your screen for a caller.";
  else if (has("credential_request")) tip = "Never tell a caller an OTP or PIN, even if they say they are the bank.";
  else if (has("payment_instruction")) tip = "No real officer or bank asks you to move money on a call.";
  else if (has("secrecy_isolation")) tip = "A caller who wants secrecy is a warning sign. Tell your family.";
  else if (has("threat") || has("urgency")) tip = "Pressure to act fast is a warning sign. You can hang up and call the official number.";
  else tip = "Don't share codes or send money because of this call.";
  if (level === "PAUSE") return "This call follows a known scam pattern. " + tip;
  if (level === "WARN") return tip;
  return seen.size ? "Listening. Nothing alarming yet." : "No pressure heard yet.";
}
let lastLevelRendered = "SAFE";
export function renderRisk() {
  const sc = state.core.scorer;
  const lvl = sc.level();
  const d = sc.score();
  const risk = $("#b-risk");
  risk.dataset.level = lvl;
  if (lvl !== lastLevelRendered) { risk.classList.remove("changed"); void risk.offsetWidth; risk.classList.add("changed"); lastLevelRendered = lvl; }
  $("#b-word").textContent = LEVEL_WORD[lvl];
  $("#b-score").textContent = `${Math.round(d * 100)}`;
  $("#b-needle").style.transform = `rotate(${-90 + 180 * d}deg)`;
  const s = state.session;
  const vs = s && s.victimSeen;
  $("#b-warning").textContent = (vs && vs.size && lvl !== "SAFE") ? victimAdvice(vs) : advice(sc.seen, lvl);
  $$("#b-lamps li").forEach((li) => {
    const t = li.dataset.t;
    const on = sc.seen.has(t) || (t === "impersonation" && s && s.imp && s.imp.verdict === "mismatch") || (t === "synthetic" && s && s.voice.flagged);
    li.classList.toggle("on", on);
    if (EXTRA_LAMPS[t]) li.hidden = !on;
  });
  pushGuardianStatus();
}
function pushGuardianStatus() {
  if (!guardianCount()) return;
  const sc = state.core.scorer;
  const labels = state.core.detector.labels;
  const s = state.session;
  const voice = [];
  if (s && s.voice.flagged) voice.push("voice may be computer-generated");
  if (s && s.imp && s.imp.verdict === "mismatch") voice.push(`voice does not match ${s.imp.name}`);
  guardianStatus({ level: sc.level(), score: sc.score(), tactics: [...sc.seen].map((t) => labels[t] || t), voice: voice.join("; "), inCall: !!s });
}
function highlight(text, tactics) {
  const spans = Object.values(tactics || {}).flat().sort((a, b) => a[0] - b[0]);
  let out = "", i = 0;
  for (const [s, e] of spans) { if (s < i) continue; out += esc(text.slice(i, s)) + "<mark>" + esc(text.slice(s, e)) + "</mark>"; i = e; }
  return out + esc(text.slice(i));
}
function addRow(side, html, level) {
  const box = $("#b-transcript");
  box.querySelector(".empty")?.remove();
  const el = document.createElement("div");
  el.className = "utt " + side;
  el.dataset.level = level;
  el.innerHTML = html;
  box.appendChild(el);
  box.scrollTop = box.scrollHeight;
}
function maybeEscalate(prev, lvl) {
  if (LEVEL_RANK[lvl] > LEVEL_RANK[prev] && !spokenLevels.has(lvl)) { spokenLevels.add(lvl); escalate(lvl); }
}

// Every line goes through one queue, so results apply in order even when push() is async.
export function addUtterance(text, side = "caller", meta = {}) {
  if (!state.session) startSession("typed");
  const sess = state.session;
  scoring++;
  scoreChain = scoreChain
    .then(() => (side === "me" ? scoreVictim(sess, text, meta) : scoreCaller(sess, text, meta)))
    .catch((err) => console.error("Scoring failed:", err))
    .then(() => { scoring--; });
}
async function scoreCaller(sess, text, meta) {
  if (state.session !== sess) return;
  const sc = state.core.scorer;
  const prev = sc.level();
  const r = await sc.push(text);
  if (!r || state.session !== sess) return;
  const tactics = r.tactics || {};
  sess.log.push({ t: Date.now() - sess.start, side: "caller", text, p: +r.p.toFixed(4), risk: +r.risk.toFixed(4), score: +r.score.toFixed(3), level: r.level, tactics: Object.keys(tactics), ...meta });
  const labels = state.core.detector.labels;
  const tl = Object.keys(tactics).filter((t) => t !== "authority_claim" || r.level !== "SAFE").map((t) => labels[t] || t);
  const lat = meta.asrMs ? `, heard in ${(meta.asrMs / 1000).toFixed(1)} s` : "";
  addRow("caller", `<span class="who">Caller</span>${highlight(text, tactics)}<div class="meta">${tl.length ? esc(tl.join(", ")) + ". " : ""}Pressure ${Math.round(r.score * 100)}${lat}</div>`, r.level);
  // impersonation: did the caller claim to be someone we know?
  const claim = detectClaim(text);
  if (claim && !sess.claim) { sess.claim = { ...claim, text: claim.relation ? `your ${claim.relation}` : claim.name }; logEvent("caller_claim", claim); evaluateImpersonation(sess); }
  renderRisk();
  maybeEscalate(prev, sc.level());
}
function scoreVictim(sess, text, meta) {
  const sc = state.core.scorer;
  const prev = sc.level();
  const found = detectVictim(text);
  for (const f of found) sess.victimSeen.add(f);
  const r = sc.raiseTo(victimLevel(sess.victimSeen), text, "me");
  sess.log.push({ t: Date.now() - sess.start, side: "me", text, score: +r.score.toFixed(3), level: r.level, cues: found, ...meta });
  const cueText = found.map((f) => VICTIM_CUES[f].label).join(", ");
  const lat = meta.asrMs ? `, heard in ${(meta.asrMs / 1000).toFixed(1)} s` : "";
  addRow("me", `<span class="who">You</span>${found.length ? "<mark>" + esc(text) + "</mark>" : esc(text)}<div class="meta">${cueText ? esc(cueText) + ". " : ""}Pressure ${Math.round(r.score * 100)}${lat}</div>`, r.level);
  renderRisk();
  maybeEscalate(prev, sc.level());
}

function renderSide() {
  $$("#b-side button").forEach((x) => x.classList.toggle("on", x.dataset.side === settings.side));
  $("#b-side-note").textContent = {
    caller: "Listens to the caller only (our web call, or a call on speaker next to this phone).",
    both: "Listens to both of you: the caller's pressure tactics and your own replies (a code read out, money agreed).",
    me: "Android mode. A real Android app can't hear the other side of a phone call, only this phone's microphone, so BOSC listens to your voice and warns if you start giving away a code, money or your screen.",
  }[settings.side];
}

// ---------------------------------------------------------------- voice checks (synthetic voice, impersonation)
function onVoiceResult(m) {
  const s = state.session;
  if (!s || m.who !== "caller") return;
  if (typeof m.synth === "number") {
    const v = s.voice;
    v.n += 1; v.scores.push(m.synth);
    if (v.scores.length > 8) v.scores.shift();
    v.mean = v.scores.reduce((a, b) => a + b, 0) / v.scores.length;
    s.log.push({ t: Date.now() - s.start, event: "voice_check", synth: +m.synth.toFixed(3), ms: m.ms });
    const el = $("#b-voice");
    el.hidden = false;
    const pct = Math.round(v.mean * 100);
    if (v.n >= VOICE_MIN_CLIPS && v.mean >= voiceThreshold) {
      el.dataset.flag = "1";
      el.textContent = `This voice sounds computer-generated (AI-voice score ${pct}/100). Scammers use cloned voices.`;
      if (!v.flagged) {
        v.flagged = true;
        const prev = state.core.scorer.level();
        state.core.scorer.raiseTo("WARN", "[voice check: computer-generated voice]", "voice");
        logEvent("voice_synthetic", { mean: +v.mean.toFixed(3), clips: v.n });
        renderRisk();
        maybeEscalate(prev, state.core.scorer.level());
      }
    } else if (!v.flagged) {
      el.dataset.flag = "";
      el.textContent = v.n < VOICE_MIN_CLIPS ? `Checking the voice… (${v.n}/${VOICE_MIN_CLIPS} clips)` : `Voice sounds like a real person (AI-voice score ${pct}/100).`;
    }
  }
  if (m.print) {
    s.prints.push(m.print);
    if (s.prints.length > 12) s.prints.shift();
    evaluateImpersonation(s);
  }
}
function evaluateImpersonation(s) {
  if (!trustedVoices().length || s.prints.length < 2) return;
  const ev = evaluateVoice(s.prints, s.claim, voiceModels);
  const el = $("#b-imp");
  if (ev.claimed) {
    const pct = Math.round(ev.claimed.sim * 100);
    const before = s.imp && s.imp.verdict;
    s.imp = { name: ev.claimed.name, sim: ev.claimed.sim, verdict: ev.verdict, claimText: s.claim.text };
    el.hidden = false;
    el.dataset.flag = ev.verdict === "mismatch" ? "1" : ev.verdict === "match" ? "ok" : "";
    el.textContent = ev.verdict === "mismatch"
      ? `The caller says they are ${s.claim.text}, but the voice does not match ${ev.claimed.name}'s saved voice (${pct}% similar).`
      : ev.verdict === "match" ? `Voice matches ${ev.claimed.name}'s saved voice (${pct}% similar).`
      : `Caller says they are ${s.claim.text}. Checking against ${ev.claimed.name}'s saved voice… (${pct}% so far)`;
    if (ev.verdict === "mismatch" && before !== "mismatch") {
      const prev = state.core.scorer.level();
      state.core.scorer.raiseTo("PAUSE", `[voice does not match ${ev.claimed.name}]`, "voice");
      logEvent("impersonation", { name: ev.claimed.name, sim: +ev.claimed.sim.toFixed(3) });
      renderRisk();
      maybeEscalate(prev, state.core.scorer.level());
    } else renderRisk();
  } else if (ev.best && ev.verdict === "known") {
    el.hidden = false; el.dataset.flag = "ok";
    el.textContent = `This sounds like ${ev.best.name} (saved voice, ${Math.round(ev.best.sim * 100)}% similar).`;
  }
}

// ---------------------------------------------------------------- alerts (haptics, chime, voice)
function escalate(lvl) {
  try { navigator.vibrate?.(lvl === "PAUSE" ? [600, 200, 600, 200, 600] : [200, 120, 200]); } catch {}
  if (settings.sound) chime(lvl);
  if (settings.speak && "speechSynthesis" in window) {
    setTimeout(() => {
      try {
        speechSynthesis.cancel();
        const u = new SpeechSynthesisUtterance(lvl === "PAUSE" ? "Careful. This call looks like a scam. Do not share a code or send money." : "Be careful with this call.");
        u.lang = "en-IN"; u.rate = 0.95;
        speechSynthesis.speak(u);
      } catch {}
    }, settings.sound ? 700 : 0);
  }
}
function chime(lvl) {
  try {
    const ctx = getAudioContext();
    if (ctx.state === "suspended") ctx.resume();
    const notes = lvl === "PAUSE" ? [[660, 0], [520, 0.22], [392, 0.44]] : [[523, 0], [659, 0.18]];
    for (const [f, at] of notes) {
      const o = ctx.createOscillator(), g = ctx.createGain();
      o.type = "sine"; o.frequency.value = f;
      g.gain.setValueAtTime(0.0001, ctx.currentTime + at);
      g.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + at + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + at + 0.2);
      o.connect(g).connect(ctx.destination);
      o.start(ctx.currentTime + at); o.stop(ctx.currentTime + at + 0.22);
    }
  } catch {}
}

export { alertText };
