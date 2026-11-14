#!/usr/bin/env bash
# Teacher alone first (memory), then remaining ASR copies, then the finishing pipeline.
cd "$(dirname "$0")"; A=artifacts/v2
echo "$(date +%H:%M) ASR resumed alongside teacher predictions" > $A/finish.log
../.venv/bin/python v2_asr_local.py --input $A/asr_input.jsonl --workers 7 2>&1 | grep --line-buffered -v -E "Only waves|Warning" > $A/asr_local.log
echo EXIT=${PIPESTATUS[0]} >> $A/asr_local.log
echo "$(date +%H:%M) ASR done ($(tail -1 $A/asr_local.log))" >> $A/finish.log
./v2_finish.sh >> $A/finish.log 2>&1; echo EXIT=$? >> $A/finish.log
