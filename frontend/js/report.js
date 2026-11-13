// End-of-call analysis: one report from the whole conversation (caller tactics, your replies, voice checks,
// payment attempts) -> risk score 0-100, verdict, plain-language reasons and what to do next.
// Also renders the Summary screen and builds the SMS / WhatsApp alert text.
import { $, esc, fmtTime } from "./util.js";
import { state, LEVEL_WORD } from "./state.js";
import { settings } from "./settings.js";
import { PRESSURE } from "./bosc-core.js";
import { VICTIM_CUES } from "./victim-cues.js";

export function buildReport(s) {
  const labels = state.core.detector.labels;
  const hist = s.history || [];
  const scores = hist.map((h) => h.score ?? 0);
  const peak = Math.max(0, ...scores);
  const last = s.finalScore ?? (scores.length ? scores[scores.length - 1] : 0);
  // the call's score: its peak, with a small weight on how it ended (a call that stayed high is worse)
  const score01 = Math.min(0.99, Math.max(peak, 0.85 * peak + 0.15 * last));
  const score = Math.round(score01 * 100);
  const level = score01 >= 0.7 ? "PAUSE" : score01 >= 0.5 ? "WARN" : "SAFE";
  const seen = new Set(s.seen || []);
  const reasons = [];
  const quoteFor = (t) => (hist.find((h) => h.tactics && Object.keys(h.tactics).includes(t)) || {}).text;
  const order = ["authority_claim", "threat", "secrecy_isolation", "credential_request", "payment_instruction", "remote_access", "urgency", "stay_on_line"];
  for (const t of order) {
    if (!seen.has(t)) continue;
    const q = quoteFor(t);
    reasons.push({ kind: "tactic", key: t, title: tacticSentence(t, labels[t]), quote: q ? q.slice(0, 120) : null });
  }
  const victim = [...(s.victimSeen || [])];
  for (const v of victim) reasons.push({ kind: "you", key: v, title: VICTIM_CUES[v] ? youSentence(v) : v });
  if (s.voice && s.voice.flagged) reasons.push({ kind: "voice", key: "synthetic", title: `The caller's voice sounded computer-generated (AI-voice score ${Math.round(s.voice.mean * 100)}/100).` });
  if (s.imp && s.imp.verdict === "mismatch") reasons.push({ kind: "voice", key: "impersonation", title: `The caller claimed to be ${s.imp.claimText}, but the voice did not match ${s.imp.name}'s saved voice.` });
  if (s.imp && s.imp.verdict === "match") reasons.push({ kind: "ok", key: "voicematch", title: `The voice matched ${s.imp.name}'s saved voice.` });
  const payAttempt = (s.log || []).find((l) => l.event === "pay_attempt");
  const paid = (s.log || []).find((l) => l.event === "user_override_pay");
  const cancelled = (s.log || []).find((l) => l.event === "pay_cancelled");
  if (payAttempt) reasons.push({ kind: payAttempt.level === "HIGH" ? "pay" : "ok", key: "pay", title: `A payment of ₹${(payAttempt.amount || 0).toLocaleString("en-IN")} was attempted during the call${cancelled ? " and cancelled" : paid ? " and sent anyway" : " and paused"}.` });

  const summary = summarySentence(level, seen, victim, s, labels);
  const actions = actionsFor(level, seen, victim, s);
  const timeline = hist.map((h, i) => ({ i, score: h.score ?? 0, text: h.text, side: h.side || "caller", level: h.level }));
  return {
    v: 1, score, level, verdict: { SAFE: "This call looked fine", WARN: "This call had warning signs", PAUSE: "This was very likely a scam call" }[level],
    summary, reasons, actions, timeline, durationMs: (s.end || Date.now()) - s.start, turns: hist.filter((h) => !h.side || h.side === "caller").length,
    source: s.source, side: s.side, detector: s.detector, payAttempt: !!payAttempt, paid: !!paid, at: s.start,
    tactics: [...seen].map((t) => labels[t] || t), victim: victim.map((v) => VICTIM_CUES[v]?.label || v),
  };
}

