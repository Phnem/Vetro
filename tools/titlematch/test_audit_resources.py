import json
from pathlib import Path
import tempfile
import unittest

from audit_resources import anime_snapshot


class SnapshotAuditTest(unittest.TestCase):
    def snapshot(self, rows):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        file = Path(temp.name) / "snapshot.jsonl"
        file.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
        return file

    def test_alias_collision_does_not_become_identity(self):
        file = self.snapshot([
            {"license": "fixture"},
            {"title": "Example", "synonyms": ["EXAMPLE", "Пример"], "sources": ["id/1"]},
            {"title": "Other", "synonyms": ["Example"], "sources": ["id/2"], "relatedAnime": ["id/1"]},
        ])
        report, ids = anime_snapshot(file)
        self.assertEqual(2, report["records"])
        self.assertEqual(1, report["alias_keys_shared_by_multiple_records"])
        self.assertEqual(1, report["relationship_edges_not_identity"])
        self.assertEqual({"id/1", "id/2"}, ids)

    def test_unknown_source_schema_fails_instead_of_silent_coercion(self):
        file = self.snapshot([{"license": "fixture"}, {"title": "A", "sources": "id/1"}])
        with self.assertRaisesRegex(ValueError, "unsupported"):
            anime_snapshot(file)

    def test_missing_metadata_fails(self):
        file = self.snapshot([{"title": "A", "sources": [], "synonyms": []}])
        with self.assertRaisesRegex(ValueError, "metadata"):
            anime_snapshot(file)
