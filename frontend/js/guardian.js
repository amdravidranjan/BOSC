// Guardian link over a PeerJS data channel (no server of ours; the same signalling the call uses).
//  - Host side (the protected phone): accepts guardian connections, sends live status and the end-of-call report.
//  - Watcher side (the guardian's phone, #guardian view): enters the protected phone's 4-digit code.
// Only levels, tactic names, scores and the summary travel; never the transcript.
import { $, esc, toast, fmtTime } from "./util.js";
import { PEER_PREFIX, LEVEL_WORD } from "./state.js";

// ---------------------------------------------------------------- host
const guardians = new Map();   // conn.peer -> {conn, name, acked}
let hostHandlers = {}, lastStatus = null, statusTimer = null;

export function attachGuardianHost(peer, handlers = {}) {
  hostHandlers = handlers;
  peer.on("connection", (conn) => {
    const meta = conn.metadata || {};
    if (meta.role !== "guardian") return;
    conn.on("open", () => {
      guardians.set(conn.peer, { conn, name: meta.name || "Guardian", acked: false });
      try { conn.send({ type: "hello", name: handlers.myName?.() || "", phone: handlers.myPhone?.() || "" }); } catch {}
      if (lastStatus) try { conn.send(lastStatus); } catch {}
      handlers.onChange?.(guardianNames());
      toast(`${meta.name || "A guardian"} is now watching over this phone.`);
    });
    conn.on("data", (m) => {
      if (m && m.type === "ack") {
        const g = guardians.get(conn.peer);
        if (g) g.acked = true;
        handlers.onAck?.(meta.name || "Guardian");
      }
    });
    const drop = () => { guardians.delete(conn.peer); handlers.onChange?.(guardianNames()); };
    conn.on("close", drop);
    conn.on("error", drop);
  });
}

export function guardianNames() { return [...guardians.values()].map((g) => g.name); }
export function guardianCount() { return guardians.size; }

export function guardianSend(msg) {
  let n = 0;
  for (const g of guardians.values()) { try { g.conn.send(msg); n++; } catch {} }
  return n;
}

// live status, throttled (at most ~3 per second)
export function guardianStatus(status) {
  lastStatus = { type: "status", ...status, t: Date.now() };
  if (statusTimer) return;
  statusTimer = setTimeout(() => { statusTimer = null; guardianSend(lastStatus); }, 300);
}

// ---------------------------------------------------------------- watcher (#guardian view)
let wPeer = null, wConn = null, wName = "";
const lsGet = (k) => { try { return localStorage.getItem(k) || ""; } catch { return ""; } };
const lsSet = (k, v) => { try { localStorage.setItem(k, v); } catch {} };

export function initGuardianView() {
  $("#g-code").value = lsGet("bosc-guard-code");
  $("#g-name").value = lsGet("bosc-guard-name") || "Priya";
  $("#g-setup").onsubmit = (e) => { e.preventDefault(); connect(); };
  setTimeout(() => { try { $("#g-code").focus(); } catch {} }, 50);
  $("#g-disconnect").onclick = () => disconnect(true);
  $("#g-ack").onclick = () => {
    if (wConn && wConn.open) { try { wConn.send({ type: "ack", name: wName }); } catch {} }
    $("#g-alert").dataset.acked = "1";
    $("#g-ack").textContent = "Marked as seen";
    $("#g-ack").disabled = true;
  };
  setGState("Not connected");
  if (typeof Peer === "undefined") { setGState("Offline"); return; }
  wPeer = new Peer({ debug: 1 });
  wPeer.on("error", (err) => {
    if (err.type === "peer-unavailable") { setGState("No phone with that code"); toast("No protected phone is online with that code."); }
    else setGState("Error: " + err.type);
    $("#g-connect").disabled = false;
  });
  wPeer.on("disconnected", () => { try { wPeer.reconnect(); } catch {} });
}

function setGState(t, live = false) { const p = $("#g-status"); p.lastChild.textContent = t; p.classList.toggle("live", live); }

