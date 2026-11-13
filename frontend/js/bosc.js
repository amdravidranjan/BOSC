// BOSC (Phone B): answers the call, runs speech-to-text and the detector, shows risk.
import * as basicDetector from "./bosc-core.js";
import { UtteranceChunker, getAudioContext } from "./audio.js";
import { $, $$, toast, esc } from "./util.js";
import { settings, saveSettings, applyLargeText } from "./settings.js";
import { state, PEER_PREFIX, scriptsReady, LEVEL_WORD, display, logEvent } from "./state.js";
import { go } from "./router.js";
import { detectVictim, victimLevel, victimAdvice, VICTIM_CUES } from "./victim-cues.js";
import { initVoiceCheck, loadVoiceCheck, checkVoice, clearVoiceQueue } from "./voice-check.js";

// libMax (similarity to a known scam script) above this shows "matches a known scam script".
// Placeholder until Backend gives the real threshold.
const LIB_MATCH = 0.8;
const ADVANCED_TIMEOUT_MS = 30000;
// Voice check: mean "fake" score over at least VOICE_MIN_CLIPS clips at or above this -> "may be AI-generated".
const VOICE_FAKE = 0.7, VOICE_MIN_CLIPS = 2;
// The current model is NOT reliable (tested: Windows TTS scored 9/100 "real", a real 1961 JFK recording 79/100 "fake";
// it was trained on replayed speech, ASVspoof 2021 PA). So it is display-only and never raises the risk.
// Set to true only after a model passes a real test set (human vs cloned voices).
const VOICE_RAISES_RISK = false;

let worker = null, asrReady = false, asrBusy = false;
let pending = [];          // audio chunks waiting for ASR
let chunker = null;
let peer = null, incoming = null, activeCall = null, micStream = null;
let spokenLevels = new Set();
let wakeLock = null;
let scoreChain = Promise.resolve(), scoring = 0;  // in-order scoring queue (push may be async)
let pendingSwap = null;    // advanced detector arrived mid-call: switch after the call

export async function initBosc() {
  state.core = await basicDetector.loadScorer("model/");
  state.detectorKind = "basic";
  buildMeter();
  buildChips();
  renderRisk();
  renderDetector("basic");
  // source tabs
  $$("#b-src button").forEach((b) => (b.onclick = () => {
    $$("#b-src button").forEach((x) => x.classList.toggle("on", x === b));
    for (const s of ["call", "mic", "script"]) $("#b-src-" + s).hidden = s !== b.dataset.src;
  }));
  scriptsReady.then(() => {
    $("#b-script").innerHTML = state.scripts.map((s) => `<option value="${esc(s.id)}">${esc(s.title)}</option>`).join("");
  });
  // whose voice we listen to: the caller's (web call / speaker) or the victim's own (Android mode)
  $$("#b-side button").forEach((b) => (b.onclick = () => {
    settings.side = b.dataset.side;
    saveSettings();
    renderSide();
  }));
  renderSide();
  initVoiceCheck({ onResult: onVoiceResult, onStatus: onVoiceStatus });
  $("#b-mic-start").onclick = startMic;
  $("#b-file").onchange = (e) => e.target.files[0] && startFile(e.target.files[0]);
  $("#b-script-start").onclick = startScript;
  $("#b-hang").onclick = () => endSession("hangup");
  $("#b-type-form").onsubmit = (e) => {
    e.preventDefault();
    const v = $("#b-type").value.trim();
    if (!v) return;
    if (!state.session) startSession("typed");
    addUtterance(v, { typed: true });
    $("#b-type").value = "";
  };
  $("#in-accept").onclick = acceptCall;
  $("#in-reject").onclick = () => { $("#incoming").hidden = true; try { incoming && incoming.close(); } catch {} incoming = null; };
  $("#b-settings-btn").onclick = openSettings;
  $("#set-close").onclick = closeSettings;
  updateTrusted();
  document.addEventListener("visibilitychange", onVisibility);
  chunker = new UtteranceChunker({ onChunk, onLevel, minRms: +settings.sens });
  setBState("Guarding");
  initPeer();
  loadAsr();
  loadAdvancedDetector();
  maybeFirstRun();
}

