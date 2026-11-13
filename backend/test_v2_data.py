import copy
import unittest
import numpy as np
from v2_data import clean, review_sample, validate
from v2_plan import plan
from v2_text_noise import augment
from v2_release_gate import decide
from stage3_stream import call_folds


def sample(i=0,split='train'):
    return {'id':str(i),'scenario_seed':str(i),'split':split,'label':1,'type':'S1','language':'en',
            'style':'polite','source':'llm','first_suspicious_caller_turn':1,'turns':[
                {'speaker':'caller','text':'Hello from the card team','tactics':['authority_claim']},
                {'speaker':'receiver','text':'What is this about','tactics':[]},
                {'speaker':'caller','text':'Please share your OTP for a card upgrade','tactics':['credential_request']},
                {'speaker':'receiver','text':'I will call my branch','tactics':[]},
                {'speaker':'caller','text':'Read the OTP to complete the process','tactics':['credential_request']},
                {'speaker':'receiver','text':'No thank you','tactics':[]}]}


class DataTests(unittest.TestCase):
    def test_calibration_folds_keep_siblings_together(self):
        rows=[{'id':f'{i}-{v}','parent_id':str(i),'label':i%2} for i in range(20) for v in ('clean','A','B')]
        for train,valid in call_folds(rows):
            self.assertFalse({rows[i]['parent_id'] for i in train}&{rows[i]['parent_id'] for i in valid})

    def test_plan_ratios_seeds_and_prompt_split(self):
        rows=plan()
        self.assertEqual(len(rows),8400)
        self.assertEqual(len({r['scenario_seed'] for r in rows}),8400)
        for split in ('train','calib','dev','test_synth'):
            rs=[r for r in rows if r['split']==split]; scams=[r for r in rs if r['label']]
            self.assertEqual(len(scams),len(rs)//2)
            self.assertEqual(sum(r['language']=='hinglish' for r in rs),round(len(rs)*.15))
            self.assertGreaterEqual(sum(r['style']=='polite' for r in scams)/len(scams),.2)
            self.assertGreaterEqual(sum(r['hard_negative'] for r in rs)/(len(rs)-len(scams)),.4)
            self.assertEqual({r['prompt_version'] for r in rs},{'v1' if split in ('train','calib') else 'v2'})

    def test_schema_and_dedupe(self):
        a=sample(); validate(a)
        bad=copy.deepcopy(a); bad['turns'][2]['tactics']=['threat']
        with self.assertRaises(ValueError): validate(bad)
        b=sample(1,'test_synth')
        kept,dropped=clean([a,b],np.eye(2))
        self.assertEqual([c['id'] for c in kept],['1'])
        self.assertEqual(dropped[0]['reason'],'exact_transcript')
        b['turns'][0]['text']='Other opening'
        kept,dropped=clean([a,b],np.eye(2))
        self.assertEqual(dropped[0]['reason'],'cross_split_12gram')
        for t in b['turns']: t['text']='different short words'
        kept,dropped=clean([a,b],np.ones((2,3)))
        self.assertEqual(dropped[0]['reason'],'cosine_gt_0.90')

    def test_noise_never_claims_asr_and_preserves_labels(self):
        a=sample(); b=augment(a,13)
        self.assertFalse(b['asr']); self.assertEqual(b['parent_id'],a['id'])
        self.assertEqual([t['tactics'] for t in a['turns']],[t['tactics'] for t in b['turns']])
        a['split']='test_synth'
        with self.assertRaises(ValueError): augment(a,13)

    def test_review_is_pending_not_fabricated(self):
        rows=review_sample([sample(i) for i in range(100)])
        self.assertEqual(len(rows),5)
        self.assertTrue(all(r['status']=='pending' and r['reviewer'] is None for r in rows))

    def test_release_fails_closed_and_diagnostic_is_not_gate(self):
        self.assertFalse(decide({})['enabled'])
        stat=lambda v:{'value':v,'ci':[max(0,v-.1),min(1,v+.1)],'n':100}
        pair={'stage0_tactics':{'recall_pause':stat(.9),'false_alarm_pause':stat(.04)},
              'student_streaming':{'recall_pause':stat(.91),'false_alarm_pause':stat(.03)}}
        e={'parity':{'token_ids_identical':True,'same_levels':True,'max_logit_error':1e-6,
                     'max_risk_error':1e-6,'windows':204,'stream_calls':10},
           'quantization_f1_drop':.003,'tactic_macro_f1':.5,'tactic_source':'regex',
           'regex_tactics_verified':True,'data_reviewed':True,'phone_assets_match':True,
           'within_one_f1_of_teacher':False,
           'tests':{p:{g:copy.deepcopy(pair) for g in ('overall','S10','polite')} for p in ('clean','ASR_A','ASR_B')}}
        self.assertTrue(decide(e)['enabled'])
        e['tests']['ASR_A']['S10']['student_streaming']['recall_pause']=stat(.89)
        self.assertFalse(decide(e)['enabled'])
        e['tests']['ASR_A']['S10']['student_streaming']['recall_pause']=stat(.91)
        e['quantization_f1_drop']=float('nan')
        self.assertFalse(decide(e)['enabled'])


if __name__=='__main__': unittest.main()
