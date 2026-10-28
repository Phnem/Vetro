import unittest
import hashlib
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile

from data_pipeline import build, group_split, key, normalize, scoped_id


class IdentityDataTest(unittest.TestCase):
    def test_namespaces_and_levels_are_distinct(self):
        self.assertNotEqual(scoped_id("MOVIE", "550"), scoped_id("SERIES", "550"))
        self.assertNotEqual(scoped_id("ANIME", "21"), scoped_id("MANGA", "21"))
        with self.assertRaises(ValueError):
            scoped_id("BOOK_WORK", "OL123M")  # edition, not work

    def test_preserves_season_and_part_numbers(self):
        self.assertNotEqual(key("Example Season II"), key("Example Season III"))
        self.assertNotEqual(key("Example 1"), key("Example 2"))
        self.assertEqual(normalize("ＡＢＣ　Title"), "abc title")

    def test_grouped_split_never_separates_franchise_or_adaptation(self):
        entities = [
            {"entity_id": "a", "qid": "Q1", "kind": "ANIME"},
            {"entity_id": "b", "qid": "Q1", "kind": "MANGA"},
            {"entity_id": "c", "qid": "Q2", "kind": "ANIME"},
            {"entity_id": "d", "qid": "Q3", "kind": "MOVIE"},
        ]
        aliases = [{"entity_id": "a", "search_key": "shared"},
                   {"entity_id": "d", "search_key": "shared"}]
        relations = [{"entity_id": "c", "target_qid": "Q1"}]
        assignments, _ = group_split(entities, aliases, relations)
        self.assertEqual(1, len({assignments[e["entity_id"]]["split"] for e in entities}))
        self.assertEqual(1, len({assignments[e["entity_id"]]["group_id"] for e in entities}))

    @unittest.skipUnless(importlib.util.find_spec("pyarrow"), "pyarrow training environment required")
    def test_build_closes_database_and_keeps_alias_collision(self):
        with tempfile.TemporaryDirectory() as folder:
            raw = Path(folder) / "raw"
            raw.mkdir()
            rows = [{"item": {"value": "http://www.wikidata.org/entity/Q"+str(i)},
                     "external": {"value": str(i)},
                     "text": {"value": "Shared fixture", "xml:lang": "en"}} for i in (1,2)]
            content = json.dumps({"results": {"bindings": rows}}).encode()
            (raw/"anime-000000.json").write_bytes(content)
            (raw/"anime-000000.receipt.json").write_text(json.dumps({"kind":"ANIME", "license":"CC0-1.0",
                "sha256":hashlib.sha256(content).hexdigest(), "query_sha256":"fixture", "retrieved_at":"fixture"}))
            output = Path(folder)/"out"
            report = build(raw, output)
            self.assertEqual(2,report["counts"]["entities"])
            self.assertEqual(1,report["collision_keys"])
            with closing(sqlite3.connect(output/"dictionary.sqlite")) as db:
                self.assertEqual(2,db.execute("SELECT count(DISTINCT entity_id) FROM alias WHERE key='sharedfixture'").fetchone()[0])

    @unittest.skipUnless(importlib.util.find_spec("pyarrow"), "pyarrow training environment required")
    def test_multiple_catalog_ids_on_one_qid_are_quarantined(self):
        with tempfile.TemporaryDirectory() as folder:
            raw=Path(folder)/"raw";raw.mkdir()
            rows=[{"item":{"value":"http://www.wikidata.org/entity/Q1"},"external":{"value":str(i)},
                   "text":{"value":"Different seasons grouped by source","xml:lang":"en"}} for i in (1,2)]
            content=json.dumps({"results":{"bindings":rows}}).encode();(raw/"anime-000000.json").write_bytes(content)
            (raw/"anime-000000.receipt.json").write_text(json.dumps({"kind":"ANIME","license":"CC0-1.0",
                "sha256":hashlib.sha256(content).hexdigest(),"query_sha256":"fixture","retrieved_at":"fixture"}))
            report=build(raw,Path(folder)/"out")
            self.assertEqual(0,report["counts"]["entities"])
            self.assertEqual(1,report["counts"]["quarantine"])


if __name__ == "__main__":
    unittest.main()
