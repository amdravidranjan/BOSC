// Call summary: verdict, risk-over-time chart (with the moment Pay was tapped), tactics, log download.
import { $, esc, fmtTime } from "./util.js";
import { state, display } from "./state.js";

export function renderSummary() {
  const s = state.lastSession;
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

  // Pay attempts made during the call: a vertical line between the turn before and after the tap.
  const pays = (s.log || []).filter((l) => l.event === "pay_attempt" && typeof l.turn === "number");
  const payX = (turn) => x(Math.max(0, Math.min(h.length - 1, turn - 0.5)));
  const payMarks = pays.map((l, i) => {
    const px = payX(l.turn).toFixed(1);
    const anchor = px < 60 ? "start" : px > W - 60 ? "end" : "middle";
    return `<line class="pay" x1="${px}" y1="${P - 4}" x2="${px}" y2="${H - P}"><title>Pay ₹${l.amount.toLocaleString("en-IN")} tapped · Shield ${l.level}</title></line>`
      + (i === 0 ? `<text class="pay-t" x="${px}" y="${P - 8}" text-anchor="${anchor}">Pay tapped</text>` : "");
  }).join("");

  $("#s-graph").innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Risk over the call${pays.length ? ", with the moment Pay was tapped" : ""}">
    <line class="ax" x1="${P}" y1="${H - P}" x2="${W - P}" y2="${H - P}"/>
    <line class="th-w" x1="${P}" y1="${y(s.warn)}" x2="${W - P}" y2="${y(s.warn)}"/><text x="${W - P}" y="${y(s.warn) - 4}" text-anchor="end">caution</text>
    <line class="th-p" x1="${P}" y1="${y(s.pause)}" x2="${W - P}" y2="${y(s.pause)}"/><text x="${W - P}" y="${y(s.pause) - 4}" text-anchor="end">high risk</text>
    ${payMarks}
    <polyline class="ln" points="${pts}"/>
    ${h.map((e, i) => `<circle cx="${x(i)}" cy="${y(e.risk)}" r="3" fill="var(--brand)"><title>${esc(e.text)}</title></circle>`).join("")}
    <text x="${P}" y="${H - 6}">start</text><text x="${W - P}" y="${H - 6}" text-anchor="end">caller turns →</text></svg>`;

  const labels = state.core.detector.labels;
  $("#s-chips").innerHTML = s.seen.map((t) => `<span class="chip on">${esc(labels[t] || t)}</span>`).join("") || `<span class="small">No pressure tactics seen.</span>`;
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
