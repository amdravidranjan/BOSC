// User settings, kept in localStorage under "bosc-settings" (settings only, never transcripts).

export const DEFAULTS = {
  model: "onnx-community/whisper-tiny.en", forceWasm: false, tname: "", tnum: "",
  avg: 1500, sens: 0.008, speak: true, sound: true, large: false, setupDone: false, code: "",
  side: "caller",      // "caller" = listen to the caller; "me" = Android mode (only the victim's own mic)
  voiceCheck: false,   // experimental synthetic / cloned voice check (display only; current model is not reliable)
};

export const settings = { ...DEFAULTS };
try { Object.assign(settings, JSON.parse(localStorage.getItem("bosc-settings") || "{}")); } catch {}

export function saveSettings() {
  try { localStorage.setItem("bosc-settings", JSON.stringify(settings)); } catch {}
}

export function applyLargeText() {
  document.documentElement.classList.toggle("large", !!settings.large);
}
