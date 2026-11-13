"""Pure v2 release decision. Missing evidence is a failure, never a pass.

The function does not write release.json or activate a model. Promotion must
also bind this evidence to candidate/data hashes and preserve the Android ABI.
"""
import math


def finite(x): return isinstance(x,(int,float)) and not isinstance(x,bool) and math.isfinite(x)


def valid_stat(x):
    return (isinstance(x,dict) and finite(x.get('value')) and 0<=x['value']<=1
            and isinstance(x.get('ci'),list) and len(x['ci'])==2
            and all(finite(v) and 0<=v<=1 for v in x['ci']) and x['ci'][0]<=x['ci'][1]
            and isinstance(x.get('n'),int) and x['n']>0)


def decide(evidence):
    checks={}
    p=evidence.get('parity',{})
    checks['a_parity']=(p.get('token_ids_identical') is True and p.get('same_levels') is True
        and finite(p.get('max_logit_error')) and 0<=p['max_logit_error']<1e-4
        and finite(p.get('max_risk_error')) and 0<=p['max_risk_error']<1e-4
        and p.get('windows',0)>=200 and p.get('stream_calls',0)>=10)
    drop=evidence.get('quantization_f1_drop')
    checks['b_quantization']=finite(drop) and drop<=.005
    tables=evidence.get('tests',{})
    for path in ('clean','ASR_A','ASR_B'):
        table=tables.get(path,{})
        for group in ('overall','S10','polite'):
            pair=table.get(group,{})
            baseline=pair.get('stage0_tactics',{}); candidate=pair.get('student_streaming',{})
            recall=[baseline.get('recall_pause'),candidate.get('recall_pause')]
            checked=all(valid_stat(x) for x in recall)
            checks[f'evidence_{path}_{group}']=checked
            if path!='clean':
                checks[f'cd_{path}_{group}_recall']=checked and recall[1]['value']>=recall[0]['value']
            if group=='overall':
                fa=[baseline.get('false_alarm_pause'),candidate.get('false_alarm_pause')]
                valid=all(valid_stat(x) for x in fa)
                checks[f'evidence_{path}_fa']=valid
                if path!='clean': checks[f'c_{path}_fa']=valid and fa[1]['value']<=fa[0]['value']
    tactic=evidence.get('tactic_macro_f1')
    checks['e_tactics']=(finite(tactic) and 0<=tactic<=1 and
         (tactic>=.85 or (evidence.get('tactic_source')=='regex' and evidence.get('regex_tactics_verified') is True)))
    checks['data_reviewed']=evidence.get('data_reviewed') is True
    checks['phone_assets_match']=evidence.get('phone_assets_match') is True
    return {'enabled':all(checks.values()),'checks':checks,
            'within_one_f1_of_teacher_diagnostic':evidence.get('within_one_f1_of_teacher'),
            'ci_interpretation':'Reported intervals describe uncertainty; overlap alone does not prove non-inferiority.'}
