# Frontend: complete guide

Goal: a calm, reliable app that runs the whole demo on two real phones:
1. a call between the phones;
2. a live transcript;
3. a risk meter;
4. a warning;
5. a paused payment.
It must work 5 times in a row, with fallbacks if anything breaks on stage. This folder **is** the deployed website: static files with no build step.

Contents
1. What already exists (file by file)
2. Setup and running
3. How the app works (architecture)
4. Deployment (HTTPS)
5. Phone testing: protocol and test cases
6. Performance: speech-to-text speed
7. Work items (prioritised)
8. Integrating Backend's new detector (async)
9. UX and copy rules
10. Privacy rules the UI must keep
11. Demo operations: checklist and fallback ladder
12. Contracts with the other teams
13. Checklist, timeline and done criteria

---

## 1. What already exists

| File | Owner | What it does |
|---|---|---|
| `index.html` | Frontend | Every screen in one page. Views: `#home`, `#caller`, `#bosc`, `#pay`, `#summary`. Overlays: incoming call, payment paused, settings. Loads PeerJS 1.5.4 from jsDelivr. |
| `css/app.css` | Frontend | Mobile-first styles, max width 520 px. Colour tokens on `:root`, dark mode via `prefers-color-scheme`. Risk colours: safe green, warn amber, danger red. |
| `js/app.js` | Frontend | Hash routing, settings, caller (Phone A), BOSC (Phone B), sessions, risk UI, Shield payment logic, call summary. |
| `js/audio.js` | Frontend | `UtteranceChunker`: any audio node → 16 kHz mono → energy voice-activity detection → utterance chunks (`Float32Array`). |
| `js/asr-worker.js` | Frontend | Whisper in a module Web Worker (Transformers.js **3.0.2**): WebGPU if available, else CPU (wasm). |
| `demo/scripts.json` | **Data & Evaluation** | Six scripts (3 scam, 3 genuine) for the caller's teleprompter and the text-only demo. |
| `js/bosc-core.js` | **Backend**, don't edit | The Stage 0 detector: `loadScorer`, `StreamingScorer`, `TacticDetector`, `Model`. |
| `model/model.json`, `model/tactics.json` | **Backend**, don't edit | Weights, thresholds, tactic patterns. |

### Key behaviour already implemented
- **Call (WebRTC via PeerJS):**
  - Phone B registers the peer ID `bosc-ait26-<4-digit code>` on the free PeerJS cloud server and shows the code. If the ID is taken, it picks a new code.
  - Phone A dials that ID with its mic stream. Phone B answers with its own mic, so both people can talk.
  - On Phone B, the remote audio goes to an `<audio>` element (needed in Chrome) **and** into Web Audio → chunker → Whisper.
- **Sources on Phone B:** call · this phone's mic · replay an audio file · demo script (text only) · typed line.
- **Speech-to-text queue:**
  - Chunks wait in `pending` (capped at 30 s of audio).
  - While Whisper is busy, waiting chunks are merged into one request of ≤ 14 s.
  - Junk outputs ("[BLANK_AUDIO]", "Thank you.", "you", …) are dropped.
  - Each transcript line shows "speech-to-text X ms for Y s".
- **Voice-activity settings** (`audio.js`):
  - 30 ms frames; speech threshold = max(mic sensitivity, 3 × noise floor);
  - an utterance ends after 650 ms of silence; maximum 8 s; minimum 0.4 s; 210 ms of audio kept from before speech starts.
- **Risk display:**
  - Raw risk is mapped to a 0–100 display scale: 0–50 = safe, 50–70 = caution (from WARN), 70–100 = high risk (from PAUSE).
  - 10-segment meter, level word, one-line advice that depends on the tactics seen, tactic chips, highlighted words in the transcript.
  - Vibration, and a spoken warning on each escalation (once per level per call).
- **Shield (PayFast screen).** Hand-set logistic score:
  ```
  z = −4.0 + 1.2·onCall + 5.0·callRisk(0–1 display) + 0.8·newPayee
      + 0.9·clamp(log10(amount / usual), 0, 3) + 2.2·screenShare + 1.0·payeeLooksLikeSafeAccount
  HIGH if p ≥ 0.70 or call level = PAUSE;  MEDIUM if p ≥ 0.35 or call level = WARN;  else LOW
  ```
  - LOW → pay.
  - MEDIUM → inline warning and a 10 s countdown before Pay works.
  - HIGH → full-screen **Payment paused** with reasons. The buttons are Cancel, Call trusted contact, Wait 60 s, and "I trust this call, pay anyway" (logged).
  - Screen sharing is a **simulated toggle**, because browsers can't detect it.
