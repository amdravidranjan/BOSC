#!/usr/bin/env bash
# Mac v2 pipeline, part 2: runs after the ASR copies AND the teacher have finished.
# Every step logs to artifacts/v2/<step>.log; the script stops at the first failure.
set -euo pipefail
cd "$(dirname "$0")"
export BOSC_DATA=../data-eval/v2/splits
export BOSC_SCOPE="Mac v2: v1 + AI-handwritten Indian calls + Whisper ASR copies (offline macOS voices) + text noise; synthetic only"
export PYTORCH_ENABLE_MPS_FALLBACK=1
PY=../.venv/bin/python
A=artifacts/v2
step() { echo "$(date +%H:%M:%S) START $1"; shift; "$@" > "$A/$STEP.log" 2>&1 || { echo "FAILED: see $A/$STEP.log"; tail -20 "$A/$STEP.log"; exit 1; }; echo "$(date +%H:%M:%S) DONE"; }

echo "waiting for ASR copies and teacher..."
until grep -q "EXIT=" $A/asr_local.log 2>/dev/null && grep -q "EXIT=" $A/teacher.log 2>/dev/null; do sleep 20; done
grep -q "EXIT=0" $A/asr_local.log || { echo "ASR job failed"; exit 1; }
grep -q "EXIT=0" $A/teacher.log || { echo "teacher failed"; exit 1; }

STEP=assemble step assemble $PY v2_build.py assemble
now=$(shasum -a 256 ../data-eval/v2/splits/train.jsonl | cut -c1-16)
[ "$now" = "$(cat $A/train_sha_at_teacher.txt)" ] || { echo "train split changed since the teacher trained ($now)"; exit 1; }
echo "train split unchanged ($now)"

rm -f artifacts/teacher/13/calib_logits.npy artifacts/teacher/13/dev_logits.npy artifacts/teacher/13/test_synth_logits.npy
STEP=teacher_preds step teacher_preds $PY -u stage2_teacher.py --device mps --seeds 13 --epochs 3 --loss v2
STEP=stage0_v2 step stage0_v2 $PY -u v2_stage0.py
echo "STAGE0_V2_READY"
STEP=stage3_teacher step stage3_teacher $PY -u stage3_stream.py
STEP=student step student $PY -u stage4_distill.py --device mps --seeds 13 --epochs 5 --model minilm --length 192 --loss v2
STEP=export step export $PY -u stage4_export.py
STEP=stage3_student step stage3_student $PY -u stage3_stream.py --preds student_int8 --name student
mkdir -p $A/release && rm -rf $A/release/student && cp -R ../frontend/model/student $A/release/ && cp ../frontend/model/stream_model.json $A/release/
cp onnx_parity_cases.json stream_parity_cases.json $A/release/
STEP=eval step eval $PY -u v2_eval.py
echo "PIPELINE_DONE"
