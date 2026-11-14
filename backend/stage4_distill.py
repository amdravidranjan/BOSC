"""Distil the teacher's nine logits into the README's MiniLM-L3 student."""
import argparse
import json
import numpy as np
from common import ART, record, write_json
from neural import train_one


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--device',default='cpu')
    ap.add_argument('--seeds',type=int,nargs='+',default=[13,42,1234])
    ap.add_argument('--epochs',type=int,default=5)
    ap.add_argument('--model',choices=['student','minilm'],default='student')
    ap.add_argument('--length',type=int,default=128)
    ap.add_argument('--loss',choices=['v1','v2'],default='v1')
    ap.add_argument('--patience',type=int,default=1)
    ap.add_argument('--tag',default='')
    args=ap.parse_args()
    teacher=np.load(ART/'teacher_preds/train_logits.npy')
    results={}
    for seed in args.seeds:
        sub=('student' if args.model=='student' else 'student_l6')+args.tag
        out=ART/sub/str(seed)
        if (out/'metrics.json').exists():
            results[str(seed)]=json.loads((out/'metrics.json').read_text())
        else:
            results[str(seed)]=train_one(str(ART/'pretrained'/args.model),out,seed,args.device,
                                        args.epochs,5e-5,args.length,batch=32,teacher=teacher,loss_version=args.loss,patience=args.patience)
            write_json(out/'metrics.json',results[str(seed)])
    best=max(args.seeds,key=lambda s:results[str(s)]['dev']['pr_auc'])
    write_json(ART/'student/best.json',{'seed':best,
               'path':('student' if args.model=='student' else 'student_l6')+args.tag+'/'+str(best)})
    teacher_seed=json.loads((ART/'teacher/best.json').read_text())['seed']
    teacher_metrics=json.loads((ART/'teacher'/str(teacher_seed)/'metrics.json').read_text())
    gate=results[str(best)]['dev']['f1']>=teacher_metrics['dev']['f1']-.01
    record('stage4_distill',vars(args),{'seeds':results,'selected_seed':best,
           'student_f1_gate_passed':gate,'teacher_dev_f1':teacher_metrics['dev']['f1'],
           'dev_mean_std':{k:{'mean':float(np.mean([r['dev'][k] for r in results.values()])),
                              'std':float(np.std([r['dev'][k] for r in results.values()]))}
                           for k in ['pr_auc','f1','tactic_macro_f1']}})


if __name__=='__main__':
    main()
