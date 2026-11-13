import unittest
import numpy as np
from v2_asr import junk,phone_audio,align


class AudioTests(unittest.TestCase):
    def test_junk_exact_phrases_only(self):
        for s in ('[BLANK_AUDIO]','(speaking in foreign language)','Thank you.','you',''):
            self.assertTrue(junk(s))
        self.assertFalse(junk('Thank you, now share your bank OTP.'))

    def test_phone_paths_are_finite_pcm_and_reproducible(self):
        x=.1*np.sin(2*np.pi*1000*np.arange(16000)/16000).astype(np.float32)
        a=phone_audio(x,'A',20,-6,13); b=phone_audio(x,'B',10,6,13)
        self.assertEqual(len(a),len(x)); self.assertEqual(len(b),len(x))
        self.assertTrue(np.isfinite(b).all()); self.assertLessEqual(np.max(np.abs(b)),1.)
        np.testing.assert_array_equal(a,phone_audio(x,'A',20,-6,13))
        self.assertFalse(np.array_equal(a,b))

    def test_segment_alignment_does_not_fabricate_tactic_labels(self):
        c={'id':'sample','label':1,'first_suspicious_caller_turn':1,'turns':[
            {'speaker':'caller','text':'Hello','tactics':[]},
            {'speaker':'caller','text':'Pay now','tactics':['payment_instruction']}]}
        spans=[{'caller_turn':0,'start_sample':0,'end_sample':100},
               {'caller_turn':1,'start_sample':200,'end_sample':400}]
        segments=[{'text':'Hello','start_sample':0,'end_sample':100,'dropped':False},
                  {'text':'Pay','start_sample':200,'end_sample':300,'dropped':False},
                  {'text':'now','start_sample':300,'end_sample':400,'dropped':False}]
        out=align(c,segments,spans,'A')
        self.assertEqual(out['first_suspicious_caller_turn'],1)
        self.assertEqual(out['call_tactics'],['payment_instruction'])
        self.assertTrue(all(not t['tactics_known'] for t in out['turns']))
        self.assertEqual(out['annotation_status'],'needs_segment_review')


if __name__=='__main__': unittest.main()
