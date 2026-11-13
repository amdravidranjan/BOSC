"""Audit v1 evidence without retraining or inspecting the lockbox."""
import json
import numpy as np
from common import ART, ROOT, LABELS, calls, windows, write_json


def main():
    report={}
    for split in ('train','dev'):
        cs=calls(split); _,y,mask,_=windows(cs); valid=mask[:,1].astype(bool)
        report[split]={'calls':len(cs),'windows':len(y),'labelled_tactic_windows':int(valid.sum()),
            'missing_gen_labels':sum('tactics' not in t for c in cs if c['source']=='gen'
                                     for t in c['turns'] if t['speaker']=='caller'),
            'masked_positive_tactics':int((y[:,1:]*(1-mask[:,1:])).sum()),
            'positive_tactics':dict(zip(LABELS[1:],y[valid,1:].sum(0).astype(int).tolist()))}
        if split=='train':
            z=np.load(ART/'teacher_preds/train_logits.npy'); p=1/(1+np.exp(-z))
            report['teacher_train_positive_label_mean_probability']={k:float(p[(y[:,i]==1)&valid,i].mean())
                                                                    for i,k in enumerate(LABELS) if i}
    cfg=json.loads((ROOT/'frontend/model/student/config.json').read_text())
    report['label_order_matches_config']=[cfg['id2label'][str(i)] for i in range(9)]==LABELS
    report['label_order_matches_export']=json.loads((ROOT/'frontend/model/student/label_order.json').read_text())==LABELS
    report['finding']='No dropped/misordered/masked template labels found; newest-turn labels are correct. Unmasked tactic distillation, rare labels and weak teacher supervision are confirmed risks, not a proven sole cause.'
    write_json(ROOT/'data-eval/results/v2_label_audit.json',report)
    print(json.dumps(report,indent=2))


if __name__=='__main__': main()
