"""Text-only corruption. Never label this as measured Whisper ASR output."""
import copy
import random
import re

SWAPS={r'\bFIR\b':['Fair','F I R'],r'\bKYC\b':['K Y C'],r'\bOTP\b':['O T P','or t p'],
       r'\bAnyDesk\b':['any desk'],r'\bAadhaar\b':['adha','adhar'],r'\bSBI\b':['S B I'],
       r'\bcyber crime branch\b':['Cybertron brands']}


def augment(call,seed):
    if call['split']!='train': raise ValueError('Text augmentation is train-only')
    out=copy.deepcopy(call); rng=random.Random(seed)
    out.update(id=call['id']+f'-textnoise-{seed}',parent_id=call.get('parent_id',call['id']),
               augmentation='text_noise',asr=False)
    for turn in out['turns']:
        if turn['speaker']!='caller': continue
        for pattern,variants in SWAPS.items():
            turn['text']=re.sub(pattern,lambda _:rng.choice(variants),turn['text'],flags=re.I)
        turn['text']=re.sub(r'\b(?:uh|um|actually)\b','',turn['text'],flags=re.I)
        turn['text']=re.sub(r'[^\w\s₹]','',turn['text'].lower())
        turn['text']=' '.join(turn['text'].split())
    # Keep semantic turn boundaries: merging/splitting requires annotation realignment.
    return out
