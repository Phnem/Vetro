import importlib.util
import unittest

if not importlib.util.find_spec("scipy") or not importlib.util.find_spec("sklearn"):
    raise unittest.SkipTest("Use pinned training environment for statistical tests")

import numpy as np

from model_metrics import precision_lower,select_threshold


class CalibrationPolicyTest(unittest.TestCase):
    def test_no_predictions_is_not_a_quality_pass(self):
        result=select_threshold(np.ones(1000),np.ones(1000,dtype=np.int8))
        self.assertFalse(result["validated_auto_threshold"])
        self.assertGreater(result["threshold"],1)

    def test_ties_cannot_be_sliced_to_hide_false_positives(self):
        result=select_threshold(np.ones(10_000),np.r_[np.ones(9990),np.zeros(10)])
        self.assertFalse(result["validated_auto_threshold"])

    def test_separated_large_validation_passes_with_explicit_margin(self):
        result=select_threshold(np.r_[np.full(10_000,.9999),np.full(10_000,.1)],np.r_[np.ones(10_000),np.zeros(10_000)])
        self.assertTrue(result["validated_auto_threshold"])
        self.assertGreaterEqual(result["validation_precision_lower95"],.9995)
        self.assertGreaterEqual(precision_lower(2995,0),.999)
