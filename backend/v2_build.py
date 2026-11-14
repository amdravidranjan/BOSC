"""Build the Mac v2 splits: v1 (BothBosu + templates) + hand-written (AI) Indian calls + ASR copies + text noise.

  select   -> artifacts/v2/asr_input.jsonl  (every calib/dev/test call + ~50% of train get ASR copies)
  assemble -> data-eval/v2/splits/{train,calib,dev,test_synth}.jsonl + manifest.json

Never reads the lockbox. ASR copies keep their parent's split (siblings never cross splits).
"""
import argparse
import hashlib
import json
import random
import re
from collections import Counter

from common import ART, ROOT, SPLITS, digest, load_jsonl, write_json
from v2_text_noise import augment

V1 = ROOT / 'data-eval/v1'
HW = ROOT / 'data-eval/v2/handwritten/calls.jsonl'
OUT = ROOT / 'data-eval/v2/splits'


def h(s):
    return int(hashlib.sha256(s.encode()).hexdigest()[:8], 16)


def signature(c):
    return re.sub(r'\W+', ' ', ' '.join(t['text'] for t in c['turns'] if t['speaker'] == 'caller').lower()).strip()


def originals():
    split_calls = {s: load_jsonl(V1 / f'{s}.jsonl') for s in SPLITS}
    seen = {signature(c) for s in SPLITS for c in split_calls[s]}
    added = Counter()
    for c in load_jsonl(HW):
        if signature(c) in seen:
            continue
        seen.add(signature(c))
        split_calls[c['split']].append(c)
        added[c['split']] += 1
    return split_calls, added


def select():
    split_calls, _ = originals()
    # ~50% of every split gets ASR copies (train first, so training can start while eval copies run).
    chosen = [c for s in ('train', 'calib', 'dev', 'test_synth') for c in split_calls[s] if h(c['id']) % 2 == 0]
    dest = ART / 'v2/asr_input.jsonl'
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(''.join(json.dumps(c, ensure_ascii=False) + '\n' for c in chosen))
    print('ASR input calls:', len(chosen), dict(Counter(c['split'] for c in chosen)))


def assemble():
    split_calls, added = originals()
    asr = {}
    for f in (ART / 'v2/asr_local').glob('*.json'):
        c = json.loads(f.read_text())['call']
        if c['phone_path'] == 'B' and c['split'] != 'test_synth':
            continue  # pilot leftovers: path B is evaluation-only (test)
        if c['turns']:  # a copy where Whisper heard nothing is unusable
            asr.setdefault(c['parent_id'], []).append(c)
    OUT.mkdir(parents=True, exist_ok=True)
    counts, rng = {}, random.Random(13)
    for s in SPLITS:
        rows = list(split_calls[s])
        for c in split_calls[s]:
            rows.extend(asr.get(c['id'], []))
        if s == 'train':  # text-noise copies of ~25% of clean train calls (marked, never called ASR)
            rows.extend(augment(c, 13) for c in split_calls[s] if rng.random() < .25)
        (OUT / f'{s}.jsonl').write_text(''.join(json.dumps(c, ensure_ascii=False) + '\n' for c in rows))
        counts[s] = {'calls': len(rows),
                     'clean_originals': len(split_calls[s]),
                     'asr_copies': sum(1 for c in rows if c.get('asr')),
                     'text_noise_copies': sum(1 for c in rows if c.get('augmentation') == 'text_noise'),
                     'sources': dict(Counter(c['source'] for c in rows)),
                     'labels': dict(Counter(str(c['label']) for c in rows)),
                     'handwritten_originals_added': added[s]}
    write_json(OUT / 'manifest.json', {'counts': counts, 'hashes': {s: digest(OUT / f'{s}.jsonl') for s in SPLITS},
                                       'asr': 'macOS say (Rishi/Aman/Tara) -> phone path A/B -> Silero VAD -> Whisper tiny.en int8 (phone files)',
                                       'lockbox_read': False})
    print(json.dumps(counts, indent=1))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('step', choices=['select', 'assemble'])
    {'select': select, 'assemble': assemble}[ap.parse_args().step]()
