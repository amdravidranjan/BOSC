"""Guard leakage, label masks, causality, and dev false-alarm policy."""
import unittest

import numpy as np

from common import SPLITS, calls, threshold, windows
from data_build_splits import signature
from stage3_stream import features


class PipelineTests(unittest.TestCase):
    def test_disjoint_call_transcripts(self):
        seen=set()
        for split in SPLITS:
            signatures={signature(c) for c in calls(split)}
            self.assertFalse(seen & signatures, split)
            seen |= signatures

    def test_caller_only_window_and_hf_masks(self):
        c={'label':1,'source':'hf','first_suspicious_caller_turn':1,'turns':[
            {'speaker':'caller','text':'ordinary greeting'},
            {'speaker':'receiver','text':'SECRET RECEIVER TEXT'},
            {'speaker':'caller','text':'share your password'}]}
        texts,y,m,_=windows([c])
        self.assertNotIn('SECRET', ' '.join(texts))
        self.assertEqual(y[:,0].tolist(),[0,1])
        self.assertEqual(m[:,1:].sum(),0)

    def test_future_predictions_do_not_change_past_features(self):
        c=calls('calib')[0]
        n=sum(t['speaker']=='caller' for t in c['turns'])
        a=np.zeros((n,9))+.1
        b=a.copy()
        b[-1]=.99
        xa,_=features([c],a)
        xb,_=features([c],b)
        np.testing.assert_array_equal(xa[:-1],xb[:-1])

    def test_threshold_handles_ties_and_saturated_scores(self):
        for peaks in ([1.]*100,[.1]*90+[.9]*10,np.linspace(0,1,99).tolist()):
            for fa in (.02,.05):
                t=threshold(peaks,fa)
                self.assertLessEqual(np.mean(np.asarray(peaks)>=t),fa)


if __name__=='__main__':
    unittest.main()
