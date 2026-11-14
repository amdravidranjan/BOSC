"""Mac v2 evaluation: every system on the v2 test split, broken down by audio path and subgroup,
plus the external set of 38 Whisper-transcribed calls (real phone captures + TTS->Whisper + clean).

Systems run exactly as deployed (their own exported thresholds). Rules-only thresholds use the
train.py policy on v2 dev. 95% call-bootstrap CIs via train.summarize. Never reads the lockbox.
"""
import argparse
import os
import json
from pathlib import Path

import numpy as np

from bosc_core import Model, StreamingScorer, TacticDetector
from common import ART, ROOT, calls, write_json
from train import FA_PAUSE, FA_WARN, call_metrics, caller_utts, pick_threshold, summarize
import v2_runtime

TACTICS = ROOT / 'frontend/model/tactics.json'
V1 = ART / 'v1_release'
V2 = Path(os.environ.get('BOSC_V2_RELEASE', ART / 'v2/release'))
EXTERNAL = Path('/private/tmp/claude-501/-Users-rishigopinath/6c5023a0-342e-41da-b962-73f36ad2a322/scratchpad/eval/sets.json')


class Rules:
    """Regex tactics only (ablation row A): raw = min(1, distinct pressure tactics / 4), same smoothing."""
    def __init__(self, warn=.5, pause=.7):
        self.det = TacticDetector(json.loads(TACTICS.read_text())); self.warn, self.pause = warn, pause

    def reset(self): self.seen, self.risk = set(), 0.

    def push(self, text):
        self.seen |= set(self.det.detect(text))
        raw = min(1., len(self.seen & self.det.PRESSURE) / 4)
        self.risk += (.6 if raw > self.risk else .15) * (raw - self.risk)
        return {'risk': self.risk}


class Stage0:
    def __init__(self, path):
        mj = json.loads(Path(path).read_text())
        self.sc = StreamingScorer(Model(mj), TacticDetector(json.loads(TACTICS.read_text())), mj['scorer'])
        self.warn, self.pause = mj['scorer']['warn'], mj['scorer']['pause']

    def reset(self): self.sc.reset()

    def push(self, text): return self.sc.push(text)


class Neural:
    def __init__(self, folder, stream):
        self.ns = v2_runtime.NeuralScorer(folder, stream, TACTICS); self.warn, self.pause = self.ns.warn, self.ns.pause

    def reset(self): self.ns.reset()

    def push(self, text): return self.ns.push(text)


def trace(sys_, utts):
    sys_.reset()
    return [sys_.push(u)['risk'] for u in utts]


def systems():
    out = {}
    rules = Rules()
    peaks = [max(trace(rules, caller_utts(c)), default=0.) for c in calls('dev') if c['label'] == 0]
    rules.pause = pick_threshold(peaks, FA_PAUSE); rules.warn = min(pick_threshold(peaks, FA_WARN), rules.pause)
    out['A_rules'] = rules
    out['B_stage0_v1'] = Stage0(V1 / 'model.json')
    if (ART / 'v2/stage0/model.json').exists():
        out['B_stage0_v2'] = Stage0(ART / 'v2/stage0/model.json')
    out['G_student_v1'] = Neural(V1 / 'student', V1 / 'stream_model.json')

    if (V2 / 'student/onnx/model_quantized.onnx').exists():
        out['G_student_v2'] = Neural(V2 / 'student', V2 / 'stream_model.json')
    return out


