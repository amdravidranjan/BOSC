# BOSC for Android: real calls, on-device scam detection, payment pause

Native Android version of BOSC. Two phones on the same Wi-Fi place a **real two-way voice call inside the Bosc app**. The receiving phone transcribes the caller **on the device** with Whisper, scores the conversation with the **team's trained Stage 0 model** plus multilingual rules, and stops a payment made under pressure. Audio never leaves the phone.

## Why the call runs inside Bosc

We tested this on a Galaxy S24 (Android 17) with a mic-level probe. During a SIM call, and during a WhatsApp call, Android gives every ordinary app **pure digital silence (−120 dBFS)**. Normal apps can't hear phone calls. So Bosc places the call itself, over the local Wi-Fi. Because it owns both audio streams, it hears the caller clearly and knows who said what.

The payment SDK doesn't need audio at all. It uses device signals (on a SIM or WhatsApp call, new payee, unusual amount, screen-sharing apps), plus Bosc's call-pressure score when one is available.

## What's in this folder

| Module | What it is |
|---|---|
| `app/`: **Bosc** (`com.bosc.guardian`) | Wi-Fi contact sync and calling, scam listening (Whisper + detector), risk meter, highlighted transcript, calm full-screen pause with a spoken warning in English, Hindi or Tamil, after-call summary with report links (1930 / cybercrime.gov.in) |
| `demo-pay/`: **PayFast** (`com.bosc.payfast`) | Mock payment app (no real money) that calls `Bosc.evaluate()` before paying: LOW pays, MEDIUM waits 10 s, HIGH shows "Payment paused" |
| `bosc-sdk/` | The library a fintech would embed: call state (SIM vs internet call), speakerphone, remote-access apps, accessibility services, amount z-score, new payee, payee keywords, Bosc pressure score → `RiskDecision(level, score, reasons, signedToken)` |
| `core/` | Pure Kotlin: **port of `backend/bosc_core.py`** (Stage 0), multilingual rules, combined detector, streaming risk, evaluator, demo calls |

## Results so far (honest)

**Stage 0 parity with the team's Python model.** `Stage0ParityTest` runs `backend/parity_cases.json`:
- 204 probabilities: max difference 1.7e-16;
- 204 tactic sets: 0 mismatches;
- streamed call: max difference 1.1e-16.

The phone runs exactly the same model as the web app. To ship a retrained model, replace `core/src/main/resources/bosc/stage0/model.json` and `tactics.json`.

**Speech-to-text on the phones.** Measured on the repo's `data-eval/audio_test` recordings (123 words), scored against the script text:

| Phone | Whisper tiny.en (sherpa-onnx, int8) | Vosk small en-in |
|---|---|---|
| Galaxy S24 | **6.5% WER**, 0.06–0.18 s compute per second of audio | 26.0% WER |
| Nothing Phone (2a) | **6.5% WER**, 0.12–0.14 s compute per second of audio | 25.2% WER |

Vosk splits key words ("k y c", "l t p" for KYC and OTP); Whisper keeps them intact. Whisper is the default.

**Detectors on our 17 scripted demo calls.** These calls were written alongside the rules, so treat this as a smoke test, not accuracy:

| Detector | Scams paused (of 8) | Genuine calls paused (of 9) | False warnings |
|---|---|---|---|
| Team Stage 0 only | 4 (misses the Hinglish, Hindi and Tamil-English calls; the model is English-only) | 0 | 1 |
| Rules only | 7 (misses the polite red-team scam) | 0 | 0 |
| **Combined (default)** | **8** | **0** | 1 |

**First real two-phone call** (Wi-Fi, same room): the S24 reached **PAUSE about 22 s into a digital-arrest script**. Live audio transcribes much worse than clean recordings ("cyber crime branch" became "Cybertron brands"). That led to the latest fixes: caller-only scoring, room-echo de-duplication, automatic gain before voice detection, and the screen turning off at the ear. **Headline numbers must come from the human-written lockbox and real-audio sets in `data-eval/`.**

## Build and install

Needs JDK 17 and the Android SDK (platform 34, build-tools 34).

```bash
cd android
bash scripts/fetch_models.sh          # downloads ~190 MB of speech files (not in Git)
echo "sdk.dir=/path/to/android/sdk" > local.properties
./gradlew :core:test assembleDebug    # runs the parity tests, builds both APKs
adb install -r app/build/outputs/apk/debug/app-debug.apk          # install Bosc FIRST
adb install -r demo-pay/build/outputs/apk/debug/demo-pay-debug.apk
```

Install Bosc before PayFast: PayFast needs the `READ_CALL_PRESSURE` permission that Bosc defines.

## Two-phone demo

1. Both phones on the **same Wi-Fi** (a phone hotspot works well; office Wi-Fi often blocks discovery). Open Bosc on both.
2. Phones should find each other automatically. If not, tap **Add a phone** on one phone and type the address shown on the other. Both phones save each other as contacts.
3. On the "victim" phone, keep **Scam listening** on. On the "scammer" phone, tap the contact → **Call** → accept on the victim phone.
4. Speak a scam into the scammer phone. The victim phone transcribes the caller, underlines pressure phrases, the meter climbs, and at PAUSE a calm full-screen warning appears and is read aloud.
5. Hang up, open **PayFast** on the victim phone, and pay a new payee a large amount. You get **"Payment paused. Is someone on a call telling you to send money?"**, with "Bosc heard pressure on your call" as one of the reasons.

There's no second phone? Use **Demo tools → Preview on this phone**: it runs the same detector, alerts and SDK signal on a scripted call.

## Developer tools

- **Speech benchmark:** push `name.wav` + `name.txt` pairs to `Android/data/com.bosc.guardian/files/bench/`, then run `adb shell am start -n com.bosc.guardian/.MainActivity --ez bench true`. WER and real-time factor for Whisper and Vosk go to logcat (`BoscBench`).
- **Debug audio capture** (Settings, off by default): saves the audio received in a Bosc call as a WAV, for the team's own test calls only. Delete after measuring.
- **Evidence log:** `Android/data/com.bosc.guardian/files/logs/call_*.jsonl`, one line per utterance in the shared event format `{t, speaker, text, tactics, window_risk, call_risk, level}`. No audio.
- **Settings:** detector (combined / Stage 0 / rules), speech engine (Whisper / Vosk), call mic mode.

## Known limits

- **Bosc calls only:** Android blocks call audio for third-party apps, so SIM and WhatsApp calls can't be transcribed. In production this would ship inside a phone maker's or carrier's dialer; the payment SDK works today with no audio.
- **Call quality:** the call audio is plain PCM over TCP on the local network, unencrypted. A product would use Opus and SRTP.
- **Language:** the Stage 0 model is English-only. The rules add Hinglish, Hindi and Tamil-English patterns. Hindi and Tamil warning text needs a native speaker's check.
- **Placeholders:** Play Integrity (screen sharing) is mocked. The signed risk token shows the idea only; production needs server-side verification.
- **Accuracy:** real-audio accuracy isn't measured yet beyond the first test calls.

Third-party components: sherpa-onnx (Apache-2.0), Whisper tiny.en ONNX export by k2-fsa (MIT model), Silero VAD (MIT), Vosk + vosk-model-small-en-in (Apache-2.0).
