// PayFast screen + BOSC Shield: decides LOW / MEDIUM / HIGH for a payment.
import { PRESSURE } from "./bosc-core.js";
import { $, toast, esc } from "./util.js";
import { settings } from "./settings.js";
import { state, LEVEL_WORD, logEvent } from "./state.js";
import { inited, go } from "./router.js";
import { advice } from "./bosc.js";

let waitTimer = null, mediumTimer = null, payAllowedAt = 0;

export function initPay() {
  $("#p-amt").oninput = () => { $("#p-amt").value = $("#p-amt").value.replace(/[^\d]/g, ""); refreshPay(); };
  $("#p-pay").onclick = onPay;
  $("#p-again").onclick = () => { $("#p-done").hidden = true; $("#p-form").hidden = false; refreshPay(); };
  $("#pause-cancel").onclick = () => { closePause(); logEvent("pay_cancelled"); toast("Payment cancelled. Good call."); go("bosc"); };
  $("#pause-wait").onclick = () => {
    closePause();
    logEvent("pay_wait_60s");
    payAllowedAt = Date.now() + 60000;
    tickWait();
  };
  $("#pause-override").onclick = () => { closePause(); logEvent("user_override_pay"); paid(); };
  for (const id of ["#p-screenshare", "#p-newpayee", "#p-payee", "#p-upi"]) $(id).addEventListener("input", refreshPay);
}

function shieldEvaluate() {
  const sc = state.core ? state.core.scorer : null;
  const onCall = !!state.session;
  const callLevel = sc && onCall ? sc.level() : "SAFE";
  const callRisk = sc && onCall ? sc.score() : 0;
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
    const labels = state.core.detector.labels;
    const seen = [...sc.seen].filter((t) => PRESSURE.has(t)).map((t) => labels[t] || t);
    if (seen.length) reasons.push("The caller used: " + seen.join(", ") + ".");
  }
  if (screen) reasons.push("Screen sharing or a remote-control app is active.");
  if (ratio >= 5) reasons.push(`This is ${Math.round(ratio)}× your usual payment.`);
  if (newPayee) reasons.push("You have never paid this person before.");
  if (kw) reasons.push(`The payee name looks like a "safe account". Real agencies never ask for this.`);
  return { p, level, reasons, onCall, callLevel, amount };
}

export function refreshPay() {
  if (!inited.has("pay")) return;
  const ev = shieldEvaluate();
  const amt = ev.amount.toLocaleString("en-IN");
  $("#p-pay").textContent = `Pay ₹${amt}`;
  $("#p-callbar").dataset.level = ev.callLevel;
  $("#p-calltext").textContent = ev.onCall ? `On a call · BOSC: ${LEVEL_WORD[ev.callLevel]}` : "Not on a call";
  $("#p-sig-list").innerHTML = `Shield score ${(ev.p * 100).toFixed(0)}/100 → <b>${ev.level}</b><br>` + ev.reasons.map(esc).join("<br>");
  if (!mediumTimer && !payAllowedAt) { $("#p-inline").hidden = true; $("#p-pay").disabled = false; }
}

function onPay() {
  const ev = shieldEvaluate();
  if (Date.now() < payAllowedAt) return;
  // marks the moment on the summary chart (turn = caller lines scored so far)
  if (state.session) logEvent("pay_attempt", { level: ev.level, amount: ev.amount, turn: state.core.scorer.history.length });
  if (ev.level === "HIGH" && !(payAllowedAt && Date.now() >= payAllowedAt)) return openPause(ev);
  if (ev.level === "MEDIUM" && !mediumTimer && !payAllowedAt) {
    let n = 10;
    $("#p-inline").hidden = false;
    $("#p-pay").disabled = true;
    const tick = () => {
      $("#p-inline").textContent = `Take a moment. ${ev.reasons[0] || "This payment looks unusual."} You can pay in ${n} s.`;
      if (n-- <= 0) {
        clearInterval(mediumTimer); mediumTimer = null;
        $("#p-pay").disabled = false;
        $("#p-inline").textContent = "If you are sure, tap Pay again.";
        payAllowedAt = Date.now();
      }
    };
    tick();
    mediumTimer = setInterval(tick, 1000);
    return;
  }
  paid();
}

function openPause(ev) {
  $("#pause-reasons").innerHTML = ev.reasons.map((r) => `<li>${esc(r)}</li>`).join("");
  $("#pause-advice").textContent = state.session
    ? advice(state.core.scorer.seen, "PAUSE").replace("This call matches a known scam pattern. ", "")
    : "Take a minute. Check with someone you trust before sending money.";
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

function paid() {
  $("#p-form").hidden = true;
  $("#p-done").hidden = false;
  payAllowedAt = 0;
  clearInterval(waitTimer);
  $("#p-inline").hidden = true;
}
