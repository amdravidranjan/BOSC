// Caller (Phone A): dials the BOSC phone over WebRTC and shows the script teleprompter.
import { $, toast, esc, fmtTime } from "./util.js";
import { state, PEER_PREFIX, scriptsReady } from "./state.js";

let cPeer = null, cCall = null, cStream = null, cTimer = null, cLine = 0;

const lsGet = (k) => { try { return localStorage.getItem(k) || ""; } catch { return ""; } };
const lsSet = (k, v) => { try { localStorage.setItem(k, v); } catch {} };

export function initCaller() {
  scriptsReady.then(() => {
    const sel = $("#c-script");
    sel.innerHTML = state.scripts.map((s) => `<option value="${esc(s.id)}">${esc(s.title)}</option>`).join("");
    sel.onchange = () => { cLine = 0; renderPrompter(); };
    renderPrompter();
  });
  $("#c-prev").onclick = () => { cLine = Math.max(0, cLine - 1); renderPrompter(); };
  $("#c-next").onclick = () => { cLine++; renderPrompter(); };
  $("#c-code").value = lsGet("bosc-last-code");
  $("#c-name").value = lsGet("bosc-caller-name");
  $("#c-call").onclick = callerDial;
  $("#c-hang").onclick = () => callerEnd(true);
  $("#c-mute").onclick = () => {
    if (!cStream) return;
    const tr = cStream.getAudioTracks()[0];
    tr.enabled = !tr.enabled;
    $("#c-mute").textContent = tr.enabled ? "Mute" : "Unmute";
  };
  if (typeof Peer === "undefined") { setCState("Offline"); toast("Call library failed to load. Check the internet connection."); return; }
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
  const s = state.scripts.find((x) => x.id === $("#c-script").value) || state.scripts[0];
  if (!s) return;
  if (cLine >= s.lines.length) { $("#c-prompter").innerHTML = `<span class="n">End of script</span>Hang up when ready.`; return; }
  $("#c-prompter").innerHTML = `<span class="n">Line ${cLine + 1} of ${s.lines.length} · ${esc(s.kind)}</span>${esc(s.lines[cLine])}`;
}

function setCState(t, live = false) { const p = $("#c-state"); p.lastChild.textContent = t; p.classList.toggle("live", live); }

async function callerDial() {
  const code = $("#c-code").value.trim();
  if (!/^\d{4}$/.test(code)) return toast("Enter the 4-digit code shown on the BOSC phone.");
  if (!cPeer) return toast("Call library isn't loaded. Check the internet connection.");
  lsSet("bosc-last-code", code);
  const name = $("#c-name").value.trim().slice(0, 40);
  lsSet("bosc-caller-name", name);
  try {
    cStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
  } catch { return toast("Microphone permission is needed to call."); }
  setCState("Ringing…");
  cCall = cPeer.call(PEER_PREFIX + code, cStream, { metadata: { name } });
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
