"""Shared, caller-only data and evaluation for the available-data experiment."""
import hashlib
import json
import random
import subprocess
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import numpy as np
from sklearn.metrics import average_precision_score, f1_score, roc_auc_score

from train import caller_utts, call_metrics, load_hf, load_jsonl, summarize

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'backend/artifacts'
DATA = ROOT / 'data-eval/v1'
LABELS = ['scam', 'urgency', 'threat', 'secrecy_isolation', 'authority_claim',
          'credential_request', 'remote_access', 'payment_instruction', 'stay_on_line']
SPLITS = ['train', 'calib', 'dev', 'test_synth']


def write_json(path, obj):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2, allow_nan=False) + '\n')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def record(stage, config, metrics):
    stamp = datetime.now(ZoneInfo('Asia/Kolkata')).strftime('%Y%m%d-%H%M%S')
    path = ROOT / 'backend/runs' / stage / (stamp + '-available-data')
    config = dict(config, git_commit=subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        data_sha256={s: digest(DATA / (s + '.jsonl')) for s in SPLITS},
        source_sha256={str(p.relative_to(ROOT)):digest(p) for p in sorted((ROOT/'backend').glob('*.py'))},
        scope='Synthetic available-data experiment; no LLM/ASR/human lockbox data')
    write_json(path / 'config.json', config)
    write_json(path / 'metrics.json', metrics)
    write_json(ART / (stage + '_metrics.json'), metrics)
    return path


def calls(split):
    return load_jsonl(DATA / (split + '.jsonl'))


def windows(cs, width=4):
    texts, ys, masks, groups = [], [], [], []
    for ci, c in enumerate(cs):
        turns = [t for t in c['turns'] if t['speaker'] == 'caller']
        fs = c['first_suspicious_caller_turn']
        for i, turn in enumerate(turns):
            texts.append(' | '.join(t['text'] for t in turns[max(0, i-width+1):i+1]))
            ys.append([int(c['label'] == 1 and fs is not None and i >= fs)] +
                      [int(k in turn.get('tactics', [])) for k in LABELS[1:]])
            masks.append([1] + [int(c['source'] != 'hf')] * 8)
            groups.append(ci)
    return texts, np.asarray(ys, np.float32), np.asarray(masks, np.float32), np.asarray(groups)


def window_metrics(y, p, mask):
    assert np.isfinite(p).all()
    valid = mask[:, 1].astype(bool)
    out = {'pr_auc': float(average_precision_score(y[:, 0], p[:, 0])),
           'roc_auc': float(roc_auc_score(y[:, 0], p[:, 0])),
           'f1': float(f1_score(y[:, 0], p[:, 0] >= .5, zero_division=0)),
           'n_windows': len(y)}
    if valid.any():
        f = f1_score(y[valid, 1:], p[valid, 1:] >= .5, average=None, zero_division=0)
        out.update(tactic_macro_f1=float(f.mean()), tactic_f1=dict(zip(LABELS[1:], f.tolist())))
    return out


def threshold(peaks, fa):
    # No clipping: a saturated genuine score may require a threshold > 1.
    p = np.sort(np.asarray(peaks, dtype=float))
    if not len(p):
        raise ValueError('Threshold selection needs genuine dev calls')
    k = int(np.floor(fa * len(p)))
    return float(np.nextafter(p[len(p)-k-1], np.inf))


def traces(p, cs, groups, up=.6, down=.15):
    out = []
    for i, _ in enumerate(cs):
        risk, row = 0., []
        for q in p[groups == i]:
            risk += (up if q > risk else down) * (float(q)-risk)
            row.append(risk)
        out.append(row)
    return out


def evaluate_probs(dev_p, split_p, up=.6, down=.15):
    dc = calls('dev')
    dg = windows(dc)[3]
    dt = traces(dev_p, dc, dg, up, down)
    peaks = [max(t, default=0) for c, t in zip(dc, dt) if not c['label']]
    pause, warn = threshold(peaks, .02), threshold(peaks, .05)
    out = {'thresholds': {'warn': warn, 'pause': pause}, 'up': up, 'down': down}
    for split, p in dict(split_p, dev=dev_p).items():
        cs = calls(split)
        ts = traces(p, cs, windows(cs)[3], up, down)
        out[split] = summarize(call_metrics(ts, cs, warn, pause))
        for source in ('gen', 'hf'):
            indices = [i for i, c in enumerate(cs) if c['source'] == source]
            if indices:
                out[split + '_' + source] = summarize(call_metrics(
                    [ts[i] for i in indices], [cs[i] for i in indices], warn, pause))
    return out
