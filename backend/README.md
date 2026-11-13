# Backend / ML: complete guide

Goal: replace the current Stage 0 detector (TF-IDF + logistic regression) with the plan's Stage 1→4 pipeline. The final small model runs **inside the phone's browser**, and every stage is measured honestly on data it never saw. **English only** (Indian context).

Contents
1. What already exists
2. Setup
3. Definitions used everywhere
4. Data: taxonomy, datasets, LLM generation, cleaning, ASR noise, splits
5. Stage 1: embeddings + scam-library match
6. Stage 2: fine-tuned teacher
7. Stage 3: streaming call-level model
8. Stage 4: distil, ONNX, int8
9. Browser integration
10. Evaluation
11. Contracts with the other teams
12. Freeze and release
13. Checklist and targets

---

## 1. What already exists (don't redo it)

| File | What |
|---|---|
| `backend/train.py` | Stage 0: trains TF-IDF (word 1–2 grams, 6k features) + LR, grid-searches on dev, evaluates, runs ablations / cross-source / leave-one-type-out, exports `frontend/model/model.json`, writes `data-eval/results/report.md` |
| `backend/bosc_core.py` | Python scorer (tokeniser, TF-IDF, LR, regex tactics, smoothing). **Must equal** `frontend/js/bosc-core.js` |
| `backend/gen_calls.py` | Template call generator: 10 scam / 9 genuine types; phrase banks split train/dev/test (dev/test use phrasings never seen in train) |
| `backend/parity_test.mjs` | JS vs Python parity (204 strings + a streamed call) |
| `backend/check_scripts.py` | Risk curve of every demo script |
| `frontend/model/tactics.json` | 8 tactic regexes (highlighting + a scorer feature) |

Stage 0 = **ablation row B**. Regex tactics alone = **row A**. Current result (synthetic only): 98% recall / 9% false alarm @PAUSE on held-out phrasing.

Reproduce Stage 0:
```bash
mkdir -p data-eval/raw
for f in train test; do curl -L -o data-eval/raw/scam-dialogue_$f.csv \
  https://huggingface.co/datasets/BothBosu/scam-dialogue/resolve/main/scam-dialogue_$f.csv; done
cd backend
python gen_calls.py ../data-eval/generated
python train.py          # ~3 min on CPU
node parity_test.mjs     # must print PARITY OK
python check_scripts.py
```

---

## 2. Setup

**Hardware.** A GPU is needed for Stage 2 (teacher) and helps Stage 4: an NVIDIA GPU with ≥ 6 GB VRAM, or a free Colab T4 / Kaggle GPU. Stages 1 and 3 and the ONNX export run on CPU. Disk ~10 GB, RAM ≥ 8 GB free.

**Python 3.10 / 3.11:**
```bash
git clone https://github.com/amdravidranjan/BOSC && cd BOSC && git checkout -b backend
python -m venv .venv && source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r backend/requirements-ml.txt
# GPU: install the CUDA build of torch for your driver (pytorch.org)
python -c "import torch; print(torch.__version__, torch.cuda.is_available())"
```
**Tools:**
- ffmpeg, for the phone-codec simulation;
- Node.js ≥ 20, for the parity tests;
- git-lfs, optional, for files > 50 MB.

**Secrets:** `HF_TOKEN` (read-only) and the LLM API key go in `.env`, which is git-ignored. Never commit keys.

**Where things go:**
- New scripts go in `backend/` with stage prefixes:
  - data: `data_generate_llm.py`, `data_clean.py`, `data_asr.py`, `data_build_splits.py`;
  - stages: `stage1_embeddings.py`, `stage2_teacher.py`, `stage3_stream.py`, `stage4_distill.py`, `stage4_export.py`;
  - evaluation: `evaluate_all.py`.
  Put shared helpers in `backend/common.py`. Reuse `load_jsonl`, `load_hf`, `caller_utts`, `call_metrics`, `summarize` and `pick_threshold` from `train.py`.
