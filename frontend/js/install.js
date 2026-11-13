// "Install app" (PWA): Chrome/Edge/Android show their own prompt via beforeinstallprompt;
// iOS Safari needs the Share → "Add to Home Screen" steps.
import { $, toast } from "./util.js";

let deferred = null;

export function initInstall() {
  const btn = $("#install-btn");
  if (!btn) return;
  const standalone = window.matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
  if (standalone) { btn.hidden = true; return; }
  const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) && !window.MSStream;
  window.addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault();
    deferred = e;
    btn.hidden = false;
  });
  window.addEventListener("appinstalled", () => { btn.hidden = true; deferred = null; toast("BOSC is installed. Open it from your home screen."); });
  btn.onclick = async () => {
    if (deferred) {
      deferred.prompt();
      const { outcome } = await deferred.userChoice;
      if (outcome === "accepted") btn.hidden = true;
      deferred = null;
      return;
    }
    $("#install-help").hidden = false;
    $("#install-help-text").textContent = ios
      ? "On iPhone: tap the Share button in Safari, then “Add to Home Screen”."
      : "In Chrome: open the ⋮ menu and choose “Install app” (or “Add to Home screen”). The app then opens full-screen, like a native app, and works offline after the first visit.";
  };
  // show the button on browsers that support installation but have not fired the event yet (Chrome needs a visit or two)
  if (ios || "onbeforeinstallprompt" in window) btn.hidden = false;
  $("#install-help-close").onclick = () => { $("#install-help").hidden = true; };
}
