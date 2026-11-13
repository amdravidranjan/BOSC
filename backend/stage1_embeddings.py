"""Train embedding LR and train-only scam-library LR baselines."""
import argparse
import time

import joblib
import numpy as np
from sentence_transformers import SentenceTransformer
from sklearn.cluster import MiniBatchKMeans
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

from common import ART, SPLITS, calls, evaluate_probs, record, window_metrics, windows, write_json


def library_features(x, library, types, order):
    sim = x @ library.T
    best = sim.argmax(axis=1)
    return np.column_stack([sim.max(axis=1), np.sort(sim, axis=1)[:, -3:].mean(axis=1),
                            np.asarray([[int(types[b] == t) for t in order] for b in best])])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--device', default='cpu')
    args = ap.parse_args()
    encoder = SentenceTransformer(str(ART/'pretrained/minilm'), device=args.device)
    cs = {s:calls(s) for s in SPLITS}
    metrics, best_all = {}, None
    for width in (2, 3, 4):
        ds = {s:windows(c, width) for s,c in cs.items()}
        emb = {}
        for s in SPLITS:
            path = ART/'emb'/f'minilm-w{width}-{s}.npy'
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.exists():
                emb[s] = np.load(path)
            else:
                t = time.perf_counter()
                emb[s] = encoder.encode(ds[s][0], batch_size=64, normalize_embeddings=True,
                                        show_progress_bar=True)
                np.save(path, emb[s])
                print(s, width, 'ms/window', 1000*(time.perf_counter()-t)/len(emb[s]), flush=True)
        suspicious = np.flatnonzero(ds['train'][1][:, 0] == 1)
        km = MiniBatchKMeans(n_clusters=min(300,len(suspicious)), random_state=13,
                             n_init=3, batch_size=512).fit(emb['train'][suspicious])
        idx = np.unique(suspicious[np.argmax(km.cluster_centers_ @ emb['train'][suspicious].T, axis=1)])
        lib = emb['train'][idx]
        types = [cs['train'][ds['train'][3][i]]['type'] for i in idx]
        order = sorted(set(types))
        lf = {s:library_features(x, lib, types, order) for s,x in emb.items()}
        scaler = StandardScaler().fit(lf['train'])
        for use_lib in (False, True):
            xx = {s:np.column_stack([x,scaler.transform(lf[s])]) if use_lib else x
                  for s,x in emb.items()}
            name = 'D_embeddings_library' if use_lib else 'C_embeddings_lr'
            for c in (.25, 1., 4.):
                clf = LogisticRegression(C=c, class_weight='balanced', max_iter=1500).fit(
                    xx['train'], ds['train'][1][:,0])
                dp = clf.predict_proba(xx['dev'])[:,1]
                ev = evaluate_probs(dp, {})
                key = (ev['dev']['recall_pause'], ev['dev']['recall_warn'],
                       -ev['dev']['false_alarm_pause'])
                previous = metrics.get(name)
                if previous is None or key > tuple(previous['selection_key']):
                    probs = {s:clf.predict_proba(xx[s])[:,1] for s in SPLITS}
                    full = evaluate_probs(dp, {'test_synth':probs['test_synth']})
                    metrics[name] = {'selection_key':list(key), 'width':width, 'C':c,
                                     'calls':full, 'windows':{s:window_metrics(
                                         ds[s][1], np.column_stack([probs[s],np.zeros((len(probs[s]),8))]),
                                         np.zeros_like(ds[s][2])) for s in ('dev','test_synth')}}
                    joblib.dump({'classifier':clf,'scaler':scaler,'library':lib,'types':types,
                                 'type_order':order,'width':width,'use_library':use_lib},
                                ART/(name+'.joblib'))
        print('Finished width',width, flush=True)
    record('stage1', vars(args), metrics)
    write_json(ART/'library_sample.json', [ds['train'][0][i] for i in idx[:20]])
    print(metrics, flush=True)


if __name__ == '__main__':
    main()