- Large artefacts (checkpoints, embeddings, ASR audio) go in `backend/artifacts/`, which is git-ignored.
- Every run writes `backend/runs/<stage>/<YYYYMMDD-HHMM>-<name>/` with `config.json`, `metrics.json` and the git commit hash.
- Datasets go in `data-eval/v1/`, results in `data-eval/results/`, and the browser model in `frontend/model/`.
- Seeds for 3-seed runs: `13, 42, 1234`.

---

## 3. Definitions used everywhere (read twice)

- **Caller-only input.** The app transcribes only the **caller's** audio, so every model sees caller utterances only. Never put receiver turns into training windows.
- **Utterance.** One Whisper chunk, about one sentence. In training data, one caller turn = one utterance.
- **Window.** The last **4** caller utterances joined with `" | "`, newest last. Truncate from the **left** (keep the newest words): 192 tokens for the teacher, 128 for the student.
- **Window scam label.** 1 if the call is a scam **and** the utterance index ≥ `first_suspicious_caller_turn`, else 0.
- **Window tactic labels** (multi-label, newest utterance): `urgency, threat, secrecy_isolation, authority_claim, credential_request, remote_access, payment_instruction, stay_on_line`. For data without tactic labels (BothBosu), **mask** the tactic loss.
- **Streaming evaluation.** Replay each call utterance by utterance through the full scorer and record the risk after every utterance.
- **Threshold policy (never changes).** PAUSE = the lowest threshold where ≤ 2% of **dev** genuine calls ever cross; WARN = the same at ≤ 5%. Apply them unchanged to every test set. **Never tune on the lockbox.**
- **Metrics:**
  - recall @PAUSE / @WARN (scam calls that ever cross);
  - false alarm @PAUSE / @WARN (genuine calls that ever cross);
  - earliness (caller turns from the gold first suspicious turn to the first WARN);
  - before-the-ask (scams paused at or before the first OTP/payment/app request);
  - window ROC-AUC / PR-AUC;
  - tactic macro-F1.

---

## 4. Data

### 4.1 Taxonomy
**Scam types**
| ID | Type | Pattern |
|---|---|---|
| S1 | Fake bank / KYC | Account will be blocked; asks for OTP, card, UPI PIN |
| S2 | Digital arrest | Fake police/CBI/customs/court; parcel or money-laundering case; keeps victim on (video) call |
| S3 | Remote-access refund | Install AnyDesk/TeamViewer/screen share, then guides payment |
| S4 | Utility disconnection | Electricity/telecom bill; cut-off tonight |
| S5 | SIM / Aadhaar block | Fake TRAI/UIDAI; number misused |
| S6 | Card / loan offer | Upfront fee or OTP for "processing" |
| S7 | Task / investment | Paid tasks, guaranteed returns, prepaid "premium task" |
| S8 | Prize / lottery | Pay GST/fee to claim |
| S9 | Tech support | Device "infected"; fake Microsoft/Google |
| S10 | Family emergency | Relative in accident / police custody; urgent money |

**Genuine types** (★ = hard negative; ≥ 40% of genuine calls must be ★)
| ID | Type | Why it's hard |
|---|---|---|
| G1 ★ | Delivery agent asks for delivery OTP | Real OTP request |
| G2 ★ | Bank EMI / card bill reminder | Bank, due date, penalty |
| G3 ★ | Real bank fraud alert | "We blocked your card", "don't share OTP" |
| G4 ★ | Real police / court / passport verification | Official tone, legal words |
| G5 ★ | Family member asks for money | Urgency + payment |
| G6 | Telemarketing / loan sales | Pushy but legal |
| G7 | Doctor, school, landlord, plumber, service centre | Everyday time pressure |
| G8 ★ | Someone describing a scam / warning a friend | Every scam word, no scam |
| G9 ★ | Real customer support (user called them) | "Confirm registered mobile", "we never ask for OTP" |

