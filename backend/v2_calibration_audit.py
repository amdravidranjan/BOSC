"""Explain existing v1 thresholds from genuine DEV peaks, without retuning."""
import json
import numpy as np
from common import ART, ROOT, calls, windows, traces, threshold, write_json
from stage3_stream import features, ece


def tree_values(node,x,indices,out):
    if not len(indices): return
    if 'leaf_value' in node:
        out[indices]+=node['leaf_value']; return
    left=x[indices,node['split_feature']]<=float(node['threshold'])
    tree_values(node['left_child'],x,indices[left],out)
    tree_values(node['right_child'],x,indices[~left],out)


def main():
    cfg=json.loads((ROOT/'frontend/model/stream_model.json').read_text())
    cs=calls('dev'); _,y,_,groups=windows(cs)
    p=np.load(ART/'student_int8/dev.npy'); x,_=features(cs,p)
    z=np.zeros(len(p))
    for tree in cfg['trees']: tree_values(tree['tree_structure'],x,np.arange(len(x)),z)
    raw=1/(1+np.exp(-z)); q=np.interp(raw,cfg['isotonic']['x'],cfg['isotonic']['y'])
    score=q if cfg['mode']=='trees' else p[:,0]
    risks=traces(score,cs,groups,cfg['alpha_up'],cfg['alpha_down'])
    peaks=np.array([max(t,default=0) for c,t in zip(cs,risks) if not c['label']])
    curve=[]
    for lo in np.arange(0,1,.1):
        mask=(score>=lo)&((score<lo+.1) if lo<.9 else (score<=1))
        if mask.any(): curve.append({'count':int(mask.sum()),'predicted':float(score[mask].mean()),
                                    'observed':float(y[mask,0].mean())})
    result={'scope':'v1 DEV diagnosis only; not v2 results','warn':cfg['warn'],'pause':cfg['pause'],
        'gap':cfg['pause']-cfg['warn'],'n_genuine':len(peaks),
        'recomputed_warn':threshold(peaks,.05),'recomputed_pause':threshold(peaks,.02),
        'dev_genuine_warn_rate':float((peaks>=cfg['warn']).mean()),
        'dev_genuine_pause_rate':float((peaks>=cfg['pause']).mean()),
        'genuine_peak_quantiles':dict(zip(['50','90','95','98','100'],np.quantile(peaks,[.5,.9,.95,.98,1]).tolist())),
        'dev_selected_score_ece':ece(y[:,0],score),'calibration_curve':curve,
        'explanation':'Both thresholds select nearby order statistics of high genuine DEV peaks. This is not a threshold serialization bug. Improve hard-negative/noisy-data discrimination and refit calibration; do not widen the band by hand.'}
    write_json(ROOT/'data-eval/results/v2_calibration_audit.json',result)
    print(json.dumps(result,indent=2))


if __name__=='__main__': main()
