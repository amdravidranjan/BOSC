// Entry point: wires each screen to the router and boots the app.
//   caller.js  Phone A (dialler + teleprompter)
//   bosc.js    Phone B (call, speech-to-text, detector, risk UI)
//   shield.js  PayFast payment screen + Shield decision
//   summary.js call summary
import { toast } from "./util.js";
import { applyLargeText } from "./settings.js";
import { onView, ensureInit, startRouter } from "./router.js";
import { initCaller } from "./caller.js";
import { initBosc } from "./bosc.js";
import { initPay, refreshPay } from "./shield.js";
import { renderSummary } from "./summary.js";

applyLargeText();
onView("caller", { init: initCaller });
onView("bosc", { init: initBosc });
onView("pay", { init: initPay, enter: refreshPay });
onView("summary", { enter: renderSummary });

if (!window.isSecureContext) toast("This page needs HTTPS (or localhost) for the microphone.", 6000);
startRouter();
// the Pay and Summary views depend on BOSC state; make sure it is initialised
if (["pay", "summary"].includes(location.hash.slice(1))) ensureInit("bosc");

// Installable app: cache the app files so it opens offline (the call itself still needs internet).
if ("serviceWorker" in navigator && window.isSecureContext) {
  window.addEventListener("load", () => navigator.serviceWorker.register("sw.js").catch(() => {}));
}
