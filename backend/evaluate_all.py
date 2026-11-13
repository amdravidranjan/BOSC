"""Assemble measured available-data results and figures; never inspect lockbox."""
import json
import os
os.environ.setdefault('MPLCONFIGDIR',str(__import__('pathlib').Path(__file__).parent/'artifacts/matplotlib'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

from common import ART, ROOT, calls, digest, traces, windows, write_json
from stage3_stream import features

RES=ROOT/'data-eval/results'


def main():
    load=lambda name:json.loads((ART/(name+'_metrics.json')).read_text())
    s1,s2,s3,s4,se,sf=[load(n) for n in ('stage1','stage2','stage3_teacher','stage4_distill',
                                        'stage4_export','stage3_student')]
    manifest=json.loads((ROOT/'data-eval/v1/manifest.json').read_text())
    student_info=json.loads((ART/'student/best.json').read_text())
    student_name='MiniLM-L3' if student_info['path'].startswith('student/') else 'MiniLM-L6'
    student_length=json.loads((ROOT/'frontend/model/student/tokenizer_config.json').read_text())['model_max_length']
    def pct(x): return f'{100*x:.2f}%'
    def ci(m,k): return pct(m[k])+' ['+', '.join(pct(x) for x in m[k+'_ci'])+']'
    stage0=load('stage0_available')
    rows=[('A: rules only',stage0['A_rules_only']),('B: TF-IDF + LR',stage0['B_tfidf_lr']),
          ('B + tactics',stage0['B_tfidf_lr_tactics']),('C: embeddings + LR',s1['C_embeddings_lr']['calls']),
          ('D: embeddings + library',s1['D_embeddings_library']['calls']),
          ('E: DeBERTa teacher',s2['calls']),('F: teacher streaming',s3['calls']),
          ('G: int8-weight student streaming',sf['calls'])]
    lines=['# Stages 1–4: available-data experiment','',
           '**All results below are synthetic. No human or real-audio performance is claimed.**','',
           '## Data and scope','',
           'Used the README’s BothBosu/scam-dialogue dataset plus BOSC-generated template calls. '
           f'The teacher is DeBERTa-v3-small; the selected student is {student_name}. Both were fine-tuned with '
           'three seeds (13, 42, 1234), early stopping on dev PR-AUC, caller-only four-utterance '
           f'windows, left truncation, and masked HF tactic loss. Teacher: 192 tokens; student: {student_length}. '
           'Stage 3 uses call-grouped cross-fitting on calib and dev-only threshold selection. '
           'The deployment features omit the optional library features, as allowed by README §8.','',
           'This is **not the full README benchmark**: no LLM-generated corpus, human review, '
           'semantic/12-word-ngram dedupe, ASR augmentation, lockbox, red team, real recordings, '
           'teacher architecture sweep, or neural leave-one-type-out reruns were available/run. '
           'Template phrases can recur across splits. These results cannot establish generalization '
           'to real scam calls. The original Stage 0 report uses different splits and is not a '
           'controlled comparison to this table. Rows A/B below were rerun on the new splits '
           'with the fixed Stage 0 configuration.','',
           '| Split | Calls | HF | Templates |','|---|---:|---:|---:|']
    for name,m in manifest['counts'].items():
        lines.append(f"| {name} | {m['calls']} | {m['sources'].get('hf',0)} | {m['sources'].get('gen',0)} |")
    lines+=['','## Held-out synthetic calls (95% call-bootstrap intervals)','',
            '| System | PAUSE recall | PAUSE false alarms | Median WARN delay (turns) |',
            '|---|---|---|---:|']
    for name,ev in rows:
        m=ev['test_synth']
        lines.append(f"| {name} | {ci(m,'recall_pause')} | {ci(m,'false_alarm_pause')} | {m['median_earliness_turns']} |")
    lines+=['','## Student by source','',
            '| Source | Scam | Genuine | PAUSE recall | PAUSE false alarms |',
            '|---|---:|---:|---|---|']
    for source in ('hf','gen'):
        m=sf['calls']['test_synth_'+source]
        lines.append(f"| {source} | {m['n_scam']} | {m['n_genuine']} | {ci(m,'recall_pause')} | {ci(m,'false_alarm_pause')} |")
    if (ART/'stage1_loto_metrics.json').exists():
        lines+=['','## Embedding baseline: leave one scam type out','',
                '| Held-out type | PAUSE recall |','|---|---|']
        for typ,m in load('stage1_loto').items():
            lines.append(f"| {typ} | {ci(m,'recall_pause')} |")
    lines+=['','## Validation and deployment gates','',
            f"- Teacher dev PR-AUC mean ± std: {s2['dev_mean_std']['pr_auc']['mean']:.5f} ± {s2['dev_mean_std']['pr_auc']['std']:.5f}.",
            f"- Student dev PR-AUC mean ± std: {s4['dev_mean_std']['pr_auc']['mean']:.5f} ± {s4['dev_mean_std']['pr_auc']['std']:.5f}.",
            f"- Student within 1 F1 point of teacher: **{s4['student_f1_gate_passed']}**.",
            f"- Int8 quantization F1 drop: {se['quantization_f1_drop']:.6f}; <=0.005 gate: **{se['quantization_gate_passed']}**.",
            f"- PyTorch / FP32 ONNX max logit error: {se['torch_onnx_max_logit_error']:.8f}.",
            f"- FP32 / int8 max dev logit error: {se['int8_max_logit_error']:.6f}.",
            f"- ONNX bytes: {se['onnx_bytes']}; SHA-256 `{se['onnx_sha256']}`.",
            f"- Quantization: {se['quantization']}. Dynamic activation quantization failed WASM parity and was rejected.",
            f"- Single-thread CPU ms/window: median {se['cpu_ms_window']['median']:.2f}, p95 {se['cpu_ms_window']['p95']:.2f}. This is Mac CPU latency, not phone latency.",
            f"- Teacher streaming selected: {s3['selected_mode']}; student streaming selected: {sf['selected_mode']}.",
            f"- Student cross-fitted calibration ECE: {sf['cross_fitted_calibration_ece']:.5f}.",
            f"- Student tree-calibrator ECE on separate dev calls: {sf['dev_calibration_ece']:.5f}.",
            f"- Selected student window-score ECE on dev: {sf['dev_selected_score_ece']:.5f}.",
            f"- Teacher tactic F1 target >=0.85: {s2['dev_mean_std']['tactic_macro_f1']['mean'] >= .85}; actual mean {s2['dev_mean_std']['tactic_macro_f1']['mean']:.5f}.",
            '- See backend/runs for configs, split hashes, seed-level metrics, and exact dependencies.',
            '- Browser runtime parity is recorded separately; release.json is enabled only after all required parity/gates pass.',
            '- Large PyTorch teacher/student checkpoints and pretrained caches stay local in backend/artifacts, following the repository policy. The deployable int8 student is versioned in frontend/model/student.', '']
    if (ART/'stage1_latency.json').exists():
        latency=json.loads((ART/'stage1_latency.json').read_text())
        lines += [f"Stage 1 CPU encoding (batch 1, 50 windows): median {latency['encoder_only_ms']['median']:.2f} ms, p95 {latency['encoder_only_ms']['p95']:.2f} ms.", '']
    (RES/'stages_1_4.md').write_text('\n'.join(lines))
    write_json(RES/'stages_1_4_metrics.json',{'stage0_available':stage0,'stage1':s1,'stage2':s2,'stage3_teacher':s3,
               'stage4_distill':s4,'stage4_export':se,'stage3_student':sf})
    # Deterministic risk examples from dev, avoiding subjective test selection.
    cs=calls('dev')
    p=np.load(ART/'student_int8/dev.npy')
    cfg=json.loads((ROOT/'frontend/model/stream_model.json').read_text())
    xx,_=features(cs,p)
    def tree(node,x):
        if 'leaf_value' in node: return node['leaf_value']
        return tree(node['left_child'] if x[node['split_feature']]<=float(node['threshold']) else node['right_child'],x)
    if cfg['mode']=='ema': q=p[:,0]
    else:
        z=np.asarray([sum(tree(t['tree_structure'],x) for t in cfg['trees']) for x in xx])
        q=np.interp(1/(1+np.exp(-z)),cfg['isotonic']['x'],cfg['isotonic']['y'])
    ts=traces(q,cs,windows(cs)[3],cfg['alpha_up'],cfg['alpha_down'])
    fig,ax=plt.subplots(figsize=(9,4.5))
    for want in ('S2_digital_arrest','S1_bank_kyc','G1_delivery_otp'):
        i=next(i for i,c in enumerate(cs) if c['type']==want)
        ax.plot(range(1,len(ts[i])+1),ts[i],marker='o',label=want)
    ax.axhline(cfg['warn'],ls='--',color='#d99c00',label='WARN')
    ax.axhline(cfg['pause'],ls='--',color='#c33',label='PAUSE')
    ax.set(xlabel='Caller turn',ylabel='Risk',title='Student: synthetic dev calls (unlatched trace)')
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(RES/'fig_student_risk.png',dpi=160)
    plt.close(fig)
    delays=[]
    for c,t in zip(cs,ts):
        if c['label'] and c['first_suspicious_caller_turn'] is not None:
            found=next((i for i,r in enumerate(t) if r>=cfg['warn']),None)
            if found is not None: delays.append(found-c['first_suspicious_caller_turn'])
    fig,ax=plt.subplots(figsize=(7,4))
    bins=np.arange(min(delays)-.5,max(delays)+1.5) if delays else [-.5,.5]
    ax.hist(delays,bins=bins,color='#3568a8',edgecolor='white')
    ax.set(xlabel='Caller turns after first suspicious turn',ylabel='Scam calls',
           title='Student WARN delay: detected synthetic dev scams')
    fig.tight_layout()
    fig.savefig(RES/'fig_student_earliness.png',dpi=160)
    plt.close(fig)
    yy=windows(cs)[1][:,0]
    px,py=[],[]
    for low in np.arange(0,1,.1):
        mask=(q>=low)&(q<low+.1 if low<.9 else q<=1)
        if mask.any(): px.append(float(q[mask].mean())); py.append(float(yy[mask].mean()))
    fig,ax=plt.subplots(figsize=(5,5))
    ax.plot([0,1],[0,1],'--',color='gray')
    ax.plot(px,py,'o-',color='#3568a8')
    ax.set(xlabel='Mean predicted scam probability',ylabel='Observed scam-window fraction',
           title='Student calibration: synthetic dev windows',xlim=(0,1),ylim=(0,1))
    fig.tight_layout()
    fig.savefig(RES/'fig_student_calibration.png',dpi=160)
    plt.close(fig)
    # Hash every local trained checkpoint so runs remain identifiable without committing large files.
    paths=[p for parent in ('teacher','student','student_l6')
           for p in (ART/parent).glob('*/model.safetensors')]
    write_json(RES/'checkpoint_manifest.json',{str(p.relative_to(ROOT)):{'sha256':digest(p),'bytes':p.stat().st_size}
                                             for p in paths})
    print('Wrote stages_1_4.md and metrics/figure/checkpoint manifest',flush=True)


if __name__=='__main__': main()
