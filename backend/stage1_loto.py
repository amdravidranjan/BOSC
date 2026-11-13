"""Leave one template scam type out of the best embedding baseline."""
import json
import joblib
import numpy as np
from sklearn.linear_model import LogisticRegression

from common import ART, call_metrics, calls, record, summarize, threshold, traces, windows


def main():
    metrics=json.loads((ART/'stage1_metrics.json').read_text())['C_embeddings_lr']
    width=metrics['width']
    cs={s:calls(s) for s in ('train','dev','test_synth')}
    ds={s:windows(c,width) for s,c in cs.items()}
    emb={s:np.load(ART/'emb'/f'minilm-w{width}-{s}.npy') for s in cs}
    result={}
    for typ in sorted({c['type'] for c in cs['train'] if c['label'] and c['source']=='gen'}):
        keep=np.asarray([c['type']!=typ for c in cs['train']])[ds['train'][3]]
        clf=LogisticRegression(C=metrics['C'],class_weight='balanced',max_iter=1500).fit(
            emb['train'][keep],ds['train'][1][keep,0])
        p=clf.predict_proba(emb['dev'])[:,1]
        dt=traces(p,cs['dev'],ds['dev'][3])
        peaks=[max(t) for c,t in zip(cs['dev'],dt) if not c['label']]
        warn,pause=threshold(peaks,.05),threshold(peaks,.02)
        indices=[i for i,c in enumerate(cs['test_synth']) if c['type']==typ or not c['label']]
        tc=[cs['test_synth'][i] for i in indices]
        mask=np.isin(ds['test_synth'][3],indices)
        tp=clf.predict_proba(emb['test_synth'][mask])[:,1]
        tt=traces(tp,tc,windows(tc,width)[3])
        result[typ]=summarize(call_metrics(tt,tc,warn,pause))
    record('stage1_loto',{'baseline':'C_embeddings_lr','width':width,'C':metrics['C']},result)
    print(json.dumps(result,indent=2))


if __name__=='__main__': main()
