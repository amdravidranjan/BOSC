"""Export and quantize the selected student, then score every split with int8."""
import json
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import onnx
import onnxruntime as ort
import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

from common import ART, LABELS, ROOT, SPLITS, calls, digest, record, window_metrics, windows, write_json
from neural import encode, predict
from quantize_weights import quantize_weights


class LogitsOnly(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model=model

    def forward(self,input_ids,attention_mask,token_type_ids):
        return self.model(input_ids=input_ids,attention_mask=attention_mask,
                          token_type_ids=token_type_ids).logits


def ort_predict(session, enc, batch=1):
    names=[x.name for x in session.get_inputs()]
    def run(i):
        feed={k:enc[k][i:i+batch].numpy().astype(np.int64) for k in names}
        return session.run(None,feed)[0]
    # Each run still sees exactly one window, matching browser dynamic ranges.
    # Session.run is thread-safe; map preserves the original window order.
    with ThreadPoolExecutor(max_workers=4) as pool:
        result=list(pool.map(run,range(0,len(enc['input_ids']),batch)))
    return np.concatenate(result)


def main():
    best=json.loads((ART/'student/best.json').read_text())
    source=ART/best['path']
    dest=ROOT/'frontend/model/student'
    (dest/'onnx').mkdir(parents=True,exist_ok=True)
    tok=AutoTokenizer.from_pretrained(source,local_files_only=True)
    tok.truncation_side='left'
    tok.init_kwargs['truncation_side']='left'
    length=min(tok.model_max_length,192)
    tok.model_max_length=length
    tok.save_pretrained(dest)
    model=AutoModelForSequenceClassification.from_pretrained(source,local_files_only=True,
                  attn_implementation='eager').eval()
    model.config.save_pretrained(dest)
    write_json(dest/'label_order.json',LABELS)
    fp=ART/'student_fp32.onnx'
    example=tok(['A caller is asking for my bank password.'],return_tensors='pt')
    torch.onnx.export(LogitsOnly(model),tuple(example[k] for k in (
        'input_ids','attention_mask','token_type_ids')),str(fp),opset_version=17,dynamo=False,
        input_names=['input_ids','attention_mask','token_type_ids'],output_names=['logits'],
        dynamic_axes={k:{0:'batch',1:'sequence'} for k in example}|{'logits':{0:'batch'}})
    onnx.checker.check_model(str(fp))
    qpath=dest/'onnx/model_quantized.onnx'
    quantized_matrices=quantize_weights(fp,qpath)
    onnx.checker.check_model(str(qpath))
    opts=ort.SessionOptions()
    opts.intra_op_num_threads=1
    opts.inter_op_num_threads=1
    fs=ort.InferenceSession(str(fp),opts,providers=['CPUExecutionProvider'])
    qs=ort.InferenceSession(str(qpath),opts,providers=['CPUExecutionProvider'])
    ds={s:windows(calls(s)) for s in SPLITS}
    preds=ART/'student_int8'
    preds.mkdir(exist_ok=True)
    result={}
    for split in SPLITS:
        enc=encode(tok,ds[split][0],length)
        z=ort_predict(qs,enc)
        p=1/(1+np.exp(-z))
        np.save(preds/(split+'.npy'),p)
        result[split]=window_metrics(ds[split][1],p,ds[split][2])
        if split=='dev':
            fz=ort_predict(fs,enc)
            fp_metrics=window_metrics(ds[split][1],1/(1+np.exp(-fz)),ds[split][2])
            result['fp32_dev']=fp_metrics
            result['int8_max_logit_error']=float(np.max(np.abs(fz-z)))
            result['quantization_f1_drop']=fp_metrics['f1']-result['dev']['f1']
            result['quantization_gate_passed']=result['quantization_f1_drop']<=.005
            tz=predict(model,{k:v[:200] for k,v in enc.items()},'cpu')
            result['torch_onnx_max_logit_error']=float(np.max(np.abs(tz-fz[:200])))
            parity=[]
            for i in range(200):
                parity.append({'text':ds[split][0][i],
                               'input_ids':enc['input_ids'][i].tolist(),
                               'attention_mask':enc['attention_mask'][i].tolist(),
                               'token_type_ids':enc['token_type_ids'][i].tolist(),
                               'logits':z[i].tolist()})
            for text in ['', '₹50,000 o.t.p 1,23,456', 'hello ' * 300 + 'share the password',
                         "Don't tell anyone | transfer money now"]:
                edge=encode(tok,[text],length)
                ez=ort_predict(qs,edge)[0]
                parity.append({'text':text,**{k:v[0].tolist() for k,v in edge.items()},
                               'logits':ez.tolist()})
            write_json(ROOT/'backend/onnx_parity_cases.json',parity)
            times=[]
            feed={k:v[:1].numpy() for k,v in enc.items()}
            for _ in range(55):
                start=time.perf_counter()
                qs.run(None,feed)
                times.append((time.perf_counter()-start)*1000)
            result['cpu_ms_window']={'median':float(np.median(times[5:])),
                                     'p95':float(np.percentile(times[5:],95))}
        print(split,result[split],flush=True)
    result['onnx_bytes']=qpath.stat().st_size
    result['quantization']='int8 matrix/embedding weights via QDQ; float32 activations'
    result['quantized_matrices']=quantized_matrices
    result['onnx_sha256']=digest(qpath)
    write_json(dest/'export_metrics.json',result)
    record('stage4_export',{'selected_seed':best['seed'],'max_length':length,
           'validation_workers':4,'prediction_batch_size':1,'onnxruntime':ort.__version__},result)
    if not result['quantization_gate_passed']:
        raise RuntimeError('Quantization F1 gate failed; do not activate the student')
    if result['torch_onnx_max_logit_error']>1e-4:
        raise RuntimeError('FP32 ONNX parity gate failed')


if __name__=='__main__':
    main()
