"""Call-grouped cross-fitted calibration, causal features, and portable tree export."""
import argparse
import json

import numpy as np
from lightgbm import LGBMClassifier
from sklearn.isotonic import IsotonicRegression
from sklearn.model_selection import StratifiedKFold, StratifiedGroupKFold

from bosc_core import TacticDetector
from common import ART, LABELS, ROOT, SPLITS, calls, evaluate_probs, record, windows, write_json

FEATURES = LABELS + ['scam_max','scam_mean'] + ['max_'+k for k in LABELS[1:]] + [
    'distinct_tactics','scam_windows','scam_ema','authority_before_threat',
    'threat_before_ask','turns_since_tactic','turn','regex_distinct']


def features(cs, predictions):
    # JavaScript numbers are float64; keep running means/EMAs in the same dtype.
    predictions=np.asarray(predictions,dtype=np.float64)
    det=TacticDetector(json.loads((ROOT/'frontend/model/tactics.json').read_text()))
    out, weights, offset=[],[],0
    for c in cs:
        turns=[t for t in c['turns'] if t['speaker']=='caller']
        hist, first, seen, ema=[],{},set(),0.
        for t,turn in enumerate(turns):
            p=predictions[offset]
            offset+=1
            hist.append(p)
            h=np.asarray(hist)
            for k,q in enumerate(p[1:],1):
                if q>.5 and k not in first:
                    first[k]=t
            seen.update(det.detect(turn['text']))
            ema=.5*p[0]+.5*ema
            authority=first.get(4)
            threat=first.get(2)
            before_threat=int(authority is not None and threat is not None and authority<threat)
            before_ask=int(threat is not None and any(
                k in first and threat<first[k] for k in (5,6,7)))
            first_tactic=min(first.values()) if first else t
            row=list(p)+[h[:,0].max(),h[:,0].mean()]+list(h[:,1:].max(axis=0))+[
                int((h[:,1:].max(axis=0)>.5).sum()),int((h[:,0]>.5).sum()),ema,
                before_threat,before_ask,t-first_tactic,t,len(seen)]
            out.append(row)
            weights.append(1/len(turns))
    assert offset==len(predictions)
    return np.asarray(out),np.asarray(weights)


def new_model():
    return LGBMClassifier(n_estimators=300,learning_rate=.05,num_leaves=15,min_child_samples=20,
                          subsample=.8,subsample_freq=1,colsample_bytree=.8,reg_lambda=1.,
                          random_state=13,n_jobs=4,verbosity=-1)


def ece(y,p):
    total=0.
    for lo in np.arange(0,1,.1):
        mask=(p>=lo)&(p<lo+.1 if lo<.9 else p<=1)
        if mask.any():
            total+=mask.mean()*abs(y[mask].mean()-p[mask].mean())
    return float(total)


