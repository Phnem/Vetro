"""python -I -m unittest discover -s tests   (run from book-dictionary/v2 with build/ on the path)"""
import json
import os
import sqlite3
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "build"))

import bookindex  # noqa: E402
from common import extra_tokens, norm, translit  # noqa: E402


def row(i, ru, en, ra, ea, alt="", year=None, flags=0, cv=0):
    return (i, ru, en, ra, ea, alt, extra_tokens(ru, en, ra, ea, alt), year, flags, cv)


BOOKS = [
    row(771, "Фунты лиха в Париже и Лондоне", "Down and Out in Paris and London", "Джордж Оруэлл", "George Orwell", "", 1933, 1),
    row(5, "Чайка", "The Seagull", "Антон Чехов", "Anton Chekhov", "", 1896, 1),
    row(6, "Ёлка", None, "Иван Иванов", None, "", None, 0),
]


def build(path, rows, version=1):
    conn = bookindex.create(path)
    bookindex.insert_rows(conn, rows)
    bookindex.set_meta(conn, schema=bookindex.SCHEMA_VERSION, version=version)
    bookindex.finalize(conn)


def search(path, q):
    conn = sqlite3.connect(path)
    terms = " ".join(t + "*" for t in norm(q).split())
    out = [r[0] for r in conn.execute("SELECT docid FROM books WHERE books MATCH ? ORDER BY docid", (terms,))]
    conn.close()
    return out


class NormTest(unittest.TestCase):
    def test_golden(self):
        with open(os.path.join(HERE, "golden_norm.json"), encoding="utf-8") as f:
            for case in json.load(f):
                self.assertEqual(norm(case["in"]), case["norm"], case["in"])
                if "translit" in case:
                    self.assertEqual(translit(norm(case["in"])), case["translit"], case["in"])


class IndexTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.dir.name, "idx.sqlite")
        build(self.db, BOOKS)

    def tearDown(self):
        self.dir.cleanup()

    def test_search_forms(self):
        self.assertEqual(search(self.db, "оруэлл"), [771])           # author, RU
        self.assertEqual(search(self.db, "down paris"), [771])       # EN title tokens
        self.assertEqual(search(self.db, "фунт"), [771])             # prefix
        self.assertEqual(search(self.db, "елка"), [6])               # е for ё (folded token column)
        self.assertEqual(search(self.db, "Ёлка"), [6])
        self.assertEqual(search(self.db, "chayka"), [5])             # Latin transliteration of Чайка
        self.assertEqual(search(self.db, "chekhov seagull"), [5])    # cross-column AND

    def test_schema_flags(self):
        conn = sqlite3.connect(self.db)
        self.assertEqual(conn.execute("PRAGMA user_version").fetchone()[0], 2)
        self.assertEqual(conn.execute("SELECT f FROM books WHERE docid=771").fetchone()[0], 1)
        conn.close()

    def test_delta_roundtrip(self):
        new_rows = [BOOKS[0], row(5, "Чайка", "The Seagull", "Антон Павлович Чехов", "Anton Chekhov", "", 1896, 1),
                    row(9, "Новая книга", "A New Book", "Автор", "Author", "", 2026, 1)]  # 6 deleted, 5 changed, 9 added
        new_db = os.path.join(self.dir.name, "new.sqlite")
        build(new_db, new_rows, version=2)
        delta = os.path.join(self.dir.name, "d.zst")
        st = bookindex.make_delta(self.db, new_db, delta, 1, 2)
        self.assertEqual((st["upserts"], st["deletes"]), (2, 1))
        bookindex.apply_delta(self.db, delta)
        self.assertEqual(bookindex.read_all(self.db), bookindex.read_all(new_db))
        self.assertEqual(bookindex.read_meta(self.db)["version"], "2")
        self.assertEqual(search(self.db, "павлович"), [5])
        self.assertEqual(search(self.db, "елка"), [])

    def test_delta_wrong_base_is_rejected_and_nothing_applied(self):
        new_db = os.path.join(self.dir.name, "new.sqlite")
        build(new_db, BOOKS[:1], version=3)
        delta = os.path.join(self.dir.name, "d.zst")
        bookindex.make_delta(self.db, new_db, delta, 2, 3)  # claims base 2, device is at 1
        before = bookindex.read_all(self.db)
        with self.assertRaises(ValueError):
            bookindex.apply_delta(self.db, delta)
        self.assertEqual(bookindex.read_all(self.db), before)


if __name__ == "__main__":
    unittest.main()