- **Summary:** verdict, an SVG risk-over-time chart with WARN/PAUSE lines, the tactics seen, 1930 and cybercrime.gov.in links, and "Download session log" (JSONL of text and scores, no audio).
- **Settings** (in `localStorage` key `bosc-settings`, read inside try/catch):
  - Whisper model (tiny.en / base.en) and a force-CPU option;
  - trusted contact name and number;
  - usual payment size;
  - mic sensitivity (0.004 / 0.008 / 0.015);
  - read warnings aloud;
  - the peer code.

---

## 2. Setup and running
```bash
git clone https://github.com/amdravidranjan/BOSC && cd BOSC && git checkout -b frontend
npm run serve            # http://localhost:8080, serving frontend/ with caching off
```
- Use **Chrome** (desktop and Android). Microphone access needs `localhost` or HTTPS.
- The first visit to the BOSC screen downloads Whisper (~40 MB tiny, ~80 MB base). The browser caches it, so later loads are fast.
- No build tools, so keep it that way. Load external scripts only from jsDelivr with exact versions (as now: `peerjs@1.5.4`, `@huggingface/transformers@3.0.2`).
- Debugging on a phone: connect it by USB, enable USB debugging, then open `chrome://inspect` on the laptop.

---

## 3. How the app works

```
Phone A (caller)                        Phone B (BOSC)
mic ──WebRTC audio──────────────────▶  <audio> (you hear it)
                                        └─▶ Web Audio ─▶ UtteranceChunker (16 kHz, VAD)
                                                          └─▶ asr-worker (Whisper) ─▶ text
                                                                └─▶ scorer.push(text) ─▶ risk, level, tactics
                                                                      ├─▶ meter, chips, warning, transcript
                                                                      └─▶ Shield (PayFast) on Pay
PeerJS cloud: signalling only (sets up the call; no audio passes through it)
```
- **Sessions:** `startSession(source, stop)` resets the scorer and the UI. `endSession(reason)` stops audio, clears pending chunks, saves `lastSession`, and opens the summary.
- **One session at a time.** Starting a new one ends the old one.
- **Routing:** `location.hash` → `show(view)`. Each view's init runs once (`inited` set). The BOSC view also initialises the scorer, peer and Whisper. Pay and Summary rely on BOSC being initialised (handled at boot).

---

## 4. Deployment (HTTPS)

**Netlify (recommended):**
1. app.netlify.com → Add new site → Import from Git → `amdravidranjan/BOSC`.
2. Build command: *(empty)*. **Publish directory: `frontend`**.
3. Deploy, then share the `https://<name>.netlify.app` link with the team. Every push to `main` redeploys.
4. Optional: rename the site to something short like `bosc-demo`.

**GitHub Pages** (alternative): Pages serves the repo root or `/docs`, so add a small GitHub Actions workflow that publishes `frontend/`. Only do this if Netlify isn't possible.

**After each deploy:** open the link on both phones, open the BOSC screen once on **Wi-Fi** (model download), and check that "Speech model ready" appears.

---

## 5. Phone testing: protocol and test cases

### 5.1 Devices
- Test on **the exact two phones used on stage**, plus one low-end Android if available.
- For each phone, record the model, Android version and Chrome version (`chrome://version`) in `data-eval/results/latency.md`.

### 5.2 Network
- Use a **phone hotspot** from a third phone. Venue Wi-Fi often blocks phone-to-phone WebRTC.
- **The call needs internet**, because the PeerJS signalling server is online. Whisper and the detector don't, once cached. So:
  - the **airplane-mode** demo only works for "This mic", "Replay" and "Demo script";
  - the call itself needs the hotspot to have mobile data.

### 5.3 Test cases (run all of them; log pass/fail in `data-eval/results/frontend_tests.md`)