def call_folds(cs):
    """Keep clean/ASR/text-noise siblings in the same calibration fold."""
    labels=[c['label'] for c in cs]
    parents=[c.get('parent_id',c.get('scenario_seed',c['id'])) for c in cs]
    if len(set(parents))==len(parents):
        return list(StratifiedKFold(5,shuffle=True,random_state=13).split(cs,labels))
    return list(StratifiedGroupKFold(5,shuffle=True,random_state=13).split(cs,labels,parents))


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--preds',default='teacher_preds')
    ap.add_argument('--name',default='teacher')
    args=ap.parse_args()
    cs={s:calls(s) for s in SPLITS}
    ds={s:windows(c) for s,c in cs.items()}
    preds={s:np.load(ART/args.preds/(s+'.npy')) for s in SPLITS}
    fw={s:features(cs[s],preds[s]) for s in SPLITS}
    xx,weight=fw['calib']
    yy=ds['calib'][1][:,0]
    groups=ds['calib'][3]
    folds=call_folds(cs['calib'])
    oof=np.zeros(len(yy))
    for train_calls,valid_calls in folds:
        tr=np.isin(groups,train_calls)
        va=np.isin(groups,valid_calls)
        model=new_model().fit(xx[tr],yy[tr],sample_weight=weight[tr])
        oof[va]=model.predict_proba(xx[va])[:,1]
    calibrated_oof=np.zeros(len(yy))
    for train_calls,valid_calls in folds:
        tr=np.isin(groups,train_calls)
        va=np.isin(groups,valid_calls)
        iso=IsotonicRegression(out_of_bounds='clip').fit(oof[tr],yy[tr],sample_weight=weight[tr])
        calibrated_oof[va]=iso.predict(oof[va])
    iso=IsotonicRegression(out_of_bounds='clip').fit(oof,yy,sample_weight=weight)
    model=new_model().fit(xx,yy,sample_weight=weight)
    raw={s:model.predict_proba(fw[s][0])[:,1] for s in SPLITS}
    q={s:iso.predict(raw[s]) for s in SPLITS}
    candidates=[]
    for mode in ('trees','ema'):
        for up in (.5,.6,.8):
            for down in (.1,.15,.25):
                p=q if mode=='trees' else {s:preds[s][:,0] for s in SPLITS}
                ev=evaluate_probs(p['dev'],{},up,down)
                d=ev['dev']
                early=d['median_earliness_turns']
                key=(d['recall_pause'],d['recall_warn'],-(early if early is not None else 99),
                     int(mode=='ema'))
                candidates.append((key,mode,up,down,ev))
    _,mode,up,down,ev=max(candidates,key=lambda r:r[0])
    selected=q if mode=='trees' else {s:preds[s][:,0] for s in SPLITS}
    final=evaluate_probs(selected['dev'],{'test_synth':selected['test_synth']},up,down)
    export={'format':'bosc-stream-v1','mode':mode,'trees':model.booster_.dump_model()['tree_info'],
            'feature_order':FEATURES,'label_order':LABELS,
            'isotonic':{'x':iso.X_thresholds_.tolist(),'y':iso.y_thresholds_.tolist()},
            'alpha_up':up,'alpha_down':down,**ev['thresholds'],
            'library_features':False,'prediction_source':args.preds}
    path=ART/('stream_model_'+args.name+'.json')
    write_json(path,export)
    parity=[]
    offset=0
    for ci,c in enumerate(cs['dev'][:100]):
        n=sum(t['speaker']=='caller' for t in c['turns'])
        risk=0.
        latched=False
        steps=[]
        for i in range(offset,offset+n):
            risk+=(up if selected['dev'][i]>risk else down)*(float(selected['dev'][i])-risk)
            if risk>=export['pause']:
                latched=True
            if latched:
                risk=max(risk,export['pause'])
            steps.append({'features':fw['dev'][0][i].tolist(),'probabilities':preds['dev'][i].tolist(),
                          'text':[t['text'] for t in c['turns'] if t['speaker']=='caller'][i-offset],
                          'raw':float(raw['dev'][i]),'calibrated':float(q['dev'][i]),'risk':risk})
        offset+=n
        parity.append(steps)
    write_json(ART/('stream_parity_'+args.name+'.json'),parity)
    if args.name=='student':
        write_json(ROOT/'frontend/model/stream_model.json',export)
        write_json(ROOT/'backend/stream_parity_cases.json',parity)
    record('stage3_'+args.name,vars(args),{'selected_mode':mode,'calls':final,
           'cross_fitted_calibration_ece':ece(yy,calibrated_oof),
           'dev_calibration_ece':ece(ds['dev'][1][:,0],q['dev']),
           'dev_selected_score_ece':ece(ds['dev'][1][:,0],selected['dev']),
           'calibration_ece_scope':'Window-weighted ECE on call-grouped OOF predictions; calibration fit is cross-fitted.',
           'trees_dev':max((r[4]['dev'] for r in candidates if r[1]=='trees'),key=lambda d:d['recall_pause']),
           'ema_dev':max((r[4]['dev'] for r in candidates if r[1]=='ema'),key=lambda d:d['recall_pause'])})
    print('Selected',mode,final,flush=True)


if __name__=='__main__':
    main()