function connect() {
  const code = $("#g-code").value.trim();
  wName = $("#g-name").value.trim().slice(0, 30) || "Guardian";
  if (!/^\d{4}$/.test(code)) return toast("Enter the 4-digit code shown on the protected phone.");
  if (!wPeer) return toast("Connection library isn't loaded. Check the internet connection.");
  lsSet("bosc-guard-code", code); lsSet("bosc-guard-name", wName);
  disconnect(false);
  $("#g-connect").disabled = true;
  setGState("Connecting…");
  wConn = wPeer.connect(PEER_PREFIX + code, { metadata: { role: "guardian", name: wName }, reliable: true });
  wConn.on("open", () => {
    setGState("Connected", true);
    $("#g-connect").disabled = false;
    $("#g-setup").hidden = true;
    $("#g-watch").hidden = false;
    $("#g-alert").hidden = true;
    addLog("Connected to the protected phone.");
  });
  wConn.on("data", onData);
  wConn.on("close", () => { setGState("Disconnected"); $("#g-connect").disabled = false; addLog("Connection closed."); $("#g-setup").hidden = false; });
  wConn.on("error", () => { setGState("Connection error"); $("#g-connect").disabled = false; });
}

function disconnect(user) {
  if (wConn) { try { wConn.close(); } catch {} }
  wConn = null;
  if (user) { setGState("Not connected"); $("#g-setup").hidden = false; $("#g-watch").hidden = true; }
}

let whoName = "", whoPhone = "";
const who = () => whoName || "Your family member";
function onData(m) {
  if (!m || typeof m !== "object") return;
  if (m.type === "hello") {
    whoName = m.name || ""; whoPhone = m.phone || "";
    $("#g-who").textContent = whoName ? `Watching ${whoName}'s phone` : "Watching the protected phone";
    const call = $("#g-alert-call");
    if (whoPhone) { call.href = "tel:" + whoPhone.replace(/[^\d+]/g, ""); call.hidden = false; call.textContent = `Call ${who()} now`; }
    else call.hidden = true;
  } else if (m.type === "call") {
    if (m.state === "started") { $("#g-live").dataset.active = "1"; $("#g-callstate").textContent = "On a call now"; addLog("A call started."); $("#g-alert").hidden = true; }
    else { $("#g-live").dataset.active = ""; $("#g-callstate").textContent = "Not on a call"; }
  } else if (m.type === "status") {
    renderWatch(m);
  } else if (m.type === "report") {
    renderReport(m.report);
  }
}

function renderWatch(s) {
  $("#g-live").dataset.level = s.level;
  $("#g-word").textContent = LEVEL_WORD[s.level] || s.level;
  $("#g-score").textContent = `${Math.round((s.score || 0) * 100)} / 100`;
  $("#g-needle").style.transform = `rotate(${-90 + 180 * Math.min(1, s.score || 0)}deg)`;
  $("#g-tactics").innerHTML = (s.tactics || []).map((t) => `<li>${esc(t)}</li>`).join("") || "<li class=\"quiet\">No pressure tactics yet</li>";
  $("#g-voice").textContent = s.voice || "";
  $("#g-callstate").textContent = s.inCall ? "On a call now" : "Not on a call";
}

function renderReport(r) {
  const a = $("#g-alert");
  a.hidden = false;
  a.dataset.level = r.level;
  a.dataset.acked = "";
  $("#g-ack").disabled = false; $("#g-ack").textContent = "I've seen this";
  $("#g-alert-title").textContent = r.level === "PAUSE" ? `${who()} just had a likely scam call` : r.level === "WARN" ? `${who()} had a call with warning signs` : `${who()}'s last call looked fine`;
  $("#g-alert-body").innerHTML = `<p class="big-num">${r.score} / 100</p><p>${esc(r.summary)}</p>` +
    (r.reasons.length ? `<ul>${r.reasons.map((x) => `<li>${esc(x.title)}</li>`).join("")}</ul>` : "") +
    `<p class="quiet">${fmtTime(r.durationMs)} call, ${r.turns} lines heard${r.payAttempt ? ", a payment was attempted" : ""}.</p>`;
  addLog(`Call ended: ${LEVEL_WORD[r.level]} (${r.score}/100).`);
  try { navigator.vibrate?.(r.level === "PAUSE" ? [500, 150, 500, 150, 500] : [250]); } catch {}
  if (r.level !== "SAFE" && "Notification" in window && Notification.permission === "granted") {
    try { new Notification($("#g-alert-title").textContent, { body: r.summary }); } catch {}
  }
  if (r.level !== "SAFE" && "speechSynthesis" in window) {
    try { speechSynthesis.cancel(); const u = new SpeechSynthesisUtterance($("#g-alert-title").textContent + ". " + r.summary); u.lang = "en-IN"; speechSynthesis.speak(u); } catch {}
  }
  a.scrollIntoView({ behavior: "smooth", block: "start" });
}

function addLog(t) {
  const el = document.createElement("li");
  el.textContent = `${new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}  ${t}`;
  $("#g-log").prepend(el);
}