Labelling rule: tactics mark **manipulation**, not words. A delivery agent's OTP request in G1 is not `credential_request`. Genuine calls never contain `payment_instruction` + `threat` together.

### 4.2 Datasets
| # | Source | Use | Size | Licence / caution |
|---|---|---|---|---|
| A | **LLM-generated calls** (§4.3) | **Main training data** | train 3,000 scam + 3,000 genuine; calib 600 + 600; dev 300 + 300; test_synth 300 + 300 | Check the provider's terms allow training a classifier on outputs. Don't publish raw scripts. |
| B | Template calls (`gen_calls.py`) | Extra train (≤ 30% of train) | 2,000 + 2,000 | Ours. Too easy for any headline. |
| C | **BothBosu/scam-dialogue** (Hugging Face) | Extra train + second synthetic test | 1,280 train / 320 test (scam types: ssn, refund, support, reward; genuine: delivery, insurance, telemarketing, wrong number) | Apache-2.0 per its card. US-flavoured. No tactic labels (mask them). For scams, `first_suspicious_caller_turn = 1`. |
| D | **Lockbox** (Data & Evaluation team) | **Headline test only** | 160 + 160 human-written | Never train, never tune. Evaluate once per frozen model. |
| E | Red team (Data & Evaluation) | Robustness test | 150 + 100 | Same as D |
| F | Real audio (Data & Evaluation) | Real-audio test | 40 + 40 transcripts | Consent; transcripts only |
| G | Public advisories (RBI, I4C/cybercrime.gov.in, CERT-In, bank pages, news) | Prompt inspiration | — | Never copy text verbatim |

Other public scam-call datasets are optional: use them only after checking the licence and hand-checking 100 rows.

### 4.3 LLM generation (`data_generate_llm.py`)
- **Model:**
  - Claude Sonnet 5.5 (`claude-sonnet-5-5`) for quality;
  - Claude Haiku 5.5 (`claude-haiku-5-5`) for bulk;
  - or any equally strong model.
  Use temperature 1.0 and JSON output.
- **Attributes** sampled per call (store them together as `scenario_seed`):
  - **type:** S1–S10 / G1–G9, balanced;
  - **persona:** senior first-time UPI user · panicked professional · sceptical student · distracted parent · homemaker · small shop owner;
  - **caller style (scams):** slow build · instant panic · friendly then threatening · **polite, no threats** (≥ 20%);
  - **victim behaviour:** complies · hesitates · resists · asks to call back;
  - **length:** 6–24 turns;
  - **noise:** fillers (uh, see, sir, madam, actually), interruptions, restarts, half sentences;
  - **flavour:** Indian English ("kindly", "do the needful"), Indian names, banks, cities, ₹ in lakhs;
  - **channel:** phone / WhatsApp call / video call (S2).
- **Prompt v1** (train + calib):
```
You are writing training data for a fraud-detection classifier that protects people from phone scams.
Write ONE realistic phone-call transcript in Indian English.

Call type: {type_id} - {type_description}
Victim persona: {persona}. Caller style: {style}. Victim behaviour: {behaviour}.
Length: {n_turns} turns, alternating caller and receiver, caller first.
Speech: natural spoken English with fillers, restarts and short half-sentences. No stage directions.
Do not use real phone numbers, real account numbers or real people's names.

Label each CALLER turn with zero or more tactics from:
urgency, threat, secrecy_isolation, authority_claim, credential_request, remote_access,
payment_instruction, stay_on_line.
Tactics mark manipulation only: a genuine delivery agent asking for a delivery OTP is NOT credential_request.

Return only JSON:
{"turns":[{"speaker":"caller|receiver","text":"...","tactics":[...]}],
 "label": 1 for scam or 0 for genuine,
 "type_id": "{type_id}",
 "first_suspicious_caller_turn": <0-based index among CALLER turns where a careful person should
   become suspicious, or null for genuine>}
```
- **Prompt v2** (dev + test_synth): the same content reworded (different instructions and example style), so dev/test aren't the same template as train.
- **Script behaviour:**
  - batches of 50 with resume support;
  - retry on invalid JSON;
  - log token usage;
  - write `data-eval/v1/llm_raw.jsonl`.
