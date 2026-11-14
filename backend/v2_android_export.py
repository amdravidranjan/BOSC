"""Export everything the Android port of Stage 0 v2 + MiniLM (round 1) needs, plus parity cases.

Writes artifacts/v2/android/:
  ensemble.json        dev-genuine peak tables for both models + ensemble thresholds (2% PAUSE / 5% WARN on dev)
  tokenizer_cases.json Python WordPiece ids for many real windows (clean, Whisper, Hinglish, noisy)
  ensemble_cases.json  per-turn Stage 0 risk, student risk and ensemble score for test calls (end-to-end parity)
  report.json          test results of the exported ensemble (with and without the keyword rules)
"""
import json, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, '.')
import v2_eval as E
import v2_runtime
from common import ART, calls
from train import caller_utts

OUT = ART / 'v2/android'; OUT.mkdir(parents=True, exist_ok=True)
R1 = ART / 'v2/release_r1'
S0 = E.Stage0(ART / 'v2/stage0/model.json')
ST = E.Neural(R1 / 'student', R1 / 'stream_model.json')
RU = E.systems_rules() if hasattr(E, 'systems_rules') else None

def peaks(sys_, cs): return np.array([max(E.trace(sys_, caller_utts(c)), default=0.) for c in cs])

dev, test = calls('dev'), calls('test_synth')
yd = np.array([c['label'] for c in dev]); yt = np.array([c['label'] for c in test])
rules = E.Rules()
pk = {}
for name, s in (('stage0', S0), ('student', ST), ('rules', rules)):
    pk[name] = {'dev': peaks(s, dev), 'test': peaks(s, test)}
    print('scored', name, flush=True)
tables = {n: sorted(float(x) for x in pk[n]['dev'][yd == 0]) for n in pk}

def cdf(n, x):
    g = np.array(tables[n]); return np.searchsorted(g, x, side='right') / len(g)

def thr(scores_dev, fa):
    g = np.sort(scores_dev[yd == 0]); return float(g[int(np.ceil((1 - fa) * len(g))) - 1]) + 1e-9

report = {}
for combo in (('stage0', 'student'), ('stage0', 'student', 'rules')):
    key = '+'.join(combo)
    ed = np.max([cdf(n, pk[n]['dev']) for n in combo], axis=0)
    et = np.max([cdf(n, pk[n]['test']) for n in combo], axis=0)
    pause, warn = thr(ed, .02), thr(ed, .05)
    warn = min(warn, pause)
    res = {'pause': pause, 'warn': warn}
    for lab, m in (('clean', np.array([not c.get('asr') for c in test])),
                   ('asr_A', np.array([c.get('phone_path') == 'A' for c in test])),
                   ('asr_B', np.array([c.get('phone_path') == 'B' for c in test]))):
        y, x = yt[m], et[m] >= pause
        res[lab] = {'recall_pause': float(x[y == 1].mean()), 'false_alarm_pause': float(x[y == 0].mean()),
                    'false_alarm_warn': float((et[m] >= warn)[y == 0].mean()), 'n_scam': int((y == 1).sum()), 'n_genuine': int((y == 0).sum())}
    report[key] = res
    print(key, json.dumps(res))
ens = report['stage0+student']
json.dump({'format': 'bosc-ensemble-max-cdf-v1',
           'note': 'score = max over models of (fraction of genuine dev calls whose peak risk <= this risk). Equivalent per turn and per call.',
           'models': {'stage0': {'model_sha256': json.loads((ART / 'v2/stage0/info.json').read_text())['model_sha256'], 'genuine_dev_peaks': tables['stage0']},
                      'student': {'release': 'v2/release_r1', 'genuine_dev_peaks': tables['student']},
                      'rules': {'genuine_dev_peaks': tables['rules']}},
           'members': ['stage0', 'student'], 'warn': ens['warn'], 'pause': ens['pause'],
           'rules_thresholds': {'warn': rules.warn, 'pause': rules.pause},
           'members_with_rules': ['stage0', 'student', 'rules'], 'warn_with_rules': report['stage0+student+rules']['warn'],
           'pause_with_rules': report['stage0+student+rules']['pause']},
          open(OUT / 'ensemble.json', 'w'))
json.dump(report, open(OUT / 'report.json', 'w'), indent=1)

# Tokenizer parity: every window the student sees, for a spread of calls
tok = ST.ns.student.tok
rng = np.random.default_rng(13)
pool = [c for c in test] + [c for c in dev]
pick = [pool[i] for i in rng.choice(len(pool), 400, replace=False)]
texts = set()
for c in pick:
    u = caller_utts(c)
    for i in range(len(u)): texts.add(' | '.join(u[max(0, i - 3):i + 1]))
extra = ['Café naïve résumé — “quotes” ‘single’ … ₹50,000!!', 'नमस्ते, आपका KYC pending है।', '你好 OTP 1234', 'tab\there\nnewline  double  space',
         'A' * 150, 'x' * 101 + ' ok', 'emoji 😀 ok', 'ÀÉÎÕÜ ñ ß ø', 'don\'t won\'t I\'m', 'e-mail user@bank.com http://x.y/z?q=1', '']
texts = sorted(texts) + extra
cases = [{'text': t, 'input_ids': ST.ns.student.encode(t)[0]} for t in texts]
json.dump(cases, open(OUT / 'tokenizer_cases.json', 'w'), ensure_ascii=False)
print('tokenizer cases', len(cases))

# End-to-end parity: 40 test calls (mixed), per turn: stage0 risk, student risk, ensemble score
sel = [test[i] for i in rng.choice(len(test), 40, replace=False)]
out = []
for c in sel:
    u = caller_utts(c); a = E.trace(S0, u); b = E.trace(ST, u)
    e = [max(float(cdf('stage0', x)), float(cdf('student', y))) for x, y in zip(a, b)]
    out.append({'id': c['id'], 'label': c['label'], 'utterances': u, 'stage0': a, 'student': b, 'ensemble': e})
json.dump(out, open(OUT / 'ensemble_cases.json', 'w'), ensure_ascii=False)
print('ensemble cases', len(out), 'turns', sum(len(x['utterances']) for x in out))
