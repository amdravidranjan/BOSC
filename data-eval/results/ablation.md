# BOSC: ablation (Stages 0–4)

Generated 2026-11-13 13:08 by `backend/kaggle/bosc_kaggle.ipynb`

> Every set below is synthetic unless it's named lockbox / redteam / realaudio. Don't headline synthetic numbers. Thresholds were picked on dev (genuine false alarms ≤2% PAUSE / ≤5% WARN) and applied unchanged everywhere.

Data: teacher_train 4073 calls / 22714 windows, calib 1015 calls / 5641 windows, dev 792 calls / 4467 windows, test_gen_heldout_phrasing 600 calls / 3125 windows, test_hf 320 calls / 2275 windows

## dev

| Row | System | Recall @PAUSE | False alarm @PAUSE | Recall @WARN | False alarm @WARN | Earliness (turns) | PAUSE by first ask | Window PR-AUC | Tactic macro-F1 |
|---|---|---|---|---|---|---|---|---|---|
| A | Regex tactics only | 44.8% [39.7%–49.9%] | 0.0% [0.0%–0.0%] | 67.6% [63.0%–72.4%] | 4.8% [2.8%–6.8%] | 3.0 | 11.7% | n/a | n/a |
| B | TF-IDF + LR (Stage 0, shipped today) | 97.2% [95.7%–98.7%] | 1.8% [0.5%–3.3%] | 98.2% [96.7%–99.5%] | 4.8% [3.0%–7.1%] | 1.0 | 81.9% | n/a | n/a |
| C | Embeddings (all-MiniLM-L6-v2) + LR | 93.7% [91.4%–95.9%] | 1.5% [0.5%–2.8%] | 96.7% [94.9%–98.2%] | 4.8% [2.8%–6.8%] | 1.0 | 69.9% | 0.955 | n/a |
| D | Embeddings + LR + scam library | 91.9% [89.1%–94.4%] | 1.0% [0.0%–2.0%] | 93.9% [91.6%–96.2%] | 4.8% [2.8%–7.1%] | 1.0 | 71.2% | 0.953 | n/a |
| E | Teacher teacher_deberta-v3-base_s13 | 99.5% [98.7%–100.0%] | 1.8% [0.8%–3.3%] | 100.0% [100.0%–100.0%] | 3.5% [1.8%–5.5%] | 0.0 | 94.3% | 0.996 | 0.618 |
| F | Teacher + stream model (LightGBM + isotonic) | 99.7% [99.2%–100.0%] | 1.5% [0.5%–2.8%] | 100.0% [100.0%–100.0%] | 2.8% [1.3%–4.5%] | 0.0 | 94.3% | 0.996 | 0.618 |
| G_window_only | Student int8, simple scorer (no stream model) | 99.7% [99.2%–100.0%] | 1.8% [0.5%–3.0%] | 99.7% [99.2%–100.0%] | 4.8% [2.8%–7.1%] | 1.0 | 89.6% | 0.996 | 0.0 |
| G | Student all-MiniLM-L6-v2 int8 + stream model (ships) | 99.2% [98.2%–100.0%] | 1.8% [0.5%–3.0%] | 99.2% [98.2%–100.0%] | 2.5% [1.0%–4.3%] | 1.0 | 89.0% | 0.996 | 0.0 |

## test_gen_heldout_phrasing

| Row | System | Recall @PAUSE | False alarm @PAUSE | Recall @WARN | False alarm @WARN | Earliness (turns) | PAUSE by first ask | Window PR-AUC | Tactic macro-F1 |
|---|---|---|---|---|---|---|---|---|---|
| A | Regex tactics only | 45.7% [40.0%–51.3%] | 0.0% [0.0%–0.0%] | 80.7% [76.0%–85.0%] | 5.0% [2.7%–7.7%] | 3.0 | 10.0% | n/a | n/a |
| B | TF-IDF + LR (Stage 0, shipped today) | 98.0% [96.3%–99.3%] | 9.0% [5.7%–12.3%] | 98.0% [96.3%–99.3%] | 13.0% [9.3%–17.0%] | 1.0 | 89.6% | n/a | n/a |
| C | Embeddings (all-MiniLM-L6-v2) + LR | 95.3% [93.0%–97.3%] | 5.7% [3.0%–8.7%] | 97.0% [95.0%–98.7%] | 10.7% [7.3%–14.3%] | 1.0 | 76.9% | 0.931 | n/a |
| D | Embeddings + LR + scam library | 95.7% [93.3%–97.7%] | 11.0% [7.7%–14.7%] | 97.0% [95.0%–98.7%] | 12.7% [9.0%–16.7%] | 1.0 | 80.6% | 0.946 | n/a |
| E | Teacher teacher_deberta-v3-base_s13 | 98.7% [97.3%–99.7%] | 8.0% [5.0%–11.3%] | 99.3% [98.3%–100.0%] | 13.0% [9.3%–17.0%] | 0.0 | 93.6% | 0.978 | 0.684 |
| F | Teacher + stream model (LightGBM + isotonic) | 98.7% [97.3%–99.7%] | 8.3% [5.7%–11.7%] | 98.7% [97.3%–99.7%] | 12.3% [8.7%–16.3%] | 0.0 | 95.3% | 0.978 | 0.684 |
| G_window_only | Student int8, simple scorer (no stream model) | 97.0% [95.0%–98.7%] | 5.0% [2.7%–7.7%] | 98.3% [96.7%–99.7%] | 6.0% [3.3%–9.0%] | 1.0 | 86.3% | 0.981 | 0.0 |
| G | Student all-MiniLM-L6-v2 int8 + stream model (ships) | 96.7% [94.7%–98.3%] | 5.7% [3.0%–8.3%] | 96.7% [94.7%–98.3%] | 5.7% [3.0%–8.3%] | 1.0 | 85.3% | 0.981 | 0.0 |

