"""Retrain Stage 0 (TF-IDF + LR, same fixed configuration) on the v2 splits and export it in the
exact v1 format (bosc-tfidf-lr-v1) plus parity cases, so the browser and Android load it unchanged.

Thresholds use train.py's policy on v2 dev (all dev calls: clean + ASR copies):
PAUSE = <=2% of genuine dev calls ever cross, WARN = <=5% (WARN <= PAUSE).
Writes to backend/artifacts/v2/stage0/ (promotion to frontend/model happens only after evaluation).
"""
import json
import random
import time

from bosc_core import Model, StreamingScorer, TacticDetector
from common import ART, ROOT, calls, digest, write_json
from train import ALPHA_DOWN, ALPHA_UP, FA_PAUSE, FA_WARN, MAX_FEATURES, caller_utts, fit, pick_threshold

W, C, TW = 4, .5, .3
OUT = ART / 'v2/stage0'


def main():
    t0 = time.time()
    train, dev, test = calls('train'), calls('dev'), calls('test_synth')
    mj, _, _ = fit(train, W, C)
    det = TacticDetector(json.loads((ROOT / 'frontend/model/tactics.json').read_text()))
    probe = StreamingScorer(Model(mj), det, {'window': W, 'tactic_weight': TW, 'alpha_up': ALPHA_UP,
                                             'alpha_down': ALPHA_DOWN, 'warn': .5, 'pause': .7})
    peaks = []
    for c in dev:
        if c['label'] == 0:
            probe.reset()
            peaks.append(max([probe.push(u)['risk'] for u in caller_utts(c)], default=0.))
    pause = pick_threshold(peaks, FA_PAUSE)
    warn = min(pick_threshold(peaks, FA_WARN), pause)
    out = {'format': 'bosc-tfidf-lr-v1', 'created': time.strftime('%Y-%m-%d %H:%M'),
           'vocab': mj['vocab'], 'idf': [round(v, 6) for v in mj['idf']], 'coef': [round(v, 6) for v in mj['coef']],
           'intercept': round(mj['intercept'], 6),
           'scorer': {'window': W, 'tactic_weight': TW, 'alpha_up': ALPHA_UP, 'alpha_down': ALPHA_DOWN,
                      'warn': round(warn, 6), 'pause': round(pause, 6)},
           'train_info': {'C': C, 'n_train_calls': len(train), 'max_features': MAX_FEATURES,
                          'data': 'v2 splits: v1 + hand-written (AI) Indian calls + Whisper ASR copies + text noise'}}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'model.json').write_text(json.dumps(out, separators=(',', ':')))
    # Parity cases on the rounded, exported weights (same layout as backend/parity_cases.json).
    exp = json.loads((OUT / 'model.json').read_text())
    m = Model(exp); sc = StreamingScorer(m, det, exp['scorer'])
    rng = random.Random(11)
    pool = [u for c in test for u in caller_utts(c)]
    cases = rng.sample(pool, 200) + ['', 'OTP', "Don't tell anyone, transfer 50,000 now!", 'o.t.p 1,23,456 ok']
    stream_call = caller_utts(next(c for c in test if c['type'] == 'S2_digital_arrest' and not c.get('asr')))
    sc.reset()
    write_json(OUT / 'parity_cases.json', {
        'proba': [{'text': t, 'p': m.proba(t)} for t in cases],
        'tactics': [{'text': t, 'found': sorted(det.detect(t))} for t in cases],
        'stream': {'utterances': stream_call, 'risk': [sc.push(u)['risk'] for u in stream_call]}})
    write_json(OUT / 'info.json', {'model_sha256': digest(OUT / 'model.json'), 'warn': warn, 'pause': pause,
                                   'genuine_dev_calls': len(peaks), 'train_calls': len(train),
                                   'vocab': len(mj['vocab']), 'seconds': round(time.time() - t0, 1)})
    print(json.dumps(json.loads((OUT / 'info.json').read_text()), indent=1))


if __name__ == '__main__':
    main()