// ---------------------------------------------------------------- detector (basic -> advanced)
async function loadAdvancedDetector() {
  let mod;
  try { mod = await import("./detector-onnx.js"); }
  catch { renderDetector("basic"); return; }   // not delivered by Backend yet
  renderDetector("loading");
  try {
    const adv = await Promise.race([
      mod.loadScorer("model/"),
      new Promise((_, rej) => setTimeout(() => rej(new Error("timed out")), ADVANCED_TIMEOUT_MS)),
    ]);
    const swap = () => {
      state.core = adv;
      state.detectorKind = "advanced";
      buildChips();
      renderRisk();
      renderDetector("advanced");
    };
    if (state.session) { pendingSwap = swap; renderDetector("queued"); } else swap();
  } catch (err) {
    console.warn("Advanced detector failed; using basic.", err);
    renderDetector("basic", true);
  }
}
function renderDetector(kind, failed = false) {
  $("#b-det-text").textContent = {
    advanced: "Detector: advanced (on-device AI model)",
    loading: "Detector: basic · loading advanced…",
    queued: "Detector: basic · advanced ready after this call",
  }[kind] || `Detector: basic${failed ? " (advanced failed to load)" : ""}`;
}

// ---------------------------------------------------------------- first-run setup
function maybeFirstRun() {
  if (settings.setupDone || settings.tnum) return;
  let step = 1;
  const render = () => {
    $("#setup-1").hidden = step !== 1;
    $("#setup-2").hidden = step !== 2;
    $("#setup-step").textContent = `Step ${step} of 2`;
    $("#setup-next").textContent = step === 2 ? "Done" : "Next";
  };
  $("#setup-tname").value = settings.tname;
  $("#setup-tnum").value = settings.tnum;
  $("#setup-avg").value = settings.avg;
  const finish = () => {
    settings.setupDone = true;
    saveSettings();
    updateTrusted();
    $("#setup").hidden = true;
  };
  $("#setup-skip").onclick = finish;
  $("#setup-next").onclick = () => {
    if (step === 1) {
      const num = $("#setup-tnum").value.trim();
      if (num && num.replace(/\D/g, "").length < 6) return toast("That number looks too short. Check it, or leave it empty.");
      settings.tname = $("#setup-tname").value.trim();
      settings.tnum = num;
      step = 2;
      render();
      return;
    }
    settings.avg = Math.max(1, +$("#setup-avg").value.replace(/\D/g, "") || 1500);
    finish();
  };
  render();
  $("#setup").hidden = false;
}

// ---------------------------------------------------------------- settings UI
function openSettings() {
  $("#set-model").value = settings.model; $("#set-wasm").checked = !!settings.forceWasm;
  $("#set-tname").value = settings.tname; $("#set-tnum").value = settings.tnum;
  $("#set-avg").value = settings.avg; $("#set-sens").value = String(settings.sens);
  $("#set-speak").checked = !!settings.speak;
  $("#set-sound").checked = !!settings.sound;
  $("#set-large").checked = !!settings.large;
  $("#set-voice").checked = !!settings.voiceCheck;
  $("#settings").hidden = false;
}
function closeSettings() {
  const prevModel = settings.model, prevWasm = settings.forceWasm;
  settings.model = $("#set-model").value; settings.forceWasm = $("#set-wasm").checked;
  settings.tname = $("#set-tname").value.trim(); settings.tnum = $("#set-tnum").value.trim();
  settings.avg = Math.max(1, +$("#set-avg").value || 1500); settings.sens = +$("#set-sens").value;
  settings.speak = $("#set-speak").checked;
  settings.sound = $("#set-sound").checked;
  settings.large = $("#set-large").checked;
  settings.voiceCheck = $("#set-voice").checked;
  settings.setupDone = true;
  saveSettings();
  applyLargeText();
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
      asrReady = true;
      $("#b-asr-prog").hidden = true;
      const name = settings.model.split("/")[1];
      $("#b-asr-text").textContent = `Speech model ready on this device: ${name} · ${m.device === "webgpu" ? "GPU" : "CPU"} · warm-up ${m.warmupMs} ms`;
      pump();
    } else if (m.type === "error") {
      $("#b-asr-prog").hidden = true;
      $("#b-asr-text").textContent = "Speech model failed to load: " + m.message;
    } else if (m.type === "text") {
      asrBusy = false;
      if (m.text && state.session) addUtterance(m.text, { asrMs: m.ms, dur: m.dur });
      setListen(state.session ? "listening" : "idle");
      pump();
    }
  };
  worker.postMessage({ type: "load", model: settings.model, forceWasm: settings.forceWasm });
}
function onChunk(audio) {
  const s = state.session;
  if (s && s.side === "caller" && settings.voiceCheck && audio.length >= 16000 * 1.5) checkVoice(audio.slice());
  pending.push(audio);
  // never keep more than ~30 s of audio waiting
  let total = pending.reduce((s, a) => s + a.length, 0);
  while (total > 16000 * 30 && pending.length > 1) total -= pending.shift().length;
  pump();
}
function pump() {
  if (!asrReady || asrBusy || !pending.length) return;
  const take = [];
  let n = 0;
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
  if (!asrBusy && state.session && state.session.source !== "script") setListen(speech ? "hearing speech" : "listening");
}
function setListen(t) { $("#b-listen").textContent = t; }

