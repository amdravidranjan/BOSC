"""Twenty handwritten windows: fixed labels, masks, context, and deployment order."""
import json
import unittest
import numpy as np
from common import LABELS, ROOT, windows


class LabelContractTests(unittest.TestCase):
    def test_twenty_handwritten_windows(self):
        utterances = [
            ('Hello.', [], [0,0,0,0,0,0,0,0,0]),
            ('I am the investigating officer.', ['authority_claim'], [0,0,0,0,1,0,0,0,0]),
            ('Do this immediately.', ['urgency'], [1,1,0,0,0,0,0,0,0]),
            ('You will be arrested.', ['threat'], [1,0,1,0,0,0,0,0,0]),
            ('Do not tell your family.', ['secrecy_isolation'], [1,0,0,1,0,0,0,0,0]),
            ('Read your banking OTP.', ['credential_request'], [1,0,0,0,0,1,0,0,0]),
            ('Install AnyDesk.', ['remote_access'], [1,0,0,0,0,0,1,0,0]),
            ('Transfer the money.', ['payment_instruction'], [1,0,0,0,0,0,0,1,0]),
            ('Stay on this call and pay now.', ['stay_on_line','payment_instruction','urgency'],
             [1,1,0,0,0,0,0,1,1]),
            ('Are you still there?', [], [1,0,0,0,0,0,0,0,0]),
        ]
        turns=[]
        for text,tactics,_ in utterances:
            turns += [{'speaker':'caller','text':text,'tactics':tactics},
                      {'speaker':'receiver','text':'RECEIVER SECRET','tactics':['threat']}]
        scam={'label':1,'source':'gen','first_suspicious_caller_turn':2,'turns':turns}
        benign=['Your delivery OTP, please.', 'Pay your bill through your bank app.',
                'Never share your bank OTP.', 'Your passport verification is tomorrow.',
                'I can call back later.']
        genuine={'label':0,'source':'llm','first_suspicious_caller_turn':None,
                 'turns':[{'speaker':'caller','text':s,'tactics':[]} for s in benign]}
        hf={'label':1,'source':'hf','first_suspicious_caller_turn':1,
            'turns':[{'speaker':'caller','text':s} for s in
                     ['Hello','Refund pending','Install this app','Read the code','Thank you']]}
        text,y,mask,groups=windows([scam,genuine,hf])
        expected=[r[2] for r in utterances]+[[0]*9]*5+[
            [0,0,0,0,0,0,0,0,0], [1,0,0,0,0,0,0,0,0],
            [1,0,0,0,0,0,0,0,0], [1,0,0,0,0,0,0,0,0], [1,0,0,0,0,0,0,0,0]]
        self.assertEqual(len(text),20)
        np.testing.assert_array_equal(y,expected)
        np.testing.assert_array_equal(mask,[[1]*9]*15+[[1]+[0]*8]*5)
        np.testing.assert_array_equal(groups,[0]*10+[1]*5+[2]*5)
        self.assertEqual(text[4], 'I am the investigating officer. | Do this immediately. | You will be arrested. | Do not tell your family.')
        self.assertEqual(text[10],benign[0])
        self.assertNotIn('RECEIVER', ' '.join(text))
        self.assertEqual(LABELS,['scam','urgency','threat','secrecy_isolation','authority_claim',
                                 'credential_request','remote_access','payment_instruction','stay_on_line'])
        self.assertEqual(json.loads((ROOT/'frontend/model/student/label_order.json').read_text()),LABELS)
        cfg=json.loads((ROOT/'frontend/model/student/config.json').read_text())
        self.assertEqual([cfg['id2label'][str(i)] for i in range(9)],LABELS)

    def test_missing_annotation_is_not_a_negative_label(self):
        c={'label':1,'source':'llm','first_suspicious_caller_turn':0,
           'turns':[{'speaker':'caller','text':'unknown alignment'}]}
        with self.assertRaises(ValueError): windows([c])
        c['turns'][0]['tactics_known']=False
        self.assertEqual(windows([c])[2][0].tolist(),[1]+[0]*8)
        c['turns'][0].update(tactics_known=True,tactics=['TYPO'])
        with self.assertRaises(ValueError): windows([c])


if __name__=='__main__': unittest.main()
