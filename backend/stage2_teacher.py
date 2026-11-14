"""Fine-tune the README's DeBERTa-v3-small teacher across three seeds."""
import argparse
import numpy as np
from common import ART, SPLITS, evaluate_probs, record, write_json
from neural import train_one, checkpoint_predictions


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--device',default='cpu')
    ap.add_argument('--seeds',type=int,nargs='+',default=[13,42,1234])
    ap.add_argument('--epochs',type=int,default=3)
    ap.add_argument('--loss',choices=['v1','v2'],default='v1')
    args=ap.parse_args()
    results={}
    for seed in args.seeds:
        output=ART/'teacher'/str(seed)
        if (output/'metrics.json').exists():
            import json
            results[str(seed)]=json.loads((output/'metrics.json').read_text())
        else:
            results[str(seed)]=train_one(str(ART/'pretrained/teacher'),output,seed,args.device,
                                       args.epochs,3e-5,192,batch=16,loss_version=args.loss)
            write_json(output/'metrics.json',results[str(seed)])
    # Select seed using dev only. Mean/std still report every independent run.
    best=max(args.seeds,key=lambda s:results[str(s)]['dev']['pr_auc'])
    output=ART/'teacher'/str(best)
    missing=[s for s in SPLITS if not (output/(s+'_logits.npy')).exists()]
    if missing:
        checkpoint_predictions(output,args.device,missing)
    preds=ART/'teacher_preds'
    preds.mkdir(exist_ok=True)
    for s in SPLITS:
        z=np.load(output/(s+'_logits.npy'))
        np.save(preds/(s+'.npy'),1/(1+np.exp(-z)))
        np.save(preds/(s+'_logits.npy'),z)
    write_json(ART/'teacher/best.json',{'seed':best,'path':str(output.relative_to(ART))})
    summary={k:{'mean':float(np.mean([r['dev'][k] for r in results.values()])),
                'std':float(np.std([r['dev'][k] for r in results.values()]))}
             for k in ['pr_auc','f1','tactic_macro_f1']}
    ev=evaluate_probs(np.load(preds/'dev.npy')[:,0],
                      {'test_synth':np.load(preds/'test_synth.npy')[:,0]})
    record('stage2',vars(args),{'seeds':results,'dev_mean_std':summary,'selected_seed':best,'calls':ev})


if __name__=='__main__':
    main()
