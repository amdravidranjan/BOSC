"""Convert the hand-written call batches (data-eval/v2/handwritten/*.txt) into v2-schema JSONL.

The calls were written by Claude (an AI model) during the Android/Mac v2 run, as a free substitute for
paid LLM generation. They are labelled source='handwritten_ai' and are NOT human-written lockbox data.
Each call is validated with v2_data.validate. Splits are assigned deterministically from the call id.
"""
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

from common import ROOT, write_json
from v2_data import validate

CODES = {'U': 'urgency', 'T': 'threat', 'S': 'secrecy_isolation', 'A': 'authority_claim',
         'C': 'credential_request', 'R': 'remote_access', 'P': 'payment_instruction', 'L': 'stay_on_line'}
SRC = ROOT / 'data-eval/v2/handwritten'


def split_for(ident):
    h = int(hashlib.sha256(ident.encode()).hexdigest()[:8], 16) % 100
    return 'train' if h < 60 else 'calib' if h < 70 else 'dev' if h < 85 else 'test_synth'


def parse(path):
    calls, cur = [], None
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('==='):
            typ, lang, style = [x.strip() for x in line[3:].split('|')]
            cur = {'type': typ, 'language': lang, 'style': style, 'turns': []}
            calls.append(cur)
            continue
        who, text = line[:2], line[2:].strip()
        tactics = []
        if '##' in text:
            text, codes = text.split('##')
            tactics = sorted({CODES[c] for c in codes.split()}, key=list(CODES.values()).index)
            text = text.strip()
        cur['turns'].append({'speaker': 'caller' if who == 'C:' else 'receiver', 'text': text, 'tactics': tactics})
    return calls


def main():
    out, counts = [], Counter()
    for path in sorted(SRC.glob('batch*.txt')):
        for i, c in enumerate(parse(path)):
            ident = f'hw-{path.stem}-{i:03d}-{c["type"]}'
            label = int(c['type'].startswith('S'))
            caller = [t for t in c['turns'] if t['speaker'] == 'caller']
            fs = next((j for j, t in enumerate(caller) if t['tactics']), None) if label else None
            style = c['style']
            # v2 schema: a "polite" scam must contain no threat label.
            if style == 'polite' and any('threat' in t['tactics'] for t in caller):
                style = 'slow_build'
            call = {'id': ident, 'scenario_seed': ident, 'split': split_for(ident), 'label': label,
                    'type': c['type'], 'language': c['language'], 'style': style,
                    'source': 'handwritten_ai', 'turns': c['turns'], 'first_suspicious_caller_turn': fs}
            validate(call)
            out.append(call)
            counts[(call['split'], label)] += 1
    dest = ROOT / 'data-eval/v2/handwritten/calls.jsonl'
    dest.write_text(''.join(json.dumps(c, ensure_ascii=False) + '\n' for c in out))
    summary = {'calls': len(out), 'by_split_label': {f'{s}/{"scam" if l else "genuine"}': n for (s, l), n in sorted(counts.items())},
               'by_type': dict(sorted(Counter(c['type'] for c in out).items())),
               'by_language': dict(Counter(c['language'] for c in out)),
               'by_style': dict(Counter(c['style'] for c in out)),
               'author': 'Claude (AI), written in-session; not human-written; not lockbox'}
    write_json(ROOT / 'data-eval/v2/handwritten/summary.json', summary)
    print(json.dumps(summary, indent=1))


if __name__ == '__main__':
    main()