## test_hf

| Row | System | Recall @PAUSE | False alarm @PAUSE | Recall @WARN | False alarm @WARN | Earliness (turns) | PAUSE by first ask | Window PR-AUC | Tactic macro-F1 |
|---|---|---|---|---|---|---|---|---|---|
| A | Regex tactics only | 0.0% [0.0%–0.0%] | 0.0% [0.0%–0.0%] | 15.6% [10.0%–21.2%] | 0.0% [0.0%–0.0%] | 5.0 | n/a | n/a | n/a |
| B | TF-IDF + LR (Stage 0, shipped today) | 100.0% [100.0%–100.0%] | 1.2% [0.0%–3.1%] | 100.0% [100.0%–100.0%] | 1.9% [0.0%–4.4%] | 0.0 | n/a | n/a | n/a |
| C | Embeddings (all-MiniLM-L6-v2) + LR | 96.9% [94.4%–99.4%] | 0.0% [0.0%–0.0%] | 99.4% [98.1%–100.0%] | 0.6% [0.0%–1.9%] | 1.0 | n/a | 0.993 | n/a |
| D | Embeddings + LR + scam library | 100.0% [100.0%–100.0%] | 0.0% [0.0%–0.0%] | 100.0% [100.0%–100.0%] | 0.0% [0.0%–0.0%] | 1.0 | n/a | 0.998 | n/a |
| E | Teacher teacher_deberta-v3-base_s13 | 100.0% [100.0%–100.0%] | 0.0% [0.0%–0.0%] | 100.0% [100.0%–100.0%] | 0.0% [0.0%–0.0%] | 0.0 | n/a | 1.0 | n/a |
| F | Teacher + stream model (LightGBM + isotonic) | 100.0% [100.0%–100.0%] | 0.0% [0.0%–0.0%] | 100.0% [100.0%–100.0%] | 0.0% [0.0%–0.0%] | 0.0 | n/a | 1.0 | n/a |
| G_window_only | Student int8, simple scorer (no stream model) | 99.4% [98.1%–100.0%] | 0.0% [0.0%–0.0%] | 99.4% [98.1%–100.0%] | 0.0% [0.0%–0.0%] | 1.0 | n/a | 1.0 | n/a |
| G | Student all-MiniLM-L6-v2 int8 + stream model (ships) | 100.0% [100.0%–100.0%] | 0.6% [0.0%–1.9%] | 100.0% [100.0%–100.0%] | 0.6% [0.0%–1.9%] | 1.0 | n/a | 1.0 | n/a |

## Teacher vs student vs int8 (dev windows)

| Model | Window F1 | PR-AUC | Tactic macro-F1 |
|---|---|---|---|
| teacher | 0.980 | 0.996 | 0.6183986324791175 |
| student_fp32 | 0.959 | 0.994 | 0.0 |
| student_int8 | 0.962 | 0.996 | 0.0 |

Gate (student F1 ≥ teacher F1 − 1 point): **FAILED**

## Size and speed (onnxruntime CPU, 1 thread, one window)

- int8 model 23.0 MB (fp32 91.0 MB)
- 10.8 ms median, 16.2 ms p95 per window (Kaggle CPU; measure on the demo phones too → `latency.md`)
- ONNX check: {'exporter': 'legacy', 'torch_vs_onnx_fp32_max_abs': 4.291534423828125e-06, 'all': {'legacy': 4.291534423828125e-06}, 'fp32_vs_int8_max_abs': 1.1690632104873657}

## Stream model

- F: mode **lgbm**, α_up 0.5, α_down 0.1, WARN 0.050, PAUSE 0.170, calibration {'ece_raw_oof': 0.0008258786886522254, 'ece_calibrated_crossfit': 0.001014885070340627}
- G: mode **lgbm**, α_up 0.8, α_down 0.1, WARN 0.949, PAUSE 0.954, calibration {'ece_raw_oof': 0.001977506638263844, 'ece_calibrated_crossfit': 0.002506113707382077}

## Leave-one-scam-type-out (row C, recall @PAUSE on the unseen type)

| Type | Recall |
|---|---|
| S10_family_emergency | 90.0% |
| S1_bank_kyc | 90.0% |
| S2_digital_arrest | 100.0% |
| S3_remote_refund | 100.0% |
| S4_utility | 100.0% |
| S5_sim_aadhaar | 90.0% |
| S6_card_loan | 93.3% |
| S7_task_investment | 100.0% |
| S8_prize_lottery | 93.3% |
| S9_tech_support | 96.7% |

Teacher dev PR-AUC over seeds: 0.9961 ± 0.0000

## Not done in this notebook

- Real ASR copies (edge-tts → phone codec → faster-whisper). Training uses text-level ASR noise (lowercase, no punctuation, dropped utterance) instead.
- LLM-generated training data (source A in backend/README.md §4). Attach it as `bosc_train_*.jsonl` and re-run.
- Lockbox / red-team / real-audio rows appear only when those files are attached.
