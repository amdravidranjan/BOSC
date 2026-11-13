"""Validate, deduplicate and queue actual generated calls for human review.

No generated calls are invented here; no automated check is labelled human review.
Run on clean originals before generating ASR copies. Review data stays git-ignored.
"""
import argparse
import hashlib
import json
import math
import random
import re
from collections import Counter
from pathlib import Path
import numpy as np
from common import ROOT, ART, LABELS, write_json, digest
from v2_plan import HARD

SPLITS=('test_synth','dev','calib','train')


def words(text): return re.findall(r'\w+',text.lower())
def transcript(call): return ' '.join(t['text'] for t in call['turns'] if t['speaker']=='caller')
def ngrams(call):
    ws=words(transcript(call)); return {tuple(ws[i:i+12]) for i in range(len(ws)-11)}


def validate(call):
    required=('id','scenario_seed','split','label','type','language','style','source','turns','first_suspicious_caller_turn')
    if any(k not in call for k in required): raise ValueError('Missing required metadata')
    if call['split'] not in SPLITS or call['language'] not in ('en','hinglish'): raise ValueError('Invalid split/language')
    typ=call['type'].split('_')[0]
    if typ not in {f'S{i}' for i in range(1,11)}|{f'G{i}' for i in range(1,10)}: raise ValueError('Invalid type')
    if call['label']!=int(typ.startswith('S')): raise ValueError('Type/label mismatch')
    turns=call['turns']
    if not 6<=len(turns)<=24 or len(turns)%2: raise ValueError('Expected 6–24 alternating turns')
    for i,t in enumerate(turns):
        if t.get('speaker')!=('caller' if i%2==0 else 'receiver') or not t.get('text','').strip():
            raise ValueError('Invalid speaker order/text')
        if not isinstance(t.get('tactics'),list) or set(t['tactics'])-set(LABELS[1:]): raise ValueError('Invalid tactic annotation')
        if re.search(r'(?:\d[\s-]?){8,}',t['text']): raise ValueError('Possible phone/account number')
        if not call['label'] and {'payment_instruction','threat'}<=set(t['tactics']): raise ValueError('Invalid genuine pressure combination')
    fs=call['first_suspicious_caller_turn']; caller=turns[::2]
    if call['label']:
        if type(fs) is not int or not 0<=fs<len(caller): raise ValueError('Invalid suspicious boundary')
        if not any(t['tactics'] for t in caller[fs:]): raise ValueError('Scam without annotated manipulation')
        if call['style']=='polite' and any('threat' in t['tactics'] for t in caller): raise ValueError('Polite scam contains threat label')
    elif fs is not None: raise ValueError('Genuine suspicious boundary must be null')
    return call


def clean(calls,embeddings,cosine=.9):
    if len(calls)!=len(embeddings): raise ValueError('Embedding count mismatch')
    e=np.asarray(embeddings,dtype=np.float32)
    if not np.isfinite(e).all() or np.any(np.linalg.norm(e,axis=1)<1e-8): raise ValueError('Invalid embeddings')
    e=e/np.linalg.norm(e,axis=1,keepdims=True)
    kept=[]; dropped=[]; grams={}; seeds={}; signatures={}
    for i in sorted(range(len(calls)),key=lambda j:(SPLITS.index(calls[j]['split']),calls[j]['id'])):
        c=calls[i]; signature=' '.join(words(transcript(c)))
        reason=None; match=None
        if c['scenario_seed'] in seeds: reason='duplicate_scenario_seed'; match=seeds[c['scenario_seed']]
        elif signature in signatures: reason='exact_transcript'; match=signatures[signature]
        else:
            for gram in sorted(ngrams(c)):
                if gram in grams and grams[gram][0]!=c['split']:
                    reason='cross_split_12gram'; match=grams[gram][1]; break
        if reason is None and kept:
            scores=e[kept]@e[i]; best=int(scores.argmax())
            if scores[best]>cosine: reason='cosine_gt_0.90'; match=calls[kept[best]]['id']
        if reason:
            dropped.append({'id':c['id'],'reason':reason,'matches':match}); continue
        kept.append(i); seeds[c['scenario_seed']]=c['id']; signatures[signature]=c['id']
        for gram in ngrams(c): grams[gram]=(c['split'],c['id'])
    return [calls[i] for i in kept],dropped


