"""python -I -m unittest discover -s tests   (run from book-dictionary/v2)"""
import json
import os
import sqlite3
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "build"))

import details  # noqa: E402

GOLDEN = json.load(open(os.path.join(HERE, "golden_genres.json"), encoding="utf-8"))


class GenreTests(unittest.TestCase):
    def test_golden_cases(self):
        for case in GOLDEN["cases"]:
            self.assertEqual(details.genre_keys(case["tags"]), case["keys"], case["tags"])

    def test_names_table_matches_golden(self):
        # The Kotlin side (BookGenres.kt) is checked against the same file, so a rename here fails there too.
        self.assertEqual({k: list(v) for k, v in details.GENRE_NAMES.items()}, GOLDEN["names"])

    def test_every_mapped_key_has_a_name(self):
        self.assertLessEqual(set(details._TAG_TO_KEY.values()) | {k for _, k in details._PREFIX_RULES}, set(details.GENRE_NAMES))


class FieldTests(unittest.TestCase):
    def test_description_stubs_are_dropped(self):
        self.assertIsNone(details.clean_description("216 p. ; 18 cm"))
        self.assertIsNone(details.clean_description("> Blockquote"))
        self.assertIsNone(details.clean_description("Autobiography"))
        text = "Марк Тумидус, офицер ВКС Помпилии, пропал без вести за краем Ойкумены. Сбилась с ног внешняя разведка."
        self.assertEqual(details.clean_description("**" + text + "**  "), text)

    def test_rating_needs_votes(self):
        self.assertEqual(details.parse_rating("8.30 (1065 votes)"), {"v": 8.3, "n": 1065})
        self.assertIsNone(details.parse_rating("9.00 (2 votes)"))
        self.assertIsNone(details.parse_rating("n/a"))
        self.assertIsNone(details.parse_rating(None))

    def test_cjk_series_on_a_russian_novel_is_a_mislink(self):
        self.assertFalse(details.series_plausible("ブリーチ", "Второй контакт", "Second Contact", None, None))
        self.assertTrue(details.series_plausible("ブリーチ", "ブリーチ 1", None, None, None))
        self.assertTrue(details.series_plausible("Ойкумена", "Волк", "The Wolf", "Генри Лайон Олди", None))

    def test_extras_card(self):
        e = details.extras({
            "description": "Марк Тумидус, офицер ВКС Помпилии, пропал без вести за краем Ойкумены. Сбилась с ног внешняя разведка.",
            "description_lang": "RU", "genres_json": json.dumps(["Фантастика", "Любой", "Приключенческое"]),
            "original_language": "RU", "is_collection": 1, "chapter_count": 0, "audiobook_duration_minutes": 860,
            "additional_notes_json": json.dumps({"fantlab_rating": "8.30 (1065 votes)"}),
            "series_title": "Ойкумена", "series_title_ru": "Ойкумена", "series_title_en": None, "series_index": "2",
            "series_index_num": 2.0, "ru_title": "Волк", "en_title": "The Wolf", "ru_author": "Генри Лайон Олди", "en_author": None,
        })
        self.assertEqual(e["gen"], ["scifi", "adventure"])
        self.assertEqual((e["ol"], e["coll"], e["dur"], e["rate"]["n"]), ("ru", 1, 860, 1065))
        self.assertEqual(e["desc"]["l"], "ru")
        self.assertNotIn("ch", e)  # zero chapters is "unknown", not a fact
        self.assertEqual(e["series"], [{"ru": "Ойкумена", "pos": 2}])

    def test_empty_row_gives_empty_card(self):
        self.assertEqual(details.extras({"genres_json": "[]", "additional_notes_json": "{}"}), {})

    def test_series_title_only_is_split_by_script(self):
        ru = details.extras({"series_title": "Сказки для театра", "ru_title": "Зелёная птичка"})
        en = details.extras({"series_title": "How Did We Find Out About...", "en_title": "x"})
        self.assertEqual(ru["series"], [{"ru": "Сказки для театра"}])
        self.assertEqual(en["series"], [{"en": "How Did We Find Out About..."}])

    def test_load_extras_filters_by_id_and_skips_empty(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "details.sqlite")
            conn = sqlite3.connect(path)
            conn.execute(
                "CREATE TABLE work_details (vetro_id TEXT PRIMARY KEY, series_title TEXT, series_title_ru TEXT, series_title_en TEXT, "
                "series_index TEXT, series_index_num REAL, genres_json TEXT, description TEXT, description_lang TEXT, "
                "original_language TEXT, is_collection INTEGER, chapter_count INTEGER, audiobook_duration_minutes INTEGER, "
                "additional_notes_json TEXT, ru_title TEXT, en_title TEXT, ru_author TEXT, en_author TEXT)")
            conn.execute("INSERT INTO work_details (vetro_id, genres_json, original_language, additional_notes_json) "
                         "VALUES ('000000771', '[\"Фэнтези\"]', 'EN', '{}')")
            conn.execute("INSERT INTO work_details (vetro_id, genres_json, additional_notes_json) VALUES ('000000005', '[]', '{}')")
            conn.execute("INSERT INTO work_details (vetro_id, genres_json, original_language, additional_notes_json) "
                         "VALUES ('000000006', '[]', 'DE', '{}')")
            conn.commit()
            conn.close()
            got = details.load_extras(path, {"000000771", "000000005"})
        self.assertEqual(got, {771: {"gen": ["fantasy"], "ol": "en"}})


if __name__ == "__main__":
    unittest.main()
