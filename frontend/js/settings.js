// User settings, kept in localStorage under "bosc-settings" (settings and saved voice-prints only, never transcripts).

export const DEFAULTS = {
  model: "onnx-community/whisper-tiny.en", forceWasm: false,
  myname: "",          // how the guardian sees this phone ("Amma")
  tname: "", tnum: "", // trusted contact = guardian (name, phone)
  avg: 1500, sens: 0.008, speak: true, sound: true, large: false, setupDone: false, code: "",
  side: "both",        // "caller" | "both" | "me" (Android mode: only this phone's mic)
  voiceCheck: true,    // synthetic-voice and trusted-voice checks on the caller's audio
  voices: [],          // trusted voice-prints [{name, relation, print, at}]
};

export const settings = { ...DEFAULTS };
try { Object.assign(settings, JSON.parse(localStorage.getItem("bosc-settings") || "{}")); } catch {}
if (!["caller", "both", "me"].includes(settings.side)) settings.side = "both";

export function saveSettings() {
  try { localStorage.setItem("bosc-settings", JSON.stringify(settings)); } catch {}
}

export function applyLargeText() {
  document.documentElement.classList.toggle("large", !!settings.large);
}