| # | Case | Expected |
|---|---|---|
| T1 | Phone B opens BOSC | Code shows; "Waiting for a call"; "Speech model ready … GPU/CPU" |
| T2 | Phone A dials the wrong code | Toast "No BOSC phone is waiting with that code" |
| T3 | Phone A dials the right code; Phone B answers | Both hear each other; Phone B state "In call"; level bar moves when A speaks |
| T4 | A reads the "Digital arrest" script | Transcript appears 1–3 s after each line; meter reaches High risk by line 4–5; chips light up; warning read aloud |
| T5 | During T4, B taps Pay → Pay ₹50,000 | **Payment paused** with reasons |
| T6 | Pause screen buttons | Cancel → back to BOSC; Wait 60 s → countdown, then Pay allowed; "I trust this call" → paid (simulated), logged |
| T7 | A reads "Delivery OTP" (genuine) | Stays Safe/green; Pay ₹1,500 to "Ramesh Kumar" goes through (LOW) |
| T8 | A hangs up | B ends the session and shows the summary with the risk chart |
| T9 | B hangs up | A's call ends; A returns to Ready |
| T10 | Phone B screen turns off mid-call | Note what happens; it should keep listening (the wake lock is requested), but check this |
| T11 | Second call without reloading | Works; risk starts again from 0 |
| T12 | "This mic": a laptop plays `data-eval/audio_test/kyc_otp.wav` near Phone B | Transcript + High risk |
| T13 | "Replay an audio file" with the same WAV on Phone B | Same result, ends with the summary |
| T14 | "Demo script" with no network (airplane mode, after first load) | Works fully offline |
| T15 | Mic permission denied on B | Toast; answering still works listen-only |
| T16 | Echo test: both on speaker, same room | Does B's transcript pick up B's own voice? If yes → earphones on one phone |
| T17 | Settings: add a trusted contact, then tap "Call family" | Phone dialler opens with the number |
| T18 | Dark mode phone | Readable, with correct contrast |
| T19 | Font size set to "Large" in Android settings | Layout doesn't break |
| T20 | 10-minute call | No crash; memory stable (check in Chrome remote devtools) |

---

## 6. Performance: speech-to-text speed

- **Metric:** each transcript line shows `speech-to-text X ms for Y s`. Real-time factor (RTF) = X / (Y × 1000). **RTF < 1 means the app keeps up.** Measured so far: laptop GPU ≈ 0.25–0.45.
- **Per phone,** record the median RTF over 20 utterances for tiny.en and base.en, and whether it ran on "GPU" or "CPU" (shown in the model-ready line). Save the numbers to `data-eval/results/latency.md`.
- **If RTF > 1:**
  1. use tiny.en, not base.en;
  2. check `chrome://gpu` (WebGPU status) and update Chrome;
  3. use a faster phone as Phone B;
  4. fall back to laptop speech-to-text (the backup demo).
- **Don't** switch to the browser's built-in `SpeechRecognition`. It sends audio to Google's servers (breaking the privacy promise) and can't listen to the incoming call audio.

---

## 7. Work items (in priority order)

**P0, needed for the demo**
1. Deploy (§4); run T1–T9 on the real phones; fix whatever fails.
2. **Echo:** if T16 fails, test `echoCancellation: true` on both sides (already set for calls) plus earphones. Document what works.
3. **Screen-off:** if T10 fails, show a "Keep this screen on during calls" banner and request the wake lock again when the page becomes visible (`visibilitychange`).
4. **First-run setup:** on the first BOSC visit, a 2-step sheet for the trusted contact (name + number) and usual payment size. It writes to `settings`; skip if already set.
5. **Backup demo video:** a screen recording of a perfect run on both phones.

**P1, quality**
6. **Pause-screen copy review** with Data & Evaluation (§9). Big text, calm tone, one sentence per idea.
7. **Large-text mode** in Settings: `html { font-size: 112.5% }` and bigger meter/word sizes.
8. **Status clarity:** show "Detector: basic / advanced" (needed after the ONNX integration, §8) and the Whisper model name in the status line.
9. **Summary:** mark the moment Pay was attempted on the risk chart (a vertical line). This is a strong slide visual.
10. **Replace `ScriptProcessorNode` with an `AudioWorklet`** (ScriptProcessor is deprecated but works in Chrome). Only do this if time allows, and re-run T3, T4 and T12 after changing it.

**P2, nice to have**
11. **Installable app (PWA):** `manifest.json` + a service worker caching `frontend/` files, so the app opens offline. Don't cache the call; PeerJS needs the network.
12. **Caller ID on the incoming-call sheet:** let Phone A type a display name and send it via `peer.connect()` metadata.
13. **Haptic patterns and sound** for WARN vs PAUSE, tested with seniors.
14. **Split `app.js`** into modules (`caller.js`, `bosc.js`, `shield.js`, `summary.js`). Do it only before the integration work starts or after the freeze, never in the middle, to avoid merge pain.

---

## 8. Integrating Backend's new detector (async)

When Backend delivers `js/detector-onnx.js` (their guide, §9):
- `loadScorer()` returns the same object, **but `scorer.push(text)` returns a Promise**.
- The result keeps `p, raw, risk, level, tactics` and adds `tacticProbs` (model probabilities per tactic) and `libMax` (similarity to known scam scripts).

