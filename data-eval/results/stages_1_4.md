# Stages 1–4: available-data experiment

**All results below are synthetic. No human or real-audio performance is claimed.**

## Data and scope

Used the README’s BothBosu/scam-dialogue dataset plus BOSC-generated template calls. The teacher is DeBERTa-v3-small; the selected student is MiniLM-L6. Both were fine-tuned with three seeds (13, 42, 1234), early stopping on dev PR-AUC, caller-only four-utterance windows, left truncation, and masked HF tactic loss. Teacher: 192 tokens; student: 192. Stage 3 uses call-grouped cross-fitting on calib and dev-only threshold selection. The deployment features omit the optional library features, as allowed by README §8.

This is **not the full README benchmark**: no LLM-generated corpus, human review, semantic/12-word-ngram dedupe, ASR augmentation, lockbox, red team, real recordings, teacher architecture sweep, or neural leave-one-type-out reruns were available/run. Template phrases can recur across splits. These results cannot establish generalization to real scam calls. The original Stage 0 report uses different splits and is not a controlled comparison to this table. Rows A/B below were rerun on the new splits with the fixed Stage 0 configuration.

| Split | Calls | HF | Templates |
|---|---:|---:|---:|
| test_synth | 885 | 319 | 566 |
| dev | 751 | 192 | 559 |
| calib | 789 | 191 | 598 |
| train | 1272 | 890 | 382 |

## Held-out synthetic calls (95% call-bootstrap intervals)

| System | PAUSE recall | PAUSE false alarms | Median WARN delay (turns) |
|---|---|---|---:|
| A: rules only | 29.69% [25.76%, 33.84%] | 0.00% [0.00%, 0.00%] | 3.0 |
| B: TF-IDF + LR | 96.94% [95.20%, 98.47%] | 3.51% [1.87%, 5.62%] | 1.0 |
| B + tactics | 98.69% [97.60%, 99.56%] | 3.98% [2.34%, 6.09%] | 1.0 |
| C: embeddings + LR | 96.29% [94.54%, 98.03%] | 6.56% [4.44%, 8.90%] | 1.0 |
| D: embeddings + library | 87.77% [84.72%, 90.61%] | 7.03% [4.68%, 9.60%] | 1.0 |
| E: DeBERTa teacher | 99.78% [99.34%, 100.00%] | 5.15% [3.28%, 7.26%] | 1.0 |
| F: teacher streaming | 99.78% [99.34%, 100.00%] | 4.68% [2.81%, 6.79%] | 0.0 |
| G: int8-weight student streaming | 97.16% [95.63%, 98.69%] | 6.09% [3.75%, 8.43%] | 1.0 |

## Student by source

| Source | Scam | Genuine | PAUSE recall | PAUSE false alarms |
|---|---:|---:|---|---|
| hf | 160 | 159 | 100.00% [100.00%, 100.00%] | 0.00% [0.00%, 0.00%] |
| gen | 298 | 268 | 95.64% [93.28%, 97.65%] | 9.70% [5.97%, 13.43%] |

## Embedding baseline: leave one scam type out

| Held-out type | PAUSE recall |
|---|---|
| S10_family_emergency | 86.21% [72.41%, 96.55%] |
| S1_bank_kyc | 90.00% [76.67%, 100.00%] |
| S2_digital_arrest | 100.00% [100.00%, 100.00%] |
| S3_remote_refund | 100.00% [100.00%, 100.00%] |
| S4_utility | 90.00% [76.67%, 100.00%] |
| S5_sim_aadhaar | 93.33% [83.33%, 100.00%] |
| S6_card_loan | 89.66% [75.86%, 100.00%] |
| S7_task_investment | 76.67% [60.00%, 90.00%] |
| S8_prize_lottery | 93.33% [83.33%, 100.00%] |
| S9_tech_support | 90.00% [79.92%, 100.00%] |

## Validation and deployment gates

- Teacher dev PR-AUC mean ± std: 0.99584 ± 0.00055.
- Student dev PR-AUC mean ± std: 0.98814 ± 0.00352.
- Student within 1 F1 point of teacher: **False**.
- Int8 quantization F1 drop: 0.002550; <=0.005 gate: **True**.
- PyTorch / FP32 ONNX max logit error: 0.00000471.
- FP32 / int8 max dev logit error: 3.883817.
- ONNX bytes: 22926099; SHA-256 `d1e08cf6eab8131f17fa10ee41cae4b74564b996d9916037a2788ea37d202b2c`.
- Quantization: int8 matrix/embedding weights via QDQ; float32 activations. Dynamic activation quantization failed WASM parity and was rejected.
- Single-thread CPU ms/window: median 46.63, p95 48.35. This is Mac CPU latency, not phone latency.
- Teacher streaming selected: ema; student streaming selected: trees.
- Student cross-fitted calibration ECE: 0.00318.
- Student tree-calibrator ECE on separate dev calls: 0.02910.
- Selected student window-score ECE on dev: 0.02910.
- Teacher tactic F1 target >=0.85: False; actual mean 0.23536.
- See backend/runs for configs, split hashes, seed-level metrics, and exact dependencies.
- Browser runtime parity is recorded separately; release.json is enabled only after all required parity/gates pass.
- Large PyTorch teacher/student checkpoints and pretrained caches stay local in backend/artifacts, following the repository policy. The deployable int8 student is versioned in frontend/model/student.

Stage 1 CPU encoding (batch 1, 50 windows): median 8.26 ms, p95 17.09 ms.
