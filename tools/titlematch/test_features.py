import importlib.util
import unittest

if not importlib.util.find_spec("numpy") or not importlib.util.find_spec("rapidfuzz"):
    raise unittest.SkipTest("Use the pinned training environment for ML feature tests")

import numpy as np

from features import MAX_BYTES,NAMES,encode,pair_features


class PairFeaturesTest(unittest.TestCase):
    def test_pair_features_are_symmetric_and_finite(self):
        for a,b in [("Example II","Example III"),("進撃の巨人","Attack on Titan"),("Начало","Inception"),("","")]:
            ab=pair_features(a,b,"ANIME","MANGA",2000,2001)
            ba=pair_features(b,a,"MANGA","ANIME",2001,2000)
            np.testing.assert_array_equal(ab,ba)
            self.assertTrue(np.isfinite(ab).all())
            self.assertEqual(len(NAMES),len(ab))

    def test_unicode_encoding_is_integer_and_preserves_tail_on_truncation(self):
        a=encode("Начало")
        self.assertEqual((MAX_BYTES,),a.shape)
        self.assertEqual(np.int32,a.dtype)
        self.assertGreater(int(a.max()),128)
        self.assertEqual(0,int(a[-1]))
        self.assertNotEqual(encode("abc "*200+"season 2").tolist(),encode("abc "*200+"season 3").tolist())

    def test_numeric_conflict_and_domain_scope_are_explicit(self):
        features=dict(zip(NAMES,pair_features("Title 1","Title 2","ANIME","MANGA")))
        self.assertEqual(1,features["numeric_conflict"])
        self.assertEqual(0,features["same_kind"])


if __name__=="__main__":
    unittest.main()