def review_sample(calls):
    # Stratify by split, class, language and hard-negative status; round up.
    buckets={}
    for c in calls:
        hard=c['type'].split('_')[0] in HARD
        buckets.setdefault((c['split'],c['label'],c['language'],hard),[]).append(c)
    sample=[]; rng=random.Random(13)
    for key,rows in sorted(buckets.items()):
        count=math.ceil(len(rows)*(.10 if key[-1] else .05))
        for c in rng.sample(rows,count):
            sample.append({'id':c['id'],'call_sha256':hashlib.sha256(json.dumps(c,sort_keys=True).encode()).hexdigest(),
                           'status':'pending','reviewer':None,'reason':None,'call':c})
    return sample


def embed_whole_calls(calls,device):
    from sentence_transformers import SentenceTransformer
    model=SentenceTransformer(str(ART/'pretrained/minilm'),device=device)
    # Chunk token IDs, not characters: every caller token contributes, never just
    # the first 256 tokens of a long conversation. Pool normalised chunk vectors.
    texts=[]; owners=[]; weights=[]
    for i,c in enumerate(calls):
        ids=model.tokenizer.encode(transcript(c),add_special_tokens=False)
        for start in range(0,len(ids),200):
            part=ids[start:start+200]
            texts.append(model.tokenizer.decode(part)); owners.append(i); weights.append(len(part))
    z=model.encode(texts,batch_size=64,normalize_embeddings=True,show_progress_bar=True)
    result=np.zeros((len(calls),z.shape[1]),dtype=np.float32)
    for vec,owner,weight in zip(z,owners,weights): result[owner]+=weight*vec
    return result/np.linalg.norm(result,axis=1,keepdims=True)


def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--input',type=Path,required=True); ap.add_argument('--device',default='cpu')
    args=ap.parse_args(); rows=[json.loads(s) for s in args.input.read_text().splitlines() if s.strip()]
    valid=[]; rejected=[]
    for c in rows:
        try: valid.append(validate(c))
        except (ValueError,TypeError,KeyError) as e: rejected.append({'id':c.get('id'),'reason':str(e)})
    if not valid: raise ValueError('No valid generated calls')
    kept,dropped=clean(valid,embed_whole_calls(valid,args.device))
    dest=ROOT/'data-eval/v2/private'; dest.mkdir(parents=True,exist_ok=True)
    output=dest/'clean_pending_review.jsonl'
    output.write_text(''.join(json.dumps(c,ensure_ascii=False)+'\n' for c in kept))
    review=dest/'human_review.jsonl'
    if review.exists(): raise FileExistsError('Preserve existing human review; choose a fresh version')
    review.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in review_sample(kept)))
    write_json(dest/'cleaning_log.json',{'schema_rejections':rejected,'dedupe_drops':dropped})
    write_json(ROOT/'data-eval/v2/cleaning_summary.json',{
        'input_sha256':digest(args.input),'clean_sha256':digest(output),'review_sha256':digest(review),
        'input_calls':len(rows),'retained':len(kept),'schema_rejected':len(rejected),'dedupe_dropped':len(dropped),
        'encoder':'all-MiniLM-L6-v2','embedding_method':'token chunks, length-weighted pooled unit embeddings',
        'cosine_threshold':.90,'cross_split_ngram':12,'status':'human review pending; not training-ready'})
    print('Retained',len(kept),'calls; review required; replenish dropped strata before training.')


if __name__=='__main__': main()
