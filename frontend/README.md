# Frontend: the BOSC web app

This folder **is** the deployed site (static files, no build step).

## Files
| File | What it does | Owner |
|---|---|---|
| `index.html` | All screens: home, caller, BOSC, PayFast, summary, overlays | Frontend |
| `css/app.css` | Styles (light/dark, mobile-first) | Frontend |
| `js/app.js` | Routing, WebRTC call (PeerJS), sessions, risk UI, Shield payment logic, summary | Frontend |
| `js/audio.js` | Audio → 16 kHz → voice-activity chunks | Frontend |
| `js/asr-worker.js` | Whisper (tiny.en / base.en) in a Web Worker via Transformers.js 3.0.2 | Frontend |
| `demo/scripts.json` | Teleprompter + demo scripts | Data & Evaluation |
| `js/bosc-core.js`, `model/` | The detector and its weights | **Backend**, don't edit |

## Run
```
npm run serve     # from the repo root → http://localhost:8080
```
Phones: deploy to HTTPS (Netlify → Import from Git → publish directory `frontend`).

## How the demo works
1. **Phone B:** "Protected phone (BOSC)" shows a 4-digit code; wait for "Speech model ready".
2. **Phone A:** "Caller", enter the code → Call. Phone B → Answer.
3. Phone A reads script lines. Phone B shows the transcript, meter, chips and warning.
4. Phone B → Pay ₹50,000 during the call → **Payment paused**.
5. Repeat with a genuine script, which should stay green.

Fallbacks on Phone B: **This mic**, **Replay an audio file**, **Demo script**, typed lines.

## To do
- [ ] Deploy to Netlify; share the link.
- [ ] Two-phone test on the real demo phones: connection over a hotspot, echo (earphones on one phone), mic permissions, screen-off behaviour.
- [ ] Measure Whisper speed per phone (each transcript line shows "speech-to-text X ms for Y s"; X must stay < Y). Choose tiny or base.
- [ ] First-run setup: trusted-contact name/number, usual payment size.
- [ ] Senior-friendly polish: large text mode, warning read aloud, calmer pause-screen copy (review with Data & Evaluation).
- [ ] Laptop fallback (the plan's fallback ladder) and a recorded backup demo video.
- [ ] **When Backend ships the ONNX detector:** change `addUtterance` to `await scorer.push(text)` and queue utterances so they're processed in order; show `tacticProbs` if useful; keep the Stage 0 fallback.
- [ ] Done when: the full demo (call → meter → pause) works 5 times in a row on the real phones.

## Notes
- Chrome only feeds a remote WebRTC stream into Web Audio if it's also attached to an `<audio>` element (already handled).
- PeerJS uses its free cloud signalling server and default STUN/TURN. Venue Wi-Fi may block it, so use a hotspot.
- The microphone needs HTTPS or localhost.
