"""Mask-aware, balanced supervision; do not distil unreliable tactic pseudo-labels."""
import torch
from torch.nn import functional as F


def positive_weights(labels, masks, cap=20.):
    positives=(labels*masks).sum(0)
    negatives=((1-labels)*masks).sum(0)
    # Compute from train only. Zero-positive labels need more data, not infinity.
    return (negatives/positives.clamp(min=1)).clamp(min=1.,max=cap)


def loss_v2(logits, labels, masks, pos_weights, teacher=None, alpha=.3, temperature=2.):
    if not 0 <= alpha <= 1 or temperature <= 0:
        raise ValueError('Invalid distillation parameters')
    hard=F.binary_cross_entropy_with_logits(logits,labels,pos_weight=pos_weights,reduction='none')
    per_head=(hard*masks).sum(0)/masks.sum(0).clamp(min=1)
    active=masks[:,1:].sum(0)>0
    tactic=(per_head[1:]*active).sum()/active.sum().clamp(min=1)
    scam=per_head[0]
    if teacher is not None:
        # Scam knowledge transfer remains useful on additional unlabelled windows.
        # Tactics use explicit annotations until a teacher demonstrates reliable tactics.
        soft=F.binary_cross_entropy_with_logits(logits[:,0]/temperature,
                  torch.sigmoid(teacher[:,0]/temperature))*temperature**2
        scam=(1-alpha)*scam+alpha*soft
    return scam+tactic
