// "Install app" (PWA). Chrome/Edge/Android fire beforeinstallprompt once the manifest, PNG icons and the
// service worker pass the installability checks; iOS Safari needs the Share -> "Add to Home Screen" steps.
import { $, toast } from "./util.js";

let deferred = null;

export function initInstall() {
  const btn = $("#install-btn");
  if (!btn) return;
  const standalone = window.matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
  if (standalone) { btn.hidden = true; return; }
  const ua = navigator.userAgent;
  const ios = /iphone|ipad|ipod/i.test(ua) && !window.MSStream;
  const android = /android/i.test(ua);
  window.addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); deferred = e; btn.textContent = "Install BOSC on this phone"; });
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
      ? "On iPhone: tap the Share button in Safari, then \u201cAdd to Home Screen\u201d."
      : android
        ? "In Chrome: tap the \u22ee menu (top right) and choose \u201cAdd to Home screen\u201d or \u201cInstall app\u201d. BOSC then opens full-screen like a native app and works offline. If this page opened inside WhatsApp or another app, open it in Chrome first."
        : "In Chrome or Edge: click the install icon at the right end of the address bar, or open the \u22ee menu and choose \u201cInstall BOSC\u201d.";
  };
  $("#install-help-close").onclick = () => { $("#install-help").hidden = true; };
}
