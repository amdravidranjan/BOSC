"""Masked multitask transformer training, distillation, and batched prediction."""
import random
import time

import numpy as np
import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader, TensorDataset
from transformers import AutoModelForSequenceClassification, AutoTokenizer, get_linear_schedule_with_warmup

from common import ART, LABELS, SPLITS, calls, window_metrics, windows, write_json


class LengthBatches:
    """Shuffle calls' windows, then bucket lengths to avoid training on padding."""
    def __init__(self, lengths, batch, seed):
        self.lengths=lengths
        self.batch=batch
        self.seed=seed
        self.epoch=0

    def __len__(self):
        return (len(self.lengths)+self.batch-1)//self.batch

    def __iter__(self):
        rng=random.Random(self.seed+self.epoch)
        self.epoch+=1
        order=list(range(len(self.lengths)))
        rng.shuffle(order)
        batches=[]
        for i in range(0,len(order),self.batch*32):
            bucket=sorted(order[i:i+self.batch*32],key=lambda j:self.lengths[j])
            batches.extend(bucket[j:j+self.batch] for j in range(0,len(bucket),self.batch))
        rng.shuffle(batches)
        yield from batches


def seed_all(seed):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def encode(tokenizer, texts, length):
    return tokenizer(texts, padding='max_length', truncation=True, max_length=length,
                     return_tensors='pt')


def predict(model, enc, device, batch=32):
    model.eval()
    out = []
    with torch.no_grad():
        for start in range(0,len(enc['input_ids']),batch):
            length=int(enc['attention_mask'][start:start+batch].sum(dim=1).max())
            b = {k:v[start:start+batch,:length].to(device) for k,v in enc.items()}
            out.append(model(**b).logits.cpu().numpy())
    return np.concatenate(out)


def checkpoint_predictions(output, device, splits, batch=32):
    tokenizer=AutoTokenizer.from_pretrained(output,local_files_only=True)
    tokenizer.truncation_side='left'
    length=tokenizer.model_max_length
    model=AutoModelForSequenceClassification.from_pretrained(output,local_files_only=True,
              attn_implementation='eager').to(device)
    results={}
    for split in splits:
        ds=windows(calls(split))
        enc=encode(tokenizer,ds[0],length)
        print(output.name,'predicting',split,len(ds[0]),'windows',flush=True)
        logits=predict(model,enc,device,batch)
        np.save(output/(split+'_logits.npy'),logits)
        results[split]=window_metrics(ds[1],1/(1+np.exp(-logits)),ds[2])
        print(output.name,'saved predictions',split,flush=True)
    del model
    if device=='mps': torch.mps.empty_cache()
    return results


def train_one(base, output, seed, device, epochs, lr, max_length, batch=16, teacher=None):
    seed_all(seed)
    tokenizer = AutoTokenizer.from_pretrained(base, local_files_only=True)
    tokenizer.truncation_side = 'left'
    tokenizer.init_kwargs['truncation_side'] = 'left'
    tokenizer.model_max_length = max_length
    ds = {s:windows(calls(s)) for s in SPLITS}
    enc = {s:encode(tokenizer,d[0],max_length) for s,d in ds.items()}
    model = AutoModelForSequenceClassification.from_pretrained(
        base, num_labels=9, problem_type='multi_label_classification',
        ignore_mismatched_sizes=True, local_files_only=True,
        attn_implementation='eager').to(device)
    model.config.id2label = dict(enumerate(LABELS))
    model.config.label2id = {v:k for k,v in enumerate(LABELS)}
    keys = list(enc['train'])
    y = torch.from_numpy(ds['train'][1])
    mask = torch.from_numpy(ds['train'][2])
    soft = torch.from_numpy(teacher) if teacher is not None else torch.zeros_like(y)
    loader = DataLoader(TensorDataset(*(enc['train'][k] for k in keys),y,mask,soft),
                        batch_sampler=LengthBatches(enc['train']['attention_mask'].sum(dim=1).tolist(),batch,seed))
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=.01)
    sched = get_linear_schedule_with_warmup(opt, int(.06*len(loader)*epochs),len(loader)*epochs)
    pos_weight = ((len(y)-y[:,0].sum())/y[:,0].sum()).to(device)
    best, history, stale = -1., [], 0
    output.mkdir(parents=True,exist_ok=True)
    for epoch in range(epochs):
        model.train()
        losses=[]
        start=time.perf_counter()
        for step, row in enumerate(loader):
            length=int(row[keys.index('attention_mask')].sum(dim=1).max())
            inputs={k:row[i][:,:length].to(device) for i,k in enumerate(keys)}
            labels, masks, targets = [r.to(device) for r in row[len(keys):]]
            opt.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device, dtype=torch.bfloat16, enabled=device=='mps'):
                logits=model(**inputs).logits.float()
            scam=F.binary_cross_entropy_with_logits(logits[:,0],labels[:,0],pos_weight=pos_weight)
            tactics=F.binary_cross_entropy_with_logits(logits[:,1:],labels[:,1:],reduction='none')
            hard=scam+(tactics*masks[:,1:]).sum()/masks[:,1:].sum().clamp(min=1)
            loss=hard
            if teacher is not None:
                soft_loss=F.binary_cross_entropy_with_logits(logits/2,torch.sigmoid(targets/2))*4
                loss=.7*soft_loss+.3*hard
            if not torch.isfinite(loss):
                raise RuntimeError('Nonfinite training loss')
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(),1.)
            opt.step()
            sched.step()
            losses.append(loss.item())
            if step % 50 == 0:
                print(output.name,'epoch',epoch+1,'step',step,'/',len(loader),'loss',loss.item(),flush=True)
        logits=predict(model,enc['dev'],device,batch)
        p=1/(1+np.exp(-logits))
        metrics=window_metrics(ds['dev'][1],p,ds['dev'][2])
        history.append(dict(epoch=epoch+1,loss=float(np.mean(losses)),seconds=time.perf_counter()-start,**metrics))
        print(history[-1],flush=True)
        if metrics['pr_auc']>best:
            best=metrics['pr_auc']
            stale=0
            model.save_pretrained(output, safe_serialization=True)
            tokenizer.save_pretrained(output)
            write_json(output/'label_order.json',LABELS)
        else:
            stale+=1
            if stale>=1:
                break
    write_json(output/'history.json',history)
    del model, opt, sched, loader
    if device=='mps':
        torch.mps.empty_cache()
    # Only the selected teacher needs train/calib predictions. Avoid scoring
    # those splits for every seed; student fp32 needs dev/test only as well.
    return checkpoint_predictions(output,device,['dev','test_synth'],batch)