def subsets(test):
    clean = [c for c in test if not c.get('asr') and not c.get('augmentation')]
    groups = {'clean': clean,
              'asr_A': [c for c in test if c.get('asr') and c.get('phone_path') == 'A'],
              'asr_B': [c for c in test if c.get('asr') and c.get('phone_path') == 'B']}
    for name, rows in list(groups.items()):
        groups[name + '/hf'] = [c for c in rows if c['source'] == 'hf']
        groups[name + '/templates'] = [c for c in rows if c['source'] == 'gen']
        groups[name + '/handwritten'] = [c for c in rows if c['source'] == 'handwritten_ai']
        groups[name + '/S10'] = [c for c in rows if c['type'].startswith('S10')] + [c for c in rows if c['label'] == 0]
        groups[name + '/polite'] = [c for c in rows if c.get('style') == 'polite'] + [c for c in rows if c['label'] == 0]
        groups[name + '/hinglish'] = [c for c in rows if c.get('language') == 'hinglish']
    return groups


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--out', default=str(ROOT / 'data-eval/results/v2_mac'))
    args = ap.parse_args(); out = Path(args.out)
    test = calls('test_synth'); syss = systems(); groups = subsets(test)
    traces = {}
    for name, s in syss.items():
        traces[name] = {c['id']: trace(s, caller_utts(c)) for c in test}
        print('scored', name, flush=True)
    results = {'thresholds': {n: {'warn': s.warn, 'pause': s.pause} for n, s in syss.items()}, 'tests': {}}
    for g, rows in groups.items():
        if not rows or not any(c['label'] for c in rows):
            continue
        results['tests'][g] = {n: summarize(call_metrics([traces[n][c['id']] for c in rows], rows, s.warn, s.pause))
                               for n, s in syss.items()}
    # Per-type flag rates on clean + ASR-A test
    types = sorted({c['type'] for c in test})
    results['per_type'] = {path: {t: {n: float(np.mean([max(traces[n][c['id']], default=0) >= s.pause
                                                         for c in groups[path] if c['type'] == t] or [np.nan]))
                                      for n, s in syss.items()} for t in types} for path in ('clean', 'asr_A')}
    # External set (38 calls; written by the same AI author as the hand-written training calls: indicative only)
    if EXTERNAL.exists():
        ext = json.loads(EXTERNAL.read_text()); results['external'] = {}
        for subset in ('real', 'tts', 'clean'):
            rows = [c for c in ext if c['set'] == subset]
            for lang in ('English', 'other'):
                rs = [c for c in rows if (c['language'] == 'English') == (lang == 'English')]
                if not rs: continue
                res = {}
                for n, s in syss.items():
                    tr = [trace(s, c['utterances']) for c in rs]
                    sc = [max(t, default=0) >= s.pause for t, c in zip(tr, rs) if c['scam']]
                    ge = [(max(t, default=0) >= s.pause, max(t, default=0) >= s.warn) for t, c in zip(tr, rs) if not c['scam']]
                    res[n] = {'scams_paused': f'{sum(sc)}/{len(sc)}', 'genuine_paused': f'{sum(p for p, _ in ge)}/{len(ge)}',
                              'genuine_warned': f'{sum(w for _, w in ge)}/{len(ge)}'}
                results['external'][f'{subset}/{lang}'] = res
    write_json(out / 'eval.json', results)
    # Markdown summary
    names = list(syss)
    lines = ['| Test subset | ' + ' | '.join(names) + ' |', '|---|' + '---|' * len(names)]
    for g, r in results['tests'].items():
        cell = lambda m: f"{m['recall_pause']*100:.1f}% / {m['false_alarm_pause']*100:.1f}%" if m['n_genuine'] else f"{m['recall_pause']*100:.1f}% / -"
        lines.append(f"| {g} (n={r[names[0]]['n_scam']}s/{r[names[0]]['n_genuine']}g) | " + ' | '.join(cell(r[n]) for n in names) + ' |')
    (out / 'eval_table.md').write_text('Cells: PAUSE recall / PAUSE false alarms\n\n' + '\n'.join(lines) + '\n')
    print('\n'.join(lines))
    if 'external' in results:
        print('\nExternal (scams paused | genuine paused | genuine warned):')
        for k, v in results['external'].items():
            print(' ', k, ' | '.join(f"{n}: {m['scams_paused']} {m['genuine_paused']} {m['genuine_warned']}" for n, m in v.items()))


if __name__ == '__main__':
    main()