- **Split by `scenario_seed`**, never by row: each seed combination belongs to exactly one of train / calib / dev / test_synth.

### 4.4 Cleaning (`data_clean.py`)
1. **Schema check:**
   - alternating speakers;
   - `label` matches the type (S → 1, G → 0);
   - genuine calls have `first_suspicious_caller_turn = null`;
   - consistency rules from §4.1;
   - scams have ≥ 1 pressure tactic at or after the first suspicious turn.
2. **Dedupe:** embed whole calls with `sentence-transformers/all-MiniLM-L6-v2` and drop pairs with cosine > 0.90. Drop any call sharing a 12-word n-gram with a call in another split.
3. **Human review** (with Data & Evaluation): 5% of all calls, 10% of hard negatives. Log the reasons for each fix or drop.
4. **Label noise** (after the first teacher): `cleanlab.find_label_issues` on out-of-fold window predictions; review the top 200.

### 4.5 ASR-noise copies (`data_asr.py`)
The app classifies **Whisper output**, so train and test on it too.
1. **Text-to-speech:** `edge-tts`, Indian-English voices `en-IN-NeerjaNeural` / `en-IN-PrabhatNeural` (confirm with `edge-tts --list-voices`). Caller turns only.
2. **Phone effect:**
   - 8 kHz A-law round trip: `ffmpeg -i in.wav -ar 8000 -ac 1 -acodec pcm_alaw tmp.wav`, then back to 16 kHz;
   - band-pass 300–3400 Hz;
   - background noise at SNR 20/10/5 dB;
   - volume ±6 dB.
3. **ASR:** `faster-whisper` model `tiny.en`, the same model the browser uses, with greedy decoding and `language="en"`. Save a copy of the call with `"asr": true` and the same labels.
4. Do this for all of calib/dev/test_synth and ~50% of train. Delete the audio afterwards (it may stay in `backend/artifacts/` temporarily, never in Git).

### 4.6 Final splits (`data_build_splits.py` → `data-eval/v1/`)
| Split | Sources | Scam | Genuine | Used for |
|---|---|---|---|---|
| train | A-train + B + C-train(85%), ~50% ASR copies | ~5,000 | ~5,000 | Stages 1, 2, 4 |
| calib | A-calib, clean + ASR | 600 | 600 | Stage 3 training + isotonic (teacher never saw it) |
| dev | A-dev (prompt v2) + C-train(15%) | ~490 | ~490 | Model selection, early stopping, **thresholds** |
| test_synth | A-test (prompt v2) + C-test | ~460 | ~460 | Secondary synthetic test |
| redteam / lockbox / realaudio | E / D / F | — | — | Robustness / **headline** / real audio |

Also produce:
- `data-eval/v1/hashes.txt`: SHA-256 of every file;
- `data-eval/v1/DATASHEET.md`: sources, licences, counts per type/split, persona and style mix, % hard negatives, % ASR, known gaps.
Files > 50 MB go to Drive with their hash recorded.

---

## 5. Stage 1: embeddings + scam-library match (`stage1_embeddings.py`)
Ablation rows **C** (embeddings + LR) and **D** (+ library).

| Encoder | Params | Dim | Note |
|---|---|---|---|
| `sentence-transformers/all-MiniLM-L6-v2` | 22 M | 384 | Fast default; same family as the student |
| `BAAI/bge-small-en-v1.5` | 33 M | 384 | Often stronger |
| `intfloat/e5-small-v2` | 33 M | 384 | Needs the `"query: "` prefix |

