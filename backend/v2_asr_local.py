"""Phone-path ASR copies with OFFLINE macOS voices, in parallel.

Same pipeline as v2_asr.py (phone_audio paths A/B, Silero VAD + Whisper tiny.en int8 via sherpa-onnx 2.0.1
with the phone's exact files, time-provenance alignment with masked tactics) - only the TTS differs:
Edge-TTS (online, one request at a time) is replaced by the offline macOS Indian-English voices
(Rishi, Aman, Tara) at three speaking rates. Used for the Mac v2 run because the network was the bottleneck.
"""
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import soundfile as sf

from common import ART, write_json
import v2_asr

VOICES = ('Rishi', 'Aman', 'Tara')
RATES = (165, 185, 205)  # words per minute
OUT = ART / 'v2/asr_local'
_rec = None


def _init():
    global _rec
    import sherpa_onnx
    v2_asr.recognizer()  # runs the version / file / export checks
    a = v2_asr.ASSETS  # same files, 1 thread per worker (many workers instead)
    _rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(a / 'tiny.en-encoder.int8.onnx'), decoder=str(a / 'tiny.en-decoder.int8.onnx'),
        tokens=str(a / 'tiny.en-tokens.txt'), language='en', task='transcribe', num_threads=1)


def paths_for(call):
    # Bosc in-app calls are path A. Path B (8 kHz telephony) only for the test set.
    return ('A', 'B') if call['split'] == 'test_synth' else ('A',)


def synthesize(call, voice, rate):
    audio, spans, offset = [], [], 0
    with tempfile.TemporaryDirectory() as d:
        for i, turn in enumerate(t for t in call['turns'] if t['speaker'] == 'caller'):
            path = os.path.join(d, f'{i}.wav')
            for attempt in range(3):  # macOS `say` occasionally hangs forever: time out and retry
                try:
                    subprocess.run(['say', '-v', voice, '-r', str(rate), '--file-format=WAVE', '--data-format=LEI16@16000',
                                    '-o', path, turn['text']], check=True, timeout=60)
                    break
                except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
                    if attempt == 2:
                        raise
            x, sr = sf.read(path, dtype='float32')
            assert sr == 16000
            spans.append({'caller_turn': i, 'start_sample': offset, 'end_sample': offset + len(x)})
            audio.extend([x, np.zeros(8000, dtype=np.float32)])
            offset += len(x) + 8000
    return np.concatenate(audio), spans


def work(call):
    ident = hashlib.sha256(call['id'].encode()).hexdigest()
    if all((OUT / f'{ident}-{p}.json').exists() for p in paths_for(call)):
        return call['id'], 'cached'
    seed = int(ident[:8], 16)
    voice, rate = VOICES[seed % 3], RATES[(seed // 3) % 3]
    audio, spans = synthesize(call, voice, rate)
    for path in paths_for(call):
        snr = (20, 10)[seed % 2]; volume = (-6, 6)[(seed // 2) % 2]
        x = v2_asr.phone_audio(audio, path, snr, volume, seed)
        segments = v2_asr.transcribe(x, _rec)
        result = v2_asr.align(call, segments, spans, path)
        result['audio_config'] = {'tts': 'macOS say', 'voice': voice, 'rate_wpm': rate, 'snr_db': snr,
                                  'volume_db': volume, 'seed': seed}
        write_json(OUT / f'{ident}-{path}.json', {'call': result, 'segments': segments})
    return call['id'], 'done'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--input', type=Path, required=True)
    ap.add_argument('--workers', type=int, default=8)
    ap.add_argument('--limit', type=int)
    args = ap.parse_args()
    cs = [json.loads(s) for s in args.input.read_text().splitlines() if s.strip()]
    if args.limit:
        cs = cs[:args.limit]
    OUT.mkdir(parents=True, exist_ok=True)
    with Pool(args.workers, initializer=_init) as pool:
        for n, (cid, status) in enumerate(pool.imap_unordered(work, cs, chunksize=4), 1):
            if n % 50 == 0 or n == len(cs):
                print(f'{n}/{len(cs)} {status} {cid}', flush=True)


if __name__ == '__main__':
    main()
