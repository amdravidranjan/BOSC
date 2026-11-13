"""Deterministic generation requests; no API calls or fabricated completed data."""
import argparse
import hashlib
import json
import random
from collections import Counter
from common import ROOT, write_json

COUNTS={'train':3000,'calib':600,'dev':300,'test_synth':300}
HARD={'G1','G2','G3','G4','G5','G8','G9'}
PERSONAS=['senior first-time UPI user','panicked professional','sceptical student',
          'distracted parent','homemaker','small shop owner']


def plan(scale=1.):
    if not 0 < scale <= 1: raise ValueError('scale must be in (0,1]')
    seen=set(); rows=[]
    for si,(split,target) in enumerate(COUNTS.items()):
        rng=random.Random(13+si)
        n=round(target*scale)
        if n<60: raise ValueError('Use at least 60 per class per split for taxonomy coverage')
        for label in (0,1):
            pool=([f'S{i}' for i in range(1,11)]*2+['S1','S10'] if label else
                  [f'G{i}' for i in range(1,10)])
            types=[pool[i%len(pool)] for i in range(n)]; rng.shuffle(types)
            langs=['hinglish']*round(n*.15)+['en']*(n-round(n*.15)); rng.shuffle(langs)
            polite=set(i for i,t in enumerate(types) if label and t=='S1')
            remaining=[i for i in range(n) if i not in polite]; rng.shuffle(remaining)
            if label: polite.update(remaining[:max(0,round(n*.25)-len(polite))])
            for i,(typ,language) in enumerate(zip(types,langs)):
                while True:
                    attributes={'type_id':typ,'persona':rng.choice(PERSONAS),
                        'style':('polite' if i in polite else rng.choice(['slow_build','instant_panic','friendly_then_threat'])) if label else 'genuine',
                        'behaviour':rng.choice(['complies','hesitates','resists','asks to call back']),
                        'n_turns':rng.choice(list(range(6,25,2))), 'language':language,
                        'channel':rng.choice(['phone','WhatsApp call','video call'] if typ=='S2' else ['phone','WhatsApp call']),
                        'setting_seed':rng.randrange(2**31)}
                    fingerprint=hashlib.sha256(json.dumps(attributes,sort_keys=True).encode()).hexdigest()
                    if fingerprint not in seen: break
                seen.add(fingerprint)
                rows.append(dict(attributes,id=f'{split}-{label}-{i:05}',scenario_seed=fingerprint,
                                 split=split,label=label,hard_negative=typ in HARD,
                                 prompt_version='v1' if split in ('train','calib') else 'v2'))
    return rows


def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--scale',type=float,default=1.)
    args=ap.parse_args(); rows=plan(args.scale)
    dest=ROOT/'data-eval/v2'; dest.mkdir(exist_ok=True)
    path=dest/'generation_plan.jsonl'
    path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    counts={}
    for split in COUNTS:
        rs=[r for r in rows if r['split']==split]; scam=[r for r in rs if r['label']]
        counts[split]={'planned_calls':len(rs),'labels':dict(Counter(r['label'] for r in rs)),
            'types':dict(Counter(r['type_id'] for r in rs)),
            'languages':dict(Counter(r['language'] for r in rs)),
            'polite_scam_fraction':sum(r['style']=='polite' for r in scam)/len(scam),
            'hard_negative_fraction':sum(r['hard_negative'] for r in rs)/sum(not r['label'] for r in rs)}
    write_json(dest/'generation_plan_summary.json',{'status':'planned, not generated','scale':args.scale,
               'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'counts':counts})
    print('Planned',len(rows),'calls; generated 0. Provider, credentials and spending limit required.')


if __name__=='__main__': main()