Steps:
1. Build windows (W ∈ {2, 3, 4}) for train/dev/test_synth. Encode with `normalize_embeddings=True`, batch 128, and cache to `backend/artifacts/emb/`.
2. **Scam library:**
   - take the **train** scam windows at or after the gold first suspicious turn;
   - k-means with k = 300 and keep the window nearest each centroid (covering all S-types);
   - save it to `backend/artifacts/library/`, plus a text sample to `data-eval/results/scam_library_sample.txt`.
3. **Library features:**
   - `lib_max`: max cosine similarity to the library;
   - `lib_top3_mean`: mean of the top-3 similarities;
   - `lib_type`: one-hot of the best match's S-type.
4. **Classifier:** `LogisticRegression(C ∈ {0.25, 1, 4}, class_weight="balanced")` on the embedding (C), and on the embedding ⊕ standardised library features (D).
5. **Streaming evaluation** with the same scorer as Stage 0 (tactic weight ∈ {0, 0.15, 0.3}, rise 0.6, fall 0.15) and the §3 threshold policy.
6. Leave-one-scam-type-out recall for the best configuration.

Report window AUCs, call metrics with CIs, earliness, before-the-ask, and encode ms/window on CPU.
Pitfalls:
- forgetting e5's prefix;
- building the library from dev or test (leak);
- unnormalised embeddings.

---

## 6. Stage 2: fine-tuned teacher (`stage2_teacher.py`)
Ablation row **E**. The most accurate window model, which learns **context**. Too big for the phone; Stage 4 distils it.

| Candidate | Params | Note |
|---|---|---|
| `microsoft/deberta-v3-base` | 184 M | **Default** |
| `roberta-base` | 125 M | Robust to noisy text |
| `microsoft/deberta-v3-small` | 44 M | If GPU or time is short |

Train each once (seed 13), pick by dev window PR-AUC, then run the winner over 3 seeds.

- **Input:** window of 4 utterances, `" | "` join, tokenizer `truncation_side="left"`, `max_length=192`. Optionally prefix each utterance with `caller: ` (keep it only if dev improves).
- **Head:** `AutoModelForSequenceClassification(num_labels=9, problem_type="multi_label_classification")`.
  - Logit 0 = **scam**; logits 1–8 = tactics in the §3 order.
  - Save the order to `label_order.json`.
- **Loss:**
  ```
  L = BCE(scam; pos_weight = n_neg/n_pos) + λ · mean_k[ BCE(tactic_k) · mask ]     λ = 1.0
  ```
- **Hyperparameters:**
  - lr 2e-5 (3e-5 for deberta-v3-small);
  - batch 32 (use gradient accumulation if needed);
  - 3–5 epochs with early stopping on dev PR-AUC (patience 1);
  - warmup 6%, weight decay 0.01;
  - fp16 or bf16;
  - seeds 13/42/1234, reported as mean ± std.
  Expect under an hour per run on a T4.
- **Augmentation (train only):**
  - 50% ASR copies;
  - drop 1 of the 4 utterances with p = 0.1;
  - swap numbers, names, banks and cities;
  - random lowercasing and punctuation removal.
- **After training, save 9-sigmoid predictions** for train, calib, dev, test_synth and redteam to `backend/artifacts/teacher_preds/*.npy`. Stages 3 and 4 need them. Optionally make 3-fold out-of-fold train predictions for cleanlab.
- **Evaluate:**
  - streaming, same scorer and policy → row E;
  - per-tactic F1 and macro-F1 at 0.5 (target ≥ 0.85);
  - leave-one-type-out (1 seed).
- **Save the teacher folder to Drive** (too big for Git).

Pitfalls:
- DeBERTa-v3 + fp16 can give NaN losses (use bf16 or lower the LR);
- check that left truncation is actually applied;
- never select on test or lockbox.

---

## 7. Stage 3: streaming call-level model (`stage3_stream.py`)
Ablation row **F** (on teacher outputs). Retrain it on student outputs in Stage 4 → row **G**.

Train on **calib** (the teacher never saw it), pick thresholds on **dev**.

