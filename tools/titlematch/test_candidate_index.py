from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

from candidate_index import CandidateIndex, add_ngrams


class CandidateRetrievalTest(unittest.TestCase):
    def test_scope_collisions_prefix_and_typo(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"fixture.sqlite"
            with closing(sqlite3.connect(p)) as db:
                db.executescript('''CREATE TABLE entity(id TEXT,kind TEXT);
                  CREATE TABLE alias(entity_id TEXT,key TEXT);
                  CREATE TABLE external_id(entity_id TEXT,typed_id TEXT);
                  CREATE VIRTUAL TABLE search USING fts5(text,entity_id UNINDEXED);''')
                db.executemany("INSERT INTO entity VALUES (?,?)",[("a","ANIME"),("b","MANGA"),("c","ANIME")])
                db.executemany("INSERT INTO alias VALUES (?,?)",[("a","exampletitle"),("b","exampletitle"),("c","exampletitle")])
                db.executemany("INSERT INTO search VALUES (?,?)",[("example title",e) for e in ("a","b","c")])
                db.commit()
            add_ngrams(p,p)
            index=CandidateIndex(p)
            try:
                self.assertEqual({"a","c"},index.exact("Example Title","ANIME"))
                self.assertEqual({"b"},index.exact("Example Title","MANGA"))
                self.assertEqual({"a","c"},set(index.search("examp","ANIME")))
                self.assertEqual({"a","c"},set(index.search("exmpletitle","ANIME")))
                self.assertEqual([],index.search("","ANIME"))
            finally:
                index.close()


if __name__ == "__main__":
    unittest.main()
