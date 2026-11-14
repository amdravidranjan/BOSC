"""Python replica of the deployed neural detector (browser frontend/js/detector-onnx.js +
student-tokenizer.js + stream-model.js; Android port planned). Used to evaluate any exported student
folder + stream_model.json exactly as it runs on devices. `check()` verifies it against a folder's
onnx_parity_cases.json / stream_parity_cases.json (token IDs, logits, streamed risk).
"""
import json
import math
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

from bosc_core import TacticDetector

MAXLEN = 192


class Student:
    def __init__(self, folder, threads=4):
        folder = Path(folder)
        self.tok = Tokenizer.from_file(str(folder / 'tokenizer.json'))
        self.tok.no_truncation(); self.tok.no_padding()
        cfg = json.loads((folder / 'tokenizer_config.json').read_text())
        self.maxlen = min(int(cfg.get('model_max_length', MAXLEN)), MAXLEN)
        o = ort.SessionOptions(); o.intra_op_num_threads = threads
        self.sess = ort.InferenceSession(str(folder / 'onnx/model_quantized.onnx'), o, providers=['CPUExecutionProvider'])

    def encode(self, text):
        ids = [101] + self.tok.encode(text, add_special_tokens=False).ids[-(self.maxlen - 2):] + [102]
        mask = [1] * len(ids)
        pad = self.maxlen - len(ids)
        return ids + [0] * pad, mask + [0] * pad

    def logits(self, text):
        ids, mask = self.encode(text)
        a = lambda x: np.array([x], dtype=np.int64)
        return self.sess.run(['logits'], {'input_ids': a(ids), 'attention_mask': a(mask),
                                          'token_type_ids': a([0] * self.maxlen)})[0][0]

    def probs(self, text):
        return [1 / (1 + math.exp(-float(z))) for z in self.logits(text)]


def tree_value(node, f):
    while 'leaf_value' not in node:
        if node['decision_type'] != '<=':
            raise ValueError('Unsupported tree decision')
        node = node['left_child'] if f[node['split_feature']] <= float(node['threshold']) else node['right_child']
    return node['leaf_value']


class Stream:
    def __init__(self, cfg):
        self.cfg = cfg; self.reset()

    def reset(self):
        self.rows, self.first, self.ema, self.regex, self.risk, self.latched = [], {}, 0., set(), 0., False

    def features(self, p, tactics):
        t = len(self.rows); self.rows.append(p)
        for k in range(1, 9):
            if p[k] > .5 and k not in self.first:
                self.first[k] = t
        self.regex |= set(tactics)
        self.ema = .5 * p[0] + .5 * self.ema
        mx = [max(r[k] for r in self.rows) for k in range(9)]
        a, th = self.first.get(4), self.first.get(2)
        ab = int(a is not None and th is not None and a < th)
        tb = int(th is not None and any(k in self.first and th < self.first[k] for k in (5, 6, 7)))
        first = min(self.first.values()) if self.first else t
        return [*p, mx[0], sum(r[0] for r in self.rows) / len(self.rows), *mx[1:], sum(1 for q in mx[1:] if q > .5),
                sum(1 for r in self.rows if r[0] > .5), self.ema, ab, tb, t - first, t, len(self.regex)]

    def push(self, feats, p):
        c = self.cfg
        raw = 1 / (1 + math.exp(-sum(tree_value(t['tree_structure'], feats) for t in c['trees'])))
        x, y = c['isotonic']['x'], c['isotonic']['y']
        if raw <= x[0]:
            cal = y[0]
        else:
            cal = y[-1]
            for i in range(1, len(x)):
                if raw <= x[i]:
                    cal = y[i - 1] + (raw - x[i - 1]) / (x[i] - x[i - 1]) * (y[i] - y[i - 1]); break
        q = p[0] if c['mode'] == 'ema' else cal
        self.risk += (c['alpha_up'] if q > self.risk else c['alpha_down']) * (q - self.risk)
        if self.risk >= c['pause']:
            self.latched = True
        if self.latched:
            self.risk = max(self.risk, c['pause'])
        return raw, cal, self.risk


class NeuralScorer:
    """Caller utterances only; window = last 4 joined with ' | '."""
    def __init__(self, student_dir, stream_json, tactics_json):
        self.student = Student(student_dir)
        self.cfg = json.loads(Path(stream_json).read_text())
        self.det = TacticDetector(json.loads(Path(tactics_json).read_text()))
        self.stream = Stream(self.cfg); self.utts = []
        self.warn, self.pause = self.cfg['warn'], self.cfg['pause']

    def reset(self):
        self.stream.reset(); self.utts = []

    def level(self, r):
        return 'PAUSE' if r >= self.pause else 'WARN' if r >= self.warn else 'SAFE'

    def push(self, text):
        self.utts.append(text)
        p = self.student.probs(' | '.join(self.utts[-4:]))
        tactics = self.det.detect(text)
        _, _, risk = self.stream.push(self.stream.features(p, tactics), p)
        return {'p': p[0], 'risk': risk, 'level': self.level(risk), 'tactics': sorted(tactics)}


def check(student_dir, stream_json, tactics_json, onnx_cases, stream_cases):
    st = Student(student_dir)
    cases = json.loads(Path(onnx_cases).read_text())
    mism, err = 0, 0.
    for c in cases:
        ids, mask = st.encode(c['text'])
        mism += ids != c['input_ids'] or mask != c['attention_mask']
        err = max(err, float(np.max(np.abs(st.logits(c['text']) - np.array(c['logits'])))))
    calls = json.loads(Path(stream_cases).read_text())
    s = Stream(json.loads(Path(stream_json).read_text())); serr, steps = 0., 0
    for call in calls:
        s.reset()
        for row in call:
            serr = max(serr, abs(s.push(row['features'], row['features'][:9])[2] - row['risk'])); steps += 1
    ns = NeuralScorer(student_dir, stream_json, tactics_json); ferr, lv, turns = 0., 0, 0
    for call in calls[:10]:
        ns.reset()
        for row in call:
            r = ns.push(row['text']); ferr = max(ferr, abs(r['risk'] - row['risk'])); turns += 1
            lv += r['level'] != ns.level(row['risk'])
    return {'windows': len(cases), 'token_id_mismatches': mism, 'max_logit_error': err,
            'stream_calls': len(calls), 'stream_steps': steps, 'max_stream_risk_error': serr,
            'full_stream_calls': 10, 'full_stream_turns': turns, 'max_full_stream_risk_error': ferr, 'level_mismatches': lv}
