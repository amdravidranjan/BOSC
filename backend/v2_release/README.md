# Mac v2 release (shipped in the Android app on branch `rishi`)

- `model.json`, `info.json`: Stage 0 v2 (TF-IDF + LR), `v2_stage0.py`. Same file as `android/core/src/main/resources/bosc/stage0/model.json`.
- Student: round-1 MiniLM-L6 int8 = `frontend/model/student/` and `frontend/model/stream_model.json` (`v2_student_r1.sh`: 6 epochs max, patience 3, epoch chosen on dev Whisper copies).
- `ensemble.json`, `report.json`: Stage 0 + student combination for Android (`v2_android_export.py`).

Pipeline: `v2_handwritten.py` → `v2_build.py select` → `v2_asr_local.py` → `v2_build.py assemble` → `v2_finish.sh` → `v2_student_r1.sh` → `v2_eval.py` / `v2_android_export.py`.
Results: `data-eval/results/v2_mac/` (first student) and `data-eval/results/v2_mac_r1/` (shipped student).
Data caveat: 160 of the calls in `data-eval/v2/handwritten/` were written by Claude (AI), not humans, and nothing here has been human-reviewed, so the release gate's `data_reviewed` check does not pass.