function tacticSentence(t, label) {
  return {
    authority_claim: "The caller claimed to be an official (police, bank, government).",
    threat: "The caller used threats (arrest, blocked account, legal action).",
    secrecy_isolation: "The caller asked you to keep the call secret from your family.",
    credential_request: "The caller asked for an OTP, PIN or card details.",
    payment_instruction: "The caller told you to send money or pay a fee.",
    remote_access: "The caller asked you to install a screen-sharing or remote-control app.",
    urgency: "The caller pushed you to act immediately.",
    stay_on_line: "The caller insisted you stay on the line.",
  }[t] || label;
}
function youSentence(v) {
  return {
    code_readout: "You read out a code during the call.",
    shared_secret: "You shared a PIN or card detail.",
    remote_app: "You installed or opened a remote-control app.",
    agreeing_to_pay: "You agreed to send money.",
    agreeing_to_secrecy: "You agreed to keep the call secret.",
    fear: "You sounded frightened.",
  }[v];
}
function summarySentence(level, seen, victim, s, labels) {
  const who = seen.has("authority_claim") ? "A caller claiming to be an official" : "The caller";
  const did = [];
  if (seen.has("threat")) did.push("used threats");
  if (seen.has("credential_request")) did.push("asked for an OTP or PIN");
  if (seen.has("payment_instruction")) did.push("asked for money");
  if (seen.has("remote_access")) did.push("asked for a screen-sharing app");
  if (seen.has("secrecy_isolation")) did.push("asked for secrecy");
  if (!did.length && seen.has("urgency")) did.push("pushed for an immediate decision");
  let out = did.length ? `${who} ${did.slice(0, 3).join(", ").replace(/, ([^,]*)$/, " and $1")}.` : level === "SAFE" ? "No pressure tactics were heard." : "The call showed some pressure.";
  if (victim.includes("code_readout")) out += " A code was read out.";
  if (victim.includes("agreeing_to_pay")) out += " Money was agreed to.";
  if (s.voice && s.voice.flagged) out += " The voice may be computer-generated.";
  if (s.imp && s.imp.verdict === "mismatch") out += " The voice did not match the person they claimed to be.";
  return out;
}
function actionsFor(level, seen, victim, s) {
  const a = [];
  if (level === "PAUSE" || victim.includes("code_readout") || victim.includes("shared_secret")) a.push("Do not send money or share any more codes because of this call.");
  if (victim.includes("code_readout") || victim.includes("shared_secret")) a.push("Call your bank's official number now and ask them to block the card or UPI.");
  if (victim.includes("remote_app")) a.push("Uninstall the remote-control app and switch off the phone's internet for now.");
  if (level !== "SAFE" && (seen.has("authority_claim") || seen.has("threat"))) a.push("Real police and banks never take action over a phone call. Call 1930 (cyber-crime helpline) to check.");
  if (level !== "SAFE" && settings.tname) a.push(`Talk to ${settings.tname} before you do anything the caller asked.`);
  if (level === "SAFE") a.push("Nothing to do. Stay careful with any call that asks for codes or money.");
  return a;
}

// text for SMS / WhatsApp to the guardian
export function alertText(r) {
  const who = settings.myname ? `${settings.myname}'s phone` : "BOSC";
  return `${who}: ${r.verdict} (risk ${r.score}/100). ${r.summary} ${r.tactics.length ? "Signs: " + r.tactics.join(", ") + "." : ""} Please call me.`;
}

