# Data & Evaluation

Owns the test sets that produce the **headline numbers**, the demo scripts, the results, and the material for the slides.

## Folder contents
| Path | What | In Git? |
|---|---|---|
| `raw/` | BothBosu/scam-dialogue CSVs (download command in `backend/README.md`) | No |
| `generated/` | Template calls from `backend/gen_calls.py` | No (regenerate) |
| `v1/` | Final training/dev/test data built by Backend (LLM + template + public) | Only if < 50 MB, otherwise Drive + hash |
| `lockbox/` | **Human-written test calls. Never trained on.** | Yes, read-only |
| `redteam/` | Calls designed to break the model | Yes |
| `realaudio/` | Transcripts of role-played recorded calls (no audio files) | Yes |
| `audio_test/` | Two TTS test recordings for the app's file-replay mode | Yes |
| `results/` | Reports, metrics, figures, screenshots | Yes |

## Call format (the contract with Backend)
One call per line, JSONL:
```json
{"id":"lb-001","source":"lockbox","label":1,"type":"S2_digital_arrest","first_suspicious_caller_turn":1,
 "turns":[{"speaker":"caller","text":"...","tactics":["authority_claim"]},
          {"speaker":"receiver","text":"...","tactics":[]}]}
```
- `label`: 1 for a scam, 0 for a genuine call. Genuine calls use `"first_suspicious_caller_turn": null`.
- `first_suspicious_caller_turn` counts **caller turns only**, from 0.
- Type IDs: S1–S10 (scam) and G1–G9 (genuine); the table is in `backend/README.md` §4.
- `tactics` per caller turn are optional in the lockbox, but help the tactic metrics.

## To do
- [ ] **Lockbox:** each member writes 40 scam + 40 genuine calls **in their own words**: messy speech, fillers, half sentences.
  - At least 40 hard negatives (delivery OTP, real bank alert, real police verification, a family member asking for money, someone describing a scam).
  - At least 20 scams with no obvious keywords (polite, slow).
  - Whoever writes lockbox calls must **not** have seen the LLM prompts used for training data.
  - Save to `lockbox/lockbox_v1.jsonl`, record its SHA-256 in `results/lockbox_log.md`, and don't edit it afterwards.
- [ ] **Red team:** 150 scam + 100 genuine, aimed at weak spots (paraphrases, misspellings, scary-sounding genuine calls).
- [ ] **Real audio:** 40 scam + 40 genuine role-played calls through the demo setup, with everyone's consent. Keep only the app's transcripts (Summary → "Download session log") plus the clean script for each, and delete the audio.
- [ ] **Review LLM data:** read a 5% sample of Backend's generated calls (10% of hard negatives) and flag bad ones.
- [ ] **Demo scripts:** keep `frontend/demo/scripts.json` realistic. After any edit, run `python backend/check_scripts.py` to see each script's risk curve.
- [ ] **Slides:**
  - results slide: ablation table, risk-over-time plot, 3 numbers with confidence intervals and one line on limits;
  - privacy slide;
  - limits slide;
  - disclosure of pre-trained models and data;
  - a 3-minute pitch, rehearsed 3 times.
- [ ] **Error analysis:** read every lockbox miss and false alarm (Backend produces the list) and group them in `results/errors.md`.

## Current results
`results/report.md`, Stage 0, synthetic sets only: 98% recall / 9% false alarm on held-out phrasing. **Do not present these as headline numbers.**