**Features after each caller utterance t** (only utterances ≤ t; save the order to `feature_order.json`):
| Group | Features |
|---|---|
| Current | `p_scam_t`, 8 tactic probabilities |
| History | max and mean of `p_scam`; max of each tactic; # distinct tactics with max > 0.5; # windows with `p_scam` > 0.5; EMA of `p_scam` (α = 0.5) |
| Sequence | authority before threat; threat before payment/credential/remote; turns since the first tactic > 0.5; t |
| Library | `lib_max_t`, running max of `lib_max` |
| Regex | # distinct regex tactics seen (`tactics.json`) |

- **Model:** `LGBMClassifier(n_estimators=300, learning_rate=0.05, num_leaves=15, min_child_samples=20, subsample=0.8, colsample_bytree=0.8, reg_lambda=1.0, random_state=13)`.
  - Step label: scam and t ≥ first suspicious.
  - `sample_weight` = 1 / number of steps in the call.
  - Keep it ≤ 300 trees and ≤ 15 leaves, because it runs in JavaScript.
- **Calibration:** `IsotonicRegression(out_of_bounds="clip")`, 5-fold cross-fitted on calib. Report ECE (10 bins), target ≤ 0.05.
- **Smoothing:** `risk += (α_up if q > risk else α_down) · (q − risk)`. Tune α_up ∈ {0.5, 0.6, 0.8} and α_down ∈ {0.1, 0.15, 0.25} on dev earliness at a fixed false-alarm rate. **Latch PAUSE** for the rest of the call.
- **Thresholds:** §3 policy on dev.
- **Export** `frontend/model/stream_model.json`:
  - the trees from `booster.dump_model()`;
  - `feature_order`, the isotonic `X_thresholds_` / `y_thresholds_`, α_up / α_down, warn / pause;
  - `label_order` and the model hash.
  Also write `backend/stream_parity_cases.json` (100 calls' per-step features → raw, calibrated and smoothed risk).
- **Figures:**
  - risk over time for a scam caught early, a scam caught late, and a hard-negative genuine call;
  - earliness histogram;
  - calibration curve.
- If LightGBM doesn't beat a plain EMA of `p_scam` on dev, ship the EMA and report it. That's a valid result.

---

## 8. Stage 4: distil, ONNX, int8 (`stage4_distill.py`, `stage4_export.py`)
Ablation row **G**: this is what runs on stage.

| Student | Layers/hidden | Params | int8 size (approx.) |
|---|---|---|---|
| `sentence-transformers/all-MiniLM-L6-v2` (or `nreimers/MiniLM-L6-H384-uncased`) | 6 / 384 | 22 M | ~23 MB, **default** |
| `sentence-transformers/paraphrase-MiniLM-L3-v2` | 3 / 384 | 17 M | ~17 MB, if phones are slow |
| `google/bert_uncased_L-4_H-256_A-4` | 4 / 256 | 11 M | ~11 MB, smallest |

- Same windows, `max_length=128`, left truncation, 9-logit multi-label head, same label order.
- **Distillation loss** per logit k (soft targets = teacher sigmoids; optionally add extra unlabelled windows scored by the teacher):
  ```
  L = α·BCE(σ(s_k/T), σ(t_k/T))·T² + (1−α)·BCE(σ(s_k), y_k)·mask_k      α = 0.7, T = 2.0
  ```
- **Hyperparameters:** lr 5e-5, batch 64, 5–8 epochs, warmup 6%, wd 0.01, early stopping on dev PR-AUC, 3 seeds.
- **Gate:** student dev window F1 ≥ teacher F1 − 1 point. If it fails:
  1. try L6 at 192 tokens;
  2. add more unlabelled distillation data;
  3. if it still fails, ship the larger model for the demo and call the small one future work.
- **Export:** `optimum-cli export onnx --model backend/artifacts/student/best --task text-classification backend/artifacts/student_onnx/`. Fallback: `torch.onnx.export` with opset 17, inputs `input_ids, attention_mask, token_type_ids`, output `logits`, dynamic batch and sequence axes.
- **Quantise:** `onnxruntime.quantization.quantize_dynamic("model.onnx", "model_quantized.onnx", weight_type=QuantType.QInt8)`. Check:
  - int8 vs fp32 logits (max abs diff);
  - dev F1 drop ≤ 0.5 point.
- **Browser folder** (the layout Transformers.js expects):
  ```
  frontend/model/student/
    config.json              (id2label = label order)
    tokenizer.json  tokenizer_config.json ("truncation_side": "left", "model_max_length": 128)
    special_tokens_map.json  label_order.json
    onnx/model_quantized.onnx
  frontend/model/student_library.json   # library re-embedded with the STUDENT (mean pooling, normalised)
  frontend/model/stream_model.json      # Stage 3 retrained on int8 STUDENT predictions (re-run §7)
  ```
  If the library feature is used, the export also needs a pooled-embedding output. Otherwise retrain Stage 3 without the library features.
- **Report:**
  - row G;
  - size on disk;
  - onnxruntime CPU ms per window (single thread);
  - teacher vs student vs int8 table on dev (window F1, PR-AUC, tactic macro-F1).

---

## 9. Browser integration
New files, all owned by Backend:
- `frontend/js/detector-onnx.js`
- `frontend/js/nlp-worker.js` (module worker: tokenizer + ONNX)
- `frontend/js/stream-model.js` (tree evaluator + isotonic + smoothing, pure JS)
- `backend/parity_onnx.mjs`

- **Runtime:** Transformers.js **3.0.2** (the same version Whisper uses). Configure `env.allowRemoteModels = false`, `env.allowLocalModels = true` and `env.localModelPath = <site>/model/`, then load with `AutoModelForSequenceClassification.from_pretrained("student", { dtype: "q8", device: "wasm" })`.
- **Per utterance:**
  1. window → tokenise;
  2. student → sigmoids (`p`, `tacticProbs`) and embedding → `libMax`;
  3. Stage 3 features → trees → isotonic → smoothing → level (latched PAUSE);
  4. regex spans for highlighting.
- **Fallback:** if loading fails, use Stage 0 (`bosc-core.js`) and show "basic detector".
- **Parity tests** (run before every merge):
  | Test | Cases | Pass if |
  |---|---|---|
  | Tokenizer IDs, JS vs Python | 200 strings (₹, "o.t.p", > 128 tokens, empty) | Identical |
  | Student logits | 200 windows | Max abs diff < 1e-4 |
  | Stage 3 tree output | 100 feature vectors | < 1e-6 |
  | Full stream | 10 calls | Risk diff < 1e-4, same levels |
  Node needs `npm i -D @huggingface/transformers@3.0.2`.
- **On the demo phones** (with Frontend): load time; **inference ms per window** (median and p95 over 50, target ≤ 50 ms); memory alongside Whisper. Record them in `data-eval/results/latency.md`.

---

## 10. Evaluation (`evaluate_all.py`)
One command regenerates every table and figure in `data-eval/results/` from the frozen model files.

- **Every metric** from §3 gets a 95% bootstrap CI (1,000 resamples over calls). Rows E and G get mean ± std over 3 seeds. Also report:
  - real-audio drop (call F1 on app transcripts minus F1 on clean transcripts; target ≤ 5 points);
  - ECE;
  - latency.
- **Ablation table** (`data-eval/results/ablation.md`):
  | Row | System | Lockbox recall / FA @PAUSE | Red-team recall | Real-audio F1 | Earliness |
  |---|---|---|---|---|---|
  | A | Regex tactics only | | | | |
  | B | TF-IDF + LR (Stage 0) | | | | |
  | C | Embeddings + LR | | | | |
  | D | C + scam library | | | | |
  | E | Fine-tuned teacher | | | | |
  | F | E + streaming | | | | |
  | G | Student int8 + streaming (**on the phone**) | | | | |
- **Figures:**
  1. PR curves (window and call level) with WARN and PAUSE marked;
  2. confusion matrix at PAUSE on the lockbox;
  3. risk over time (3 calls);
  4. recall per S-type and flag rate per G-type;
  5. leave-one-type-out;
  6. robustness grid (clean / ASR / phone-codec / room audio);
  7. call F1 vs Whisper size with real-time factor;
  8. calibration curve;
  9. earliness histogram.
- **Error list:** every lockbox miss and false alarm goes to `data-eval/results/errors_raw.jsonl` for Data & Evaluation to group.
- **Lockbox rule:** evaluate it once per frozen model version. Log the time, model hash and lockbox hash in `data-eval/results/lockbox_log.md`.

---

## 11. Contracts with the other teams

**Frontend (detector API).** Today:
```js
const { scorer, detector } = await loadScorer("model/");
const r = scorer.push(text);  // {p, raw, risk, level: "SAFE"|"WARN"|"PAUSE", tactics: {name: [[start,end],...]}}
scorer.reset(); scorer.seen; scorer.warn; scorer.pause; scorer.history; scorer.level(r)
detector.labels
```
The ONNX version keeps every field, adds `tacticProbs` and `libMax`, and makes `push` **async**. Tell Frontend before merging, because they must `await` it and process utterances in order.

**Data & Evaluation (call format).** JSONL, as in `data-eval/README.md`. The lockbox, red-team and real-audio sets arrive from them and are **read-only** for Backend.

---

## 12. Freeze and release (Day −2)
1. Ship G if it passed the gate and the parity tests. Otherwise ship the best earlier stage that runs on the phone (Stage 0 is always the fallback).
2. Run `evaluate_all.py` on the frozen files. **These are the numbers spoken on stage.**
3. `git tag model-v1.0 && git push origin model-v1.0`. After this: no retraining, no threshold changes.
4. Write `docs/MODEL_CARD.md` (one page):
   - intended use: a warning and payment-pause aid, not an automatic blocker;
   - the architecture and data summary;
   - headline metrics with CIs;
   - known failures;
   - English only, synthetic training data, privacy.
5. **Hand-off:**
   - Frontend gets the new detector plus 5 joint phone demos;
   - Data & Evaluation gets the ablation table, figures, error list and library sample.

Likely judge questions:
- *Why not keyword rules?* Rows A vs G on hard negatives.
- *Is the accuracy real?* Human lockbox + real recordings + CIs + listed failures.
- *How fast?* `latency.md`.
- *What if scammers change their script?* Leave-one-type-out results + library updates. It is an arms race.
- *Why synthetic training data?* No consented Indian scam-call corpus exists; the lockbox and recordings are the reality check.

---

## 13. Checklist and targets
- [ ] Setup works; branch `backend`
- [ ] Data v1 built, cleaned, deduped, split by seed; datasheet + hashes
- [ ] Lockbox received → hash logged
- [ ] Stage 1 → rows C, D
- [ ] Stage 2 teacher (3 seeds) → row E; predictions saved
- [ ] Stage 3 on calib, thresholds on dev → row F; `stream_model.json`
- [ ] Stage 4 student passes the gate; int8 ONNX ≤ 30 MB → row G
- [ ] Browser detector + all parity tests pass; phone latency measured
- [ ] `evaluate_all.py` → all tables and figures; lockbox evaluated once
- [ ] Frozen, tagged, model card written, Frontend integrated

| Metric | Target |
|---|---|
| Lockbox recall @PAUSE | ≥ 95% |
| Lockbox false alarm @PAUSE / @WARN | ≤ 2% / ≤ 5% |
| Median earliness | ≤ 3 caller turns |
| Tactic macro-F1 | ≥ 0.85 |
| Real-audio drop | ≤ 5 F1 points |
| ECE | ≤ 0.05 |
| Student in the phone browser | ≤ 50 ms per window |
| Student int8 size | ≤ 30 MB |

If a target is missed, report the real number.