// ---------------------------------------------------------------- Summary screen
export function renderSummary() {
  const s = state.lastSession;
  const v = $("#v-summary");
  if (!s || !s.report) { v.dataset.level = "SAFE"; $("#s-verdict").textContent = "No call yet"; $("#s-summary").textContent = "After a call ends, its analysis appears here."; $("#s-graph").innerHTML = ""; $("#s-reasons").innerHTML = ""; $("#s-actions").innerHTML = ""; $("#s-alerts").innerHTML = ""; return; }
  const r = s.report;
  v.dataset.level = r.level;
  $("#s-verdict").textContent = r.verdict;
  $("#s-score").textContent = `${r.score}`;
  $("#s-summary").textContent = r.summary;
  $("#s-meta").textContent = `${fmtTime(r.durationMs)} call, ${r.turns} caller lines, ${r.detector === "advanced" ? "on-device AI model" : "basic detector"}`;
  $("#s-graph").innerHTML = chart(r);
  $("#s-reasons").innerHTML = r.reasons.length
    ? r.reasons.map((x) => `<li class="r-${x.kind}">${esc(x.title)}${x.quote ? `<q>${esc(x.quote)}</q>` : ""}</li>`).join("")
    : `<li class="r-ok">No pressure tactics were heard.</li>`;
  $("#s-actions").innerHTML = r.actions.map((x) => `<li>${esc(x)}</li>`).join("");
  // alerts
  const g = s.alerts || {};
  const rows = [];
  rows.push(`<li class="${g.device ? "done" : ""}">${g.device ? "You were alerted on this phone." : "No alert was needed on this phone."}</li>`);
  if (g.guardianSent) rows.push(`<li class="done">Sent to ${esc(g.guardianNames.join(", "))}'s phone${g.guardianAck ? ` (seen by ${esc(g.guardianAck)})` : ""}.</li>`);
  else if (r.level !== "SAFE") rows.push(`<li class="todo">No guardian phone is connected. Send the alert yourself:</li>`);
  $("#s-alerts").innerHTML = rows.join("");
  const num = (settings.tnum || "").replace(/[^\d+]/g, "");
  const text = alertText(r);
  const sms = $("#s-sms"), wa = $("#s-wa"), call = $("#s-call-guardian");
  sms.hidden = wa.hidden = call.hidden = !(settings.tnum && r.level !== "SAFE");
  if (settings.tnum) {
    sms.href = `sms:${num}?body=${encodeURIComponent(text)}`;
    wa.href = `https://wa.me/${num.replace(/^\+/, "")}?text=${encodeURIComponent(text)}`;
    call.href = `tel:${num}`;
    sms.textContent = `SMS ${settings.tname || "guardian"}`;
    wa.textContent = `WhatsApp ${settings.tname || "guardian"}`;
    call.textContent = `Call ${settings.tname || "guardian"}`;
  }
  $("#s-helpline").hidden = r.level === "SAFE";
  $("#s-download").onclick = () => {
    const lines = s.log.map((l) => JSON.stringify(l)).join("\n");
    const blob = new Blob([JSON.stringify({ report: r }) + "\n" + lines + "\n"], { type: "application/x-ndjson" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `bosc-call-${new Date(s.start).toISOString().slice(0, 19).replace(/:/g, "")}.jsonl`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };
}

function chart(r) {
  const h = r.timeline;
  if (!h.length) return "";
  const W = 340, H = 150, P = 24;
  const x = (i) => P + (h.length < 2 ? 0 : (i * (W - 2 * P)) / (h.length - 1));
  const y = (v) => H - P - v * (H - 2 * P);
  const pts = h.map((e, i) => `${x(i).toFixed(1)},${y(e.score).toFixed(1)}`).join(" ");
  const pays = (state.lastSession.log || []).filter((l) => l.event === "pay_attempt" && typeof l.turn === "number");
  const payMarks = pays.map((l) => { const px = x(Math.max(0, Math.min(h.length - 1, l.turn - 0.5))).toFixed(1); return `<line class="pay" x1="${px}" y1="${P - 4}" x2="${px}" y2="${H - P}"/><text class="pay-t" x="${px}" y="${P - 8}" text-anchor="middle">Pay tapped</text>`; }).join("");
  return `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Risk over the call">
    <rect class="band-p" x="${P}" y="${y(1)}" width="${W - 2 * P}" height="${y(0.7) - y(1)}"/>
    <rect class="band-w" x="${P}" y="${y(0.7)}" width="${W - 2 * P}" height="${y(0.5) - y(0.7)}"/>
    <line class="ax" x1="${P}" y1="${H - P}" x2="${W - P}" y2="${H - P}"/>
    <text x="${P + 4}" y="${y(0.7) - 4}">likely scam</text>
    <text x="${P + 4}" y="${y(0.5) - 4}">be careful</text>
    ${payMarks}
    <polyline class="ln" points="${pts}"/>
    ${h.map((e, i) => `<circle class="pt ${e.side}" cx="${x(i)}" cy="${y(e.score)}" r="3.5"><title>${esc(e.text)}</title></circle>`).join("")}
    <text x="${P}" y="${H - 6}">start</text><text x="${W - P}" y="${H - 6}" text-anchor="end">end of call</text></svg>`;
}

export { PRESSURE };
