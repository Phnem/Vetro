import importlib.util
from pathlib import Path
import tempfile
import unittest

if not importlib.util.find_spec("numpy"):
    raise unittest.SkipTest("Use pinned training environment for forest tests")

import numpy as np

from export_forest import export_forest,load_forest,portable_predict


class FixtureBooster:
    def num_feature(self):return 1
    def dump_model(self):
        return {"objective":"binary sigmoid:1","tree_info":[{"tree_structure":{
            "split_feature":0,"threshold":.5,"decision_type":"<=",
            "left_child":{"leaf_value":-2},"right_child":{"leaf_value":2}}}]}


class PortableForestTest(unittest.TestCase):
    def test_numeric_split_and_probability_parity(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"model.vtmf";export_forest(FixtureBooster(),p)
            actual=portable_predict(load_forest(p),np.asarray([[0.],[.5],[1.]]))
            np.testing.assert_allclose(actual,[1/(1+np.exp(2)),1/(1+np.exp(2)),1/(1+np.exp(-2))])
            with self.assertRaises(ValueError):portable_predict(load_forest(p),np.asarray([[np.nan]]))

    def test_invalid_magic_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"bad";p.write_bytes(b"bad magic")
            with self.assertRaises(ValueError):load_forest(p)
