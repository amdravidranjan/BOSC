# BOSC: Bane of Scam Calls (hackathon build)

One web app, two roles:
- **Caller (Phone A)** places a WebRTC call (PeerJS) to the protected phone and has script prompts.
- **BOSC (Phone B)**: Guardian transcribes the caller's audio **in the browser** (Whisper tiny/base English, Transformers.js, WebGPU or CPU), scores it, shows the risk meter, tactic chips and warning. Shield runs inside the same app: the PayFast screen pauses a payment made during a risky call.

Fallbacks on Phone B: **This mic** (call on speaker, or audio played from a laptop), **Replay an audio file**, **Demo script** (text only), and a typed line box.

## Run locally
```
npm run serve        # http://localhost:8080 (localhost counts as secure, so the mic works)
```
Phones need **HTTPS**. Deploy the `web/` folder as a static site, for example to Netlify (drag `web/` into app.netlify.com/drop) or GitHub Pages.
Use a **phone hotspot** at the venue. The first load downloads the speech model (~40 MB, cached afterwards).

## Model
```
# one-time: download the public dataset (not committed)
mkdir -p data/raw
for f in train test; do curl -L -o data/raw/scam-dialogue_$f.csv https://huggingface.co/datasets/BothBosu/scam-dialogue/resolve/main/scam-dialogue_$f.csv; done

cd train
python gen_calls.py ../data/generated     # template calls; test/dev use held-out phrasings
python train.py                           # grid search on dev, eval, ablations, export web/model/model.json
node parity_test.mjs                      # JS core must equal Python core
python check_scripts.py                   # risk trajectory of each demo script
```
- Detector: TF-IDF (word 1–2 grams, 6k features) + logistic regression over the last N caller utterances, plus a regex tactic layer (`web/model/tactics.json`) and rise-fast/fall-slow smoothing. WARN/PAUSE thresholds are picked on dev for ≤5%/≤2% genuine-call false alarms.
- Data: our Indian-context template calls (10 scam types, 9 genuine types including hard negatives) plus `BothBosu/scam-dialogue` (Apache-2.0).
- Results: `results/report.md`. **All current test sets are synthetic. Do not headline them.**

## Lockbox (do this before the event)
Put human-written calls in `data/lockbox/*.jsonl` (one call per line). `train.py` evaluates them and never trains on them:
```json
{"id":"lb-001","label":1,"type":"S2_digital_arrest","source":"lockbox","first_suspicious_caller_turn":1,
 "turns":[{"speaker":"caller","text":"..."},{"speaker":"receiver","text":"..."}]}
```
Genuine calls use `"label":0` and `"first_suspicious_caller_turn":null`.

## Honest limits
- Browsers can't see real phone calls, so the call runs through our own web app. A browser also can't detect screen sharing, so that Shield signal is a toggle.
- English only. Whisper adds about 1–2 s of delay per utterance (laptop GPU); low-end phones on CPU will be slower, so test them early.
- Shield's weights are set by hand. A production fusion model needs real fraud outcomes from a partner.
