#!/usr/bin/env bash
# Student round 1: 6 epochs, patience 3, epoch chosen on dev Whisper copies. Results -> artifacts/v2/release_r1.
set -euo pipefail
cd "$(dirname "$0")"
export BOSC_DATA=../data-eval/v2/splits PYTORCH_ENABLE_MPS_FALLBACK=1 BOSC_SELECT=asr
export BOSC_SCOPE="Mac v2 student round 1: patience 3, ASR-selected epoch"
PY=../.venv/bin/python; A=artifacts/v2
step() { echo "$(date +%H:%M:%S) START $1"; shift; "$@" > "$A/r1_$STEP.log" 2>&1 || { echo "FAILED: see $A/r1_$STEP.log"; tail -20 "$A/r1_$STEP.log"; exit 1; }; echo "$(date +%H:%M:%S) DONE"; }
STEP=student step student $PY -u stage4_distill.py --device mps --seeds 13 --epochs 6 --model minilm --length 192 --loss v2 --patience 3 --tag _r1
STEP=export step export $PY -u stage4_export.py
STEP=stage3 step stage3 $PY -u stage3_stream.py --preds student_int8 --name student
mkdir -p $A/release_r1 && rm -rf $A/release_r1/student && cp -R ../frontend/model/student $A/release_r1/ && cp ../frontend/model/stream_model.json $A/release_r1/
STEP=eval step eval env BOSC_V2_RELEASE=$A/release_r1 $PY -u v2_eval.py --out ../data-eval/results/v2_mac_r1
echo "R1_DONE"
