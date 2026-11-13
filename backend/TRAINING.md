# Available-data training on macOS

This run implements Stages 1–4 with BothBosu/scam-dialogue and the repository's
template generator. It does not satisfy the full dataset collection and research
benchmark in README.md: no additional LLM calls, ASR copies, human lockbox, red-team
set, or real recordings were supplied. Evaluation results must be called synthetic.
Exact caller-transcript duplicates are removed across splits; template phrase
overlap remains. See `data-eval/v1/DATASHEET.md` and `manifest.json`.

## Reproduce

Use Python 3.12 on this Mac, with the versions in `requirements-mac-lock.txt`.
The original guide recommends Python 3.10/3.11; 3.12 is the installed interpreter
used by this experiment. Models and intermediate arrays live in ignored
`backend/artifacts/`; no access token is needed for the public models or datasets.
The lock file pins ONNX Runtime 1.19.2 to match Transformers.js 3.0.2's native
runtime. The initially installed Python ORT 1.31 changed quantized logits across
runtimes and failed parity; do not upgrade it without rerunning the full checks.

```sh
python3.12 -m venv .venv
.venv/bin/python -m pip install -r backend/requirements-mac-lock.txt
mkdir -p data-eval/raw
curl -fL -o data-eval/raw/scam-dialogue_train.csv https://huggingface.co/datasets/BothBosu/scam-dialogue/resolve/main/scam-dialogue_train.csv
curl -fL -o data-eval/raw/scam-dialogue_test.csv https://huggingface.co/datasets/BothBosu/scam-dialogue/resolve/main/scam-dialogue_test.csv
.venv/bin/python backend/gen_calls.py data-eval/generated
.venv/bin/python backend/data_build_splits.py
.venv/bin/python backend/download_models.py
.venv/bin/python backend/stage1_embeddings.py --device mps
.venv/bin/python backend/stage0_available.py
.venv/bin/python backend/stage1_loto.py
.venv/bin/python backend/stage2_teacher.py --device mps
.venv/bin/python backend/stage3_stream.py
.venv/bin/python backend/stage4_distill.py --device mps
.venv/bin/python backend/stage4_export.py
.venv/bin/python backend/stage3_stream.py --preds student_int8 --name student
npm ci
npm run test:pipeline
npm run parity
npm run parity:neural
.venv/bin/python backend/evaluate_all.py
```

Use `--device cpu` on machines without Apple MPS. On a Mac the sandbox may hide
the GPU; the actual training run used approved access outside that sandbox.
`download_teacher.py` is a resumable range-download fallback with a pinned SHA-256
for the Microsoft teacher checkpoint.

`run_remaining.py` resumes this run after the teacher checkpoint is downloaded,
writes step logs to `backend/artifacts/`, runs the checks, and writes a gated
`frontend/model/release.json`. It skips completed teacher/student seeds, so remove
or use a fresh artifacts directory when changing data or hyperparameters.
Run Stage 1, `stage0_available.py`, and `stage1_loto.py` before the resume script
on a fresh checkout. `archive_validation.py` collects its successful check logs
and copies the small stage models to `backend/models/` for versioning.

## Model and calibration details

- Stage 1: normalized MiniLM-L6 embeddings, W=2/3/4, LR C=.25/1/4, optional
  train-only k-means scam library. Separate embedding and library baselines.
- Stage 2: DeBERTa-v3-small, masked scam/tactic BCE, 192 tokens, three seeds,
  AdamW lr 3e-5, 3 epochs maximum, early stopping patience 1 on dev PR-AUC.
  MPS uses bfloat16 autocast for the forward pass and float32 loss/optimizer state.
  Length bucketing and per-batch padding removal retain all training windows.
  Other teacher architectures were not swept.
- Stage 3: causal features without optional library scores, LightGBM 300 trees,
  15 leaves, five-fold call-grouped OOF isotonic calibration, dev-only smoothing
  and thresholds. Plain EMA is retained if the trees do not improve dev results.
  PAUSE latches until reset. Neither teacher nor student trains on calib.
- Stage 4: MiniLM-L3, 128 tokens, 5 epochs maximum, three seeds, temperature-2
  teacher-logit distillation plus masked hard loss. Matrix and embedding weights
  are stored as int8 using standard QDQ nodes, with float32 activations. Dynamic
  activation quantization was rejected because native/WASM logits failed parity.
  The portable export passed the same strict checks. Its runtime may expand
  weights to float32; the reported file size is not a RAM measurement.
  Stage 3 is retrained using batch-one int8-weight student predictions,
  matching deployment. Runtime checks compare token IDs, logits and full calls.

Transformers.js 3.0.2 does not implement left truncation through tokenizer config,
so `student-tokenizer.js` explicitly keeps the newest `max_length - 2` WordPieces,
wraps BERT special tokens, and pads to the selected length. Parity includes long text, empty input, rupees,
and dotted OTP text. Browser model loading falls back visibly to the basic model
on a load failure. Inference errors are shown instead of silently dropping calls.

The browser student is enabled only if its F1 is within one point of the teacher,
the quantized model loses no more than half a point, and parity checks pass.
This gate does not establish real-world safety. If a quality gate fails, the
trained experiment is retained, but Stage 0 remains the active browser detector.
The resume script first tries MiniLM-L3 at 128 tokens and, if its quality gate
fails, tries the README's MiniLM-L6 fallback at 192 tokens across three seeds.
WASM kernel and full-call parity run under Node; actual phone latency and
browser UI behavior on the demo phones still require the team's device tests.

Large teacher and student PyTorch checkpoints remain local, per repository policy.
The browser ONNX model, tokenizer, streaming trees, run records and reports are
versioned. `data-eval/results/checkpoint_manifest.json` identifies local weights.
