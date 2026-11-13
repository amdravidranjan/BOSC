#!/usr/bin/env bash
# Downloads the large speech files that are not committed to Git (~190 MB in total).
# Run once from the android/ folder before building:  bash scripts/fetch_models.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# 1. sherpa-onnx v2.0.1 Android library (k2-fsa, Apache-2.0): Whisper + Silero VAD, onnxruntime statically linked
mkdir -p app/libs
[ -f app/libs/sherpa-onnx-2.0.1.aar ] || curl -fL -o app/libs/sherpa-onnx-2.0.1.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar

# 2. Whisper tiny.en int8 for sherpa-onnx (same model family as the web app and the backend ASR-noise pipeline)
W=app/src/main/assets/whisper-tiny.en; mkdir -p "$W"
for f in tiny.en-encoder.int8.onnx tiny.en-decoder.int8.onnx tiny.en-tokens.txt; do
  [ -f "$W/$f" ] || curl -fL -o "$W/$f" "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/$f"
done

# 3. Vosk small Indian-English model (Apache-2.0): optional engine for live captions
V=app/src/main/assets/model-en-in
if [ ! -f "$V/uuid" ]; then
  tmp=$(mktemp -d); curl -fL -o "$tmp/m.zip" https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip
  unzip -q "$tmp/m.zip" -d "$tmp"; mkdir -p "$V"; cp -R "$tmp"/vosk-model-small-en-in-0.4/. "$V"/
  echo "vosk-model-small-en-in-0.4" > "$V/uuid"; rm -rf "$tmp"
fi
echo "Speech files ready."