// ---------------------------------------------------------------- screen-on (wake lock)
async function requestWake() {
  if (!state.session || wakeLock) return;
  if (!("wakeLock" in navigator)) { $("#b-keepon").hidden = false; return; }
  try {
    const w = await navigator.wakeLock.request("screen");
    if (!state.session) { w.release().catch(() => {}); return; }
    wakeLock = w;
    $("#b-keepon").hidden = true;
    w.addEventListener("release", () => { if (wakeLock === w) wakeLock = null; });
  } catch {
    $("#b-keepon").hidden = false;
  }
}
function releaseWake() {
  const w = wakeLock;
  wakeLock = null;
  try { w && w.release(); } catch {}
  $("#b-keepon").hidden = true;
}
function onVisibility() {
  if (!state.session) return;
  if (document.visibilityState === "hidden") { logEvent("screen_hidden"); return; }
  logEvent("screen_visible");
  try { getAudioContext().resume(); } catch {}
  requestWake();
}

// ---------------------------------------------------------------- sessions
function startSession(source, stop = () => {}) {
  if (state.session) endSession("replaced", true);
  state.core.scorer.reset();
  spokenLevels = new Set();
  const side = (source === "call" || source === "mic") ? settings.side : "caller";
  state.session = { source, side, start: Date.now(), stop, log: [], victimSeen: new Set(), voice: { n: 0, sum: 0, flagged: false } };
  $("#b-transcript").innerHTML = `<div class="empty">Listening… ${side === "me" ? "your own words (Android mode)" : "the caller's words"} will appear here.</div>`;
  $("#b-idle").hidden = true;
  $("#b-lib").hidden = true;
  $("#b-voice").hidden = true;
  if (side === "caller" && settings.voiceCheck && (source === "call" || source === "mic" || source === "file")) loadVoiceCheck();
  renderRisk();
  const names = { call: "In call", mic: "Listening (mic)", file: "Replaying file", script: "Demo script", typed: "Typed test" };
  setBState((names[source] || "Active") + (side === "me" ? " · Android mode" : ""), true);
  setListen(source === "script" || source === "typed" ? "text input" : "listening");
  $("#b-privacy").textContent = source === "script" || source === "typed"
    ? "Text-only demo. No audio is used."
    : "Listening now. Audio is processed on this phone and discarded. Nothing is recorded.";
  try { getAudioContext().resume(); } catch {}
  requestWake();
}
function endSession(reason, silent = false) {
  if (!state.session) return;
  const s = state.session;
  state.session = null;
  try { s.stop(); } catch {}
  if (chunker) chunker.detach();
  pending = [];
  clearVoiceQueue();
  releaseWake();
  const sc = state.core.scorer;
  state.lastSession = { ...s, end: Date.now(), history: sc.history.slice(), seen: [...sc.seen],
    warn: sc.warn, pause: sc.pause, reason, detector: state.detectorKind };
  $("#b-idle").hidden = false;
  setBState("Guarding");
  setListen("idle");
  $("#b-level").style.width = "0";
  $("#b-privacy").textContent = "Listening only while a call is active. Audio is processed on this phone and discarded.";
  if (pendingSwap) { const sw = pendingSwap; pendingSwap = null; sw(); }
  if (!silent && state.lastSession.history.length) go("summary");
}

