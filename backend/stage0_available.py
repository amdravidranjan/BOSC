"""Retrain the fixed Stage 0 configuration on the same new train/dev splits."""
import json
import numpy as np

from bosc_core import Model, TacticDetector
from common import ART, ROOT, SPLITS, calls, evaluate_probs, record, windows, write_json
from train import fit


def mix(cs,p,tw):
    det=TacticDetector(json.loads((ROOT/'frontend/model/tactics.json').read_text()))
    result=[]
    offset=0
    for c in cs:
        seen=set()
        for t in c['turns']:
            if t['speaker']!='caller': continue
            seen.update(det.detect(t['text']))
            result.append((1-tw)*p[offset]+tw*min(1.,len(seen & det.PRESSURE)/4))
            offset+=1
    return np.asarray(result)


def main():
    cs={s:calls(s) for s in SPLITS}
    mj,_,_=fit(cs['train'],4,.5)
    model=Model(mj)
    p={s:np.asarray([model.proba(x) for x in windows(c)[0]]) for s,c in cs.items()}
    metrics={}
    for name,tw,prob in [('A_rules_only',1.,{s:np.zeros(len(v)) for s,v in p.items()}),
                         ('B_tfidf_lr',0.,p),('B_tfidf_lr_tactics',.3,p)]:
        q={s:mix(cs[s],prob[s],tw) for s in SPLITS}
        metrics[name]=evaluate_probs(q['dev'],{'test_synth':q['test_synth']})
    write_json(ART/'stage0_available_model.json',mj)
    record('stage0_available',{'width':4,'C':.5,'selection':'Fixed configuration from Stage 0; trained only on new train split'},metrics)


if __name__=='__main__': main()
