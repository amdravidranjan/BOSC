# V2 preparation and diagnosis — training pending

Branch: `radha-v2`, based on `97e5d30`. No new model has been trained or activated.
The original Stage 0 and student exports are unchanged; release remains disabled.

## Tactic-label audit and correction

The hypothesised mapping bug was **not reproduced**. All 382 template training
calls retain tactic labels, none of their positive labels are masked, the nine
outputs match the exported order, and each window uses the newest caller turn.
`test_v2_labels.py` checks exact vectors/masks for 20 handwritten windows, caller
isolation, context rollover, benign OTP requests and the deployed label order.

Confirmed weaknesses: 6,282/8,314 train windows lack tactic annotations, but v1
soft distillation still applies all eight teacher tactic targets to these windows.
Rare positive counts range from 30 stay-on-line examples to 293 payment examples.
On labelled stay-on-line positives, the teacher's mean training probability is
only 0.077. These are verified failure mechanisms/risk factors, not experimental
proof of a single cause of zero student F1. Full evidence: `v2_label_audit.json`.

Candidate fix: explicit missing-label masks and fail-fast invalid annotations;
train-only capped per-head positive weights; scam-only soft distillation with
alpha 0.3; supervised tactic learning from known labels. It is wired into
`train_one(loss_version='v2')`. Default v1 loss is preserved for reproduction.
Gradient tests verify unknown tactic targets have no effect. A future run must
validate the candidate; no improved F1 is claimed. Calibration folds now keep
clean/ASR/noise siblings together.

## WARN/PAUSE investigation

V1 thresholds recompute exactly: WARN 0.95150408, PAUSE 0.96185955.
Among 355 genuine dev calls, 4.79% cross WARN and 1.69% cross PAUSE.
The 95th/98th percentiles of genuine peaks are 0.95138/0.96186. The narrow band
therefore follows the false-alarm policy, rather than an export bug. Window ECE
is 0.02910; this does not guarantee low call-level false alarms. The numerical
calibration curve is in `v2_calibration_audit.json`; the unchanged v1 curve is
`fig_student_calibration.png`. No thresholds were manually widened.

## V2 ablations and subgroup results

| System | Clean | ASR A | ASR B |
|---|---|---|---|
| A rules | Not run | Not run | Not run |
| B retrained Stage 0 + tactics | Not run | Not run | Not run |
| C/D embeddings/library | Not run | Not run | Not run |
| E teacher | Not run | Not run | Not run |
| F teacher streaming | Not run | Not run | Not run |
| G student int8 streaming | Not run | Not run | Not run |

Per-type, per-language, polite-scam recall, WARN/PAUSE recall/FA, earliness,
before-the-ask, 95% call-bootstrap CIs and 20 worst errors are **pending** actual
v2 frozen models and test data. V1 results are not relabelled as v2 results.

## Release checklist

| Requirement | Status |
|---|---|
| Tokenizer/logit/stream/level parity for v2 candidate | NOT RUN |
| Quantization F1 drop ≤0.005 | NOT RUN |
| ASR recall/FA at least as good as retrained Stage 0 | NOT RUN |
| S10 and polite recall at least Stage 0 | NOT RUN |
| Tactic F1 ≥0.85 or verified regex fallback | NOT RUN |
| Reviewed data and exact Android ASR asset comparison | PENDING |
| Release enabled | FALSE; original release untouched |

`v2_release_gate.py` is a tested decision function, not a completed promotion
pipeline. Missing/nonfinite evidence fails. It checks both ASR paths and records
the teacher F1 gap only as a diagnostic. CIs must be reported; interval overlap
alone is not evidence of non-inferiority. Model/data hashes still need to be
bound to evaluation evidence before promotion.

## Blocks and validation

No LLM API credential or local generation model is configured. Provider/model,
spending limit and output-training terms are required before generation. Actual
human review and Android model/VAD hashes remain outstanding. Generation API
integration, final reviewed split assembly, full v2 orchestration/evaluation and
artifact promotion remain unfinished; preparation scripts are not substitutes.

Run `.venv/bin/python backend/test_v2.py` for isolated suites. Loading Sherpa,
Torch and the other native ML libraries together caused a process crash during
combined unittest discovery; separate processes pass and match separate pipeline
jobs. The four original pipeline tests also pass. One real Edge-TTS smoke call
passed both phone paths after replacing an incompatible old fixed-length Whisper
export with the pinned current revision. No accuracy conclusion follows from it.
