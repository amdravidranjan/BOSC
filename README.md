# BOSC: Bane of Scam Calls

On-device scam-call detection that pauses a payment made under pressure. Hackathon build: one web app, English only.

On `radha-v2`, [v2 preparation and diagnosis](data-eval/results/v2_report.md)
records the label audit, candidate loss correction and phone-ASR tooling.
New corpus generation, review and retraining are pending; no v2 release is claimed.

Stages 1–4 now have an [available-data training workflow](backend/TRAINING.md) and
[synthetic evaluation report](data-eval/results/stages_1_4.md). The run uses
BothBosu/scam-dialogue plus the repository's templates, rather than the full
dataset collection described in the roadmap. The student missed the release F1
gate, so Stage 0 remains the active browser detector; trained artifacts and
failed gates are retained for review.

## Repository layout: three teams, three folders

| Folder | Team | What's inside | Guide |
|---|---|---|---|
| [`frontend/`](frontend/README.md) | **Frontend** | The web app (deploy root): caller + BOSC phone, WebRTC call, in-browser Whisper, risk meter, PayFast payment pause | [frontend/README.md](frontend/README.md) |
| [`backend/`](backend/README.md) | **Backend / ML** | Detector training, data pipelines, Stage 0→4 models, export to the browser, parity tests | [backend/README.md](backend/README.md) |
| [`data-eval/`](data-eval/README.md) | **Data & Evaluation** | Datasets, human-written lockbox, real-audio tests, results, slides material | [data-eval/README.md](data-eval/README.md) |

Who owns what (to avoid merge conflicts):
- **Frontend** owns everything in `frontend/` **except** `frontend/model/` and `frontend/js/bosc-core.js` (and later `frontend/js/detector-onnx.js`), which **Backend** writes.
- **Data & Evaluation** owns `data-eval/` and `frontend/demo/scripts.json` (the demo scripts).
- **Backend** owns `backend/`, and writes generated datasets into `data-eval/` and results into `data-eval/results/`.

Branches: `frontend`, `backend`, `data-eval` → pull request into `main`.

## The two contracts between teams
1. **Detector API** (Backend → Frontend): `loadScorer()` → `scorer.push(text)` returns `{p, raw, risk, level, tactics}`; also `reset()`, `seen`, `warn`, `pause`, `history`, `level()`; `detector.labels`. When the ONNX model lands, `push` becomes async (`await`). Details in `backend/README.md` §11.
2. **Call data format** (Data & Evaluation → Backend): JSONL, one call per line. Details in `data-eval/README.md`.

## Quick start
```
npm run serve        # serves frontend/ at http://localhost:8080 (the mic works on localhost)
npm run parity       # browser detector == Python detector
```
Phones need HTTPS: deploy `frontend/` (Netlify: "Import from Git", publish directory `frontend`). Use a phone hotspot at the venue.

## Honest limits
- A browser can't see real phone calls, so the call runs through our own web app. Screen-sharing detection is simulated (a toggle).
- English only. Speech-to-text adds about 1–2 s per utterance on a laptop GPU; phones on CPU will be slower.
- Current numbers are on synthetic data only (`data-eval/results/report.md`). The headline must come from the lockbox and real recordings.