Changes in `app.js`:
1. Import from `./detector-onnx.js`, with a fallback to `./bosc-core.js` if loading fails. Show "Detector: advanced / basic".
2. `addUtterance` must become `async` and `await scorer.push(text)`.
3. **Keep utterances in order.** Put incoming text in a queue and process one at a time (two fast Whisper results must not race).
4. Where `core.scorer.push` is used synchronously (typed lines, demo script), go through the same queue.
5. Optional UI: show "matches a known scam script (87%)" when `libMax` is high (Backend gives the threshold).
6. Re-run T4, T5, T7, T11 and T14. Backend's parity tests cover the numbers; you cover the UI.

---

## 9. UX and copy rules (main users: seniors and first-time digital users)

- **Calm, not scary.** Say "Payment paused" rather than "FRAUD DETECTED". Never accuse the caller on screen.
- One idea per sentence, ≤ 15 words. Use plain verbs: "Hang up", "Call your daughter", "Don't share your OTP".
- Name the **specific tactic** when known, for example "Real police never arrest anyone over a video call". The advice text in `advice()` in `app.js` is the source; edit it together with Data & Evaluation.
- Big touch targets (≥ 48 px), high contrast, never colour alone (always a word: Safe / Caution / High risk).
- The user always stays in control: every block has a way through ("I trust this call"). Forced blocking annoys genuine users, and fintechs will ask about it.
- The spoken warning uses `en-IN` at rate 0.95. Keep it short.

---

## 10. Privacy rules the UI must keep

- No audio is ever stored, uploaded or downloaded. The session log contains **text and scores only**.
- Listening only during an active session. The status pill and privacy line must always show when the mic is in use.
- No analytics or third-party scripts beyond the two pinned CDN libraries and the Hugging Face model download.
- `localStorage` holds settings only, never transcripts.
- Any new feature that sends data off the phone needs team agreement and a line on the privacy slide.

---

## 11. Demo operations

### Checklist (morning of the demo)
- [ ] Both phones charged above 80%, chargers on the table, Battery Saver **off**, Chrome battery set to unrestricted.
- [ ] Do Not Disturb on (calls allowed), notifications silenced, auto-rotate off, brightness up, screen timeout at least 10 min.
- [ ] Hotspot phone with data, both phones connected, the app link opened once with "Speech model ready".
- [ ] Phone B Settings: trusted contact set, Whisper tiny, "Read warnings aloud" on, mic sensitivity tested in the room.
- [ ] Earphones ready for echo; speaker volume tested in the actual room.
- [ ] Laptop with `npm run serve` and the backup video ready to play.
- [ ] Both team members know the hand signal for "switch to fallback".

### Fallback ladder (decide in advance which step to drop to)
1. **Live two-phone call** (best).
2. **"This mic" on Phone B:** a teammate speaks the scam lines near Phone B, or the laptop plays `kyc_otp.wav`.
3. **"Replay an audio file"** on Phone B.
4. **"Demo script"** (text only; works offline).
5. **Backup video.**

Say plainly on stage which step is running. Judges accept a fallback that's explained, and distrust one that's hidden.

---

## 12. Contracts with the other teams

- **Backend:** owns `js/bosc-core.js`, `js/detector-onnx.js` (coming), and `model/`. Don't edit them. Report bugs to Backend. The detector API is described in §8 and `backend/README.md` §11.
- **Data & Evaluation:**
  - owns `demo/scripts.json` (format: `{id, title, kind: "scam"|"genuine", lines: [...]}`);
  - reviews all user-facing copy;
  - uses the session logs from your real-audio runs (Summary → Download session log) for the real-audio test set.
- Branch `frontend` → pull request into `main`. Ask for one review from another team for anything that touches `index.html` structure or the detector calls.

---

## 13. Checklist, timeline and done criteria

**Checkpoint 1**
- [ ] Deployed; T1–T9 pass on the real phones
- [ ] RTF per phone recorded; Whisper size chosen
- [ ] Echo and screen-off solved or documented

**Checkpoint 2**
- [ ] First-run setup, copy review, large text, Pay marker on the summary chart
- [ ] Backend's ONNX detector integrated (async queue) with the fallback; T4, T5, T7, T11, T14 re-run
- [ ] T10–T20 run, results logged

**Freeze (Day −2):** no new features after this.
- [ ] Backup video recorded
- [ ] 5 full demos in a row without a failure, on the stage phones, over the hotspot

**Done when** the live demo (call → transcript → meter → warning → payment paused, then a genuine call that stays green) runs **5 times in a row** on the stage phones, and every fallback step has been rehearsed at least once.