// ---- WebRTC call (BOSC side answers)
function newCode() { return String(Math.floor(1000 + Math.random() * 9000)); }
function callerName(call) {
  const n = call && call.metadata && call.metadata.name;
  return typeof n === "string" ? n.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 40) : "";
}
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
    const name = callerName(call);
    $("#in-from").textContent = name || "Unknown caller";
    $("#in-note").hidden = !name;
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
    $("#b-remote").srcObject = null;
  });
  const name = callerName(call);
  if (name) logEvent("caller_name", { name });
  const androidMode = state.session.side === "me";
  if (androidMode) {
    // Android mode: like a real Android app, we only get this phone's microphone (the victim's voice)
    if (micStream) chunker.attach(ctx, ctx.createMediaStreamSource(micStream));
    else toast("Android mode needs the microphone. Allow it to let BOSC hear your replies.");
  }
  let attached = false;
  call.on("stream", (remote) => {
    if (attached) return; // PeerJS can fire "stream" once per track
    attached = true;
    // Chrome only feeds a remote WebRTC stream into Web Audio if it is also attached to a media element
    const a = $("#b-remote");
    a.srcObject = remote;
    a.play().catch(() => {});
    if (!androidMode) chunker.attach(ctx, ctx.createMediaStreamSource(remote));
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
  $("#b-file").value = "";
  const src = ctx.createBufferSource();
  src.buffer = buf;
  src.connect(ctx.destination);
  startSession("file", () => { try { src.stop(); } catch {} });
  const sess = state.session;
  src.onended = () => {
    chunker.flush();
    // let the last chunk finish transcribing and scoring before ending
    const wait = () => (asrBusy || pending.length || scoring)
      ? setTimeout(wait, 300)
      : state.session === sess && endSession("file-ended");
    setTimeout(wait, 500);
  };
  await chunker.attach(ctx, src);   // wait for the audio tap so the start isn't lost
  if (state.session !== sess) return;
  src.start();
}

// ---- scripted demo (text only)
function startScript() {
  const s = state.scripts.find((x) => x.id === $("#b-script").value);
  if (!s) return;
  let i = 0, timer = null;
  startSession("script", () => clearTimeout(timer));
  const sess = state.session;
  const step = () => {
    if (state.session !== sess) return;
    if (i >= s.lines.length) { timer = setTimeout(() => state.session === sess && endSession("script-ended"), 2500); return; }
    addUtterance(s.lines[i++], { script: s.id });
    timer = setTimeout(step, 2800);
  };
  timer = setTimeout(step, 600);
}

// ---------------------------------------------------------------- scoring + UI
function buildMeter() { $("#b-meter").innerHTML = "<i></i>".repeat(10); }
function buildChips() {
  const order = ["authority_claim", "threat", "urgency", "secrecy_isolation", "stay_on_line", "credential_request", "remote_access", "payment_instruction"];
  const labels = state.core.detector.labels;
  $("#b-chips").innerHTML = order.filter((t) => labels[t]).map((t) => `<span class="chip" data-t="${t}">${esc(labels[t])}</span>`).join("");
}
// Copy rules (README §9): calm, one idea per sentence, ≤ 15 words. Review changes with Data & Evaluation.
export function advice(seen, level) {
  const has = (t) => seen.has(t);
  let tip;
  if (has("stay_on_line") || (has("authority_claim") && has("threat"))) tip = "Real police never arrest anyone over a phone or video call. They never ask for money.";
  else if (has("remote_access")) tip = "Don't install any app a caller asks for. Screen sharing lets them see your OTPs.";
  else if (has("credential_request")) tip = "Never share an OTP, PIN or CVV with a caller. Banks never ask for it.";
  else if (has("payment_instruction")) tip = "Don't send money to an account or UPI ID a caller gives you.";
  else if (has("secrecy_isolation")) tip = "Scammers ask you to keep it secret. Talk to someone you trust first.";
  else if (has("threat") || has("urgency")) tip = "Pressure to act fast is a warning sign. You can hang up and call an official number.";
  else tip = "Don't share OTPs or send money because of this call.";
  if (level === "PAUSE") return "This call matches a known scam pattern. " + tip;
  if (level === "WARN") return "Be careful. " + tip;
  return seen.size ? "Listening. Nothing alarming yet." : "No pressure tactics detected.";
}
function renderRisk() {
  const sc = state.core.scorer;
  const lvl = sc.level();
  const d = display(sc.risk);
  $("#b-risk").dataset.level = lvl;
  $("#b-word").textContent = LEVEL_WORD[lvl];
  $("#b-score").textContent = `risk ${Math.round(d * 100)}/100`;
  const on = Math.round(d * 10);
  const cls = lvl === "PAUSE" ? "d" : lvl === "WARN" ? "w" : "s";
  $$("#b-meter i").forEach((el, i) => { el.className = i < on ? "on " + cls : ""; });
  const vs = state.session?.victimSeen;
  $("#b-warning").textContent = (vs && vs.size && lvl !== "SAFE") ? victimAdvice(vs) : advice(sc.seen, lvl);
  $$("#b-chips .chip").forEach((c) => c.classList.toggle("on", sc.seen.has(c.dataset.t)));
}
function highlight(text, tactics) {
  const spans = Object.values(tactics || {}).flat().sort((a, b) => a[0] - b[0]);
  let out = "", i = 0;
  for (const [s, e] of spans) {
    if (s < i) continue;
    out += esc(text.slice(i, s)) + "<mark>" + esc(text.slice(s, e)) + "</mark>";
    i = e;
  }
  return out + esc(text.slice(i));
}

// Every utterance goes through one queue, so results apply in order even when push() is async.
export function addUtterance(text, meta = {}) {
  if (!state.session) startSession("typed");
  const sess = state.session;
  scoring++;
  scoreChain = scoreChain
    .then(() => scoreOne(sess, text, meta))
    .catch((err) => console.error("Scoring failed:", err))
    .then(() => { scoring--; });
}
async function scoreOne(sess, text, meta) {
  if (state.session !== sess) return;      // the session ended while this line waited
  if (sess.side === "me") return scoreVictim(sess, text, meta);
  const sc = state.core.scorer;
  const prev = sc.level();
  const r = await sc.push(text);           // sync for Stage 0, a Promise for the ONNX detector
  if (!r || state.session !== sess) return; // null = the detector was reset while scoring
  const tactics = r.tactics || {};
  const entry = { t: Date.now() - sess.start, text, p: +r.p.toFixed(4), risk: +r.risk.toFixed(4), level: r.level, tactics: Object.keys(tactics), ...meta };
  if (typeof r.libMax === "number") entry.libMax = +r.libMax.toFixed(4);
  sess.log.push(entry);
  const box = $("#b-transcript");
  box.querySelector(".empty")?.remove();
  const el = document.createElement("div");
  el.className = "utt";
  el.dataset.level = r.level;
  const labels = state.core.detector.labels;
  const tl = Object.keys(tactics).filter((t) => t !== "authority_claim" || r.level !== "SAFE").map((t) => labels[t] || t);
  const lat = meta.asrMs ? ` · speech-to-text ${meta.asrMs} ms for ${meta.dur.toFixed(1)} s` : "";
  el.innerHTML = `${highlight(text, tactics)}<div class="meta">${tl.length ? esc(tl.join(" · ")) + " · " : ""}risk ${Math.round(display(r.risk) * 100)}${lat}</div>`;
  box.appendChild(el);
  box.scrollTop = box.scrollHeight;
  renderRisk();
  if (typeof r.libMax === "number" && r.libMax >= LIB_MATCH) {
    $("#b-lib").textContent = `Matches a known scam script (${Math.round(r.libMax * 100)}%)`;
    $("#b-lib").hidden = false;
  }
  const rank = { SAFE: 0, WARN: 1, PAUSE: 2 };
  if (rank[r.level] > rank[prev] && !spokenLevels.has(r.level)) {
    spokenLevels.add(r.level);
    escalate(r.level);
  }
}

// ---------------------------------------------------------------- Android mode: the victim's own words
function scoreVictim(sess, text, meta) {
  const sc = state.core.scorer;
  const prev = sc.level();
  const found = detectVictim(text);
  for (const f of found) sess.victimSeen.add(f);
  const r = sc.raiseTo(victimLevel(sess.victimSeen), text, "me");
  sess.log.push({ t: Date.now() - sess.start, side: "me", text, risk: +r.risk.toFixed(4), level: r.level, cues: found, ...meta });
  const box = $("#b-transcript");
  box.querySelector(".empty")?.remove();
  const el = document.createElement("div");
  el.className = "utt me";
  el.dataset.level = r.level;
  const lat = meta.asrMs ? ` · speech-to-text ${meta.asrMs} ms for ${meta.dur.toFixed(1)} s` : "";
  const cueText = found.map((f) => VICTIM_CUES[f].label).join(" · ");
  el.innerHTML = `<span class="who">You said</span> ${found.length ? "<mark>" + esc(text) + "</mark>" : esc(text)}` +
    `<div class="meta">${cueText ? esc(cueText) + " · " : ""}risk ${Math.round(display(r.risk) * 100)}${lat}</div>`;
  box.appendChild(el);
  box.scrollTop = box.scrollHeight;
  renderRisk();
  const rank = { SAFE: 0, WARN: 1, PAUSE: 2 };
  if (rank[r.level] > rank[prev] && !spokenLevels.has(r.level)) { spokenLevels.add(r.level); escalate(r.level); }
}

function renderSide() {
  $$("#b-side button").forEach((x) => x.classList.toggle("on", x.dataset.side === settings.side));
  $("#b-side-note").textContent = settings.side === "me"
    ? "Android mode: Android doesn't let apps hear the other side of a phone call, so BOSC listens only to your voice. It warns you if you start reading out an OTP, agree to pay, promise to keep it secret, or install a screen-sharing app."
    : "Listens to the caller's words (our web call, or a call on speaker) and checks whether the voice sounds AI-generated.";
}

// ---------------------------------------------------------------- voice check (experimental)
function onVoiceStatus(status, frac = 0, message = "") {
  const s = state.session;
  if (!s || s.side !== "caller" || s.voice.n) return;
  const el = $("#b-voice");
  el.hidden = false;
  el.dataset.flag = "";
  el.textContent = status === "loading" ? `Voice check: loading model… ${Math.round(frac * 100)}%`
    : status === "ready" ? "Voice check: listening…"
    : `Voice check unavailable (${message.slice(0, 80)})`;
}
function onVoiceResult(fake, ms) {
  const s = state.session;
  if (!s || s.side !== "caller") return;
  const v = s.voice;
  v.n += 1;
  v.sum += fake;
  const mean = v.sum / v.n;
  s.log.push({ t: Date.now() - s.start, event: "voice_check", fake: +fake.toFixed(3), ms });
  const el = $("#b-voice");
  el.hidden = false;
  if (v.n >= VOICE_MIN_CLIPS && mean >= VOICE_FAKE) {
    el.dataset.flag = "1";
    el.textContent = `Voice check (experimental, not reliable yet): unusual voice, score ${Math.round(mean * 100)}/100.`;
    if (!v.flagged && !VOICE_RAISES_RISK) { v.flagged = true; logEvent("voice_unusual", { mean: +mean.toFixed(3), clips: v.n }); }
    if (!v.flagged) {
      v.flagged = true;
      const sc = state.core.scorer;
      const prev = sc.level();
      const r = sc.raiseTo("WARN", "[voice check: voice may be AI-generated]", "voice");
      logEvent("voice_synthetic", { mean: +mean.toFixed(3), clips: v.n });
      renderRisk();
      const rank = { SAFE: 0, WARN: 1, PAUSE: 2 };
      if (rank[r.level] > rank[prev] && !spokenLevels.has(r.level)) { spokenLevels.add(r.level); escalate(r.level); }
    }
  } else if (!v.flagged) {
    el.dataset.flag = "";
    el.textContent = `Voice check (experimental, not reliable yet): score ${Math.round(mean * 100)}/100 over ${v.n} clip${v.n > 1 ? "s" : ""}.`;
  }
}

// ---------------------------------------------------------------- alerts (haptics, chime, voice)
function escalate(lvl) {
  // WARN: two short buzzes. PAUSE: three long buzzes. Different enough to tell apart in a pocket.
  try { navigator.vibrate?.(lvl === "PAUSE" ? [600, 200, 600, 200, 600] : [200, 120, 200]); } catch {}
  if (settings.sound) chime(lvl);
  if (settings.speak && "speechSynthesis" in window) {
    setTimeout(() => {
      try {
        speechSynthesis.cancel();
        const u = new SpeechSynthesisUtterance(lvl === "PAUSE"
          ? "Careful. This call has signs of a scam. Do not share an O T P or send money."
          : "Be careful with this call.");
        u.lang = "en-IN"; u.rate = 0.95;
        speechSynthesis.speak(u);
      } catch {}
    }, settings.sound ? 800 : 0);
  }
}
// Gentle tones, not a siren: WARN rises (two notes), PAUSE falls (three slower notes).
function chime(lvl) {
  try {
    const ctx = getAudioContext();
    if (ctx.state === "suspended") ctx.resume();
    const notes = lvl === "PAUSE" ? [880, 698.46, 587.33] : [659.25, 880];
    const step = lvl === "PAUSE" ? 0.3 : 0.2, len = step * 0.9;
    const t0 = ctx.currentTime + 0.02;
    notes.forEach((f, i) => {
      const o = ctx.createOscillator(), g = ctx.createGain();
      const t = t0 + i * step;
      o.type = "sine";
      o.frequency.value = f;
      g.gain.setValueAtTime(0.0001, t);
      g.gain.exponentialRampToValueAtTime(0.2, t + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t + len);
      o.connect(g).connect(ctx.destination);
      o.start(t);
      o.stop(t + len + 0.02);
    });
  } catch {}
}
