"""Genre normaliser: a raw genre string from any source, in any language -> one of the app's 30 tags.

No extra weights: the shared encoder embeds the raw string and the nearest hand-written
prototype wins if it is close enough. Exact matches short-circuit. Evaluated on Wikidata genre
labels in languages the prototypes do not cover, and on a hand-written list of real source strings.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from identity.features import Encoder  # noqa: E402
from vml.text import normalize  # noqa: E402

# tag id (app/.../GenreRepository.kt) -> prototype strings
PROTOTYPES = {
    "Isekai": ["isekai", "исекай", "исэкай", "異世界", "попаданцы", "another world"],
    "Games": ["games", "game", "игры", "игра", "gaming", "video game", "ゲーム"],
    "Martial Arts": ["martial arts", "боевые искусства", "единоборства", "武道", "kung fu", "wuxia"],
    "Magic": ["magic", "магия", "魔法", "witchcraft"],
    "Mecha": ["mecha", "меха", "мехи", "メカ", "giant robot", "роботы"],
    "Shounen": ["shounen", "shonen", "сёнен", "сёнэн", "少年"],
    "Shoujo": ["shoujo", "shojo", "сёдзё", "сёдзе", "少女"],
    "Seinen": ["seinen", "сэйнэн", "сейнен", "青年"],
    "Slice of Life": ["slice of life", "повседневность", "日常", "everyday life"],
    "Ecchi": ["ecchi", "этти", "エッチ"],
    "Hentai": ["hentai", "хентай"],
    "School": ["school", "школа", "school life", "школьная жизнь", "学園"],
    "Detective": ["detective", "детектив", "探偵", "detective fiction", "whodunit"],
    "Sports": ["sports", "sport", "спорт", "спортивный", "スポーツ"],
    "Action": ["action", "экшен", "боевик", "アクション", "action film"],
    "Adventure": ["adventure", "приключения", "冒険", "adventure film"],
    "Comedy": ["comedy", "комедия", "コメディ", "comedy film"],
    "Drama": ["drama", "драма", "ドラマ", "drama film"],
    "Fantasy": ["fantasy", "фэнтези", "ファンタジー", "fantasy film"],
    "Romance": ["romance", "романтика", "мелодрама", "恋愛", "romance film", "love story", "romantic"],
    "Horror": ["horror", "ужасы", "хоррор", "ホラー", "horror film"],
    "Sci-Fi": ["sci-fi", "science fiction", "научная фантастика", "фантастика", "sf", "サイエンス フィクション", "science fiction film"],
    "Thriller": ["thriller", "триллер", "スリラー", "suspense"],
    "Mystery": ["mystery", "мистика", "ミステリー", "supernatural", "сверхъестественное"],
    "Crime": ["crime", "криминал", "crime film", "犯罪", "gangster film"],
    "Western": ["western", "вестерн", "西部劇"],
    "Animation": ["animation", "анимация", "мультфильм", "animated", "アニメーション", "animated film"],
    "Documentary": ["documentary", "документальный", "документальное", "ドキュメンタリー", "documentary film"],
    "Sitcom": ["sitcom", "ситком", "situation comedy"],
    "Reality": ["reality", "реалити-шоу", "реалити", "reality tv", "reality show"],
}

# Raw strings as the sources really send them -> acceptable tags (empty list = must stay unmapped).
CHALLENGE = [
    ("Action", ["Action"]), ("Adventure", ["Adventure"]), ("Sci-Fi", ["Sci-Fi"]), ("Slice of Life", ["Slice of Life"]),
    ("Supernatural", ["Mystery"]), ("Psychological", []), ("Music", []), ("Mecha", ["Mecha"]), ("Sports", ["Sports"]),
    ("Shounen", ["Shounen"]), ("Shoujo", ["Shoujo"]), ("Seinen", ["Seinen"]), ("Josei", []), ("School", ["School"]),
    ("Martial Arts", ["Martial Arts"]), ("Isekai", ["Isekai"]), ("Gourmet", []), ("Suspense", ["Thriller"]),
    ("Award Winning", []), ("Boys Love", []), ("Сёнен", ["Shounen"]), ("Сёдзё", ["Shoujo"]), ("Сэйнэн", ["Seinen"]),
    ("Экшен", ["Action"]), ("Комедия", ["Comedy"]), ("Повседневность", ["Slice of Life"]), ("Романтика", ["Romance"]),
    ("Фантастика", ["Sci-Fi"]), ("Фэнтези", ["Fantasy"]), ("Школа", ["School"]), ("Спорт", ["Sports"]), ("Меха", ["Mecha"]),
    ("Ужасы", ["Horror"]), ("Детектив", ["Detective"]), ("Драма", ["Drama"]), ("Приключения", ["Adventure"]),
    ("Этти", ["Ecchi"]), ("Сверхъестественное", ["Mystery"]), ("Боевые искусства", ["Martial Arts"]), ("Игры", ["Games"]),
    ("Магия", ["Magic"]), ("Исэкай", ["Isekai"]), ("Триллер", ["Thriller"]), ("Музыка", []), ("Психологическое", []),
    ("Гурман", []), ("Военное", []), ("Science Fiction", ["Sci-Fi"]), ("Action & Adventure", ["Action", "Adventure"]),
    ("Sci-Fi & Fantasy", ["Sci-Fi", "Fantasy"]), ("Crime", ["Crime"]), ("Documentary", ["Documentary"]), ("Western", ["Western"]),
    ("Animation", ["Animation"]), ("Family", []), ("History", []), ("War", []), ("TV Movie", []), ("Reality", ["Reality"]),
    ("Kids", []), ("News", []), ("Soap", []), ("Talk", []), ("War & Politics", []), ("боевик", ["Action"]),
    ("мультфильм", ["Animation"]), ("криминал", ["Crime"]), ("документальный", ["Documentary"]), ("семейный", []),
    ("история", []), ("музыка", []), ("мелодрама", ["Romance"]), ("военный", []), ("вестерн", ["Western"]),
    ("Боевик и Приключения", ["Action", "Adventure"]), ("НФ и Фэнтези", ["Sci-Fi", "Fantasy"]), ("Реалити-шоу", ["Reality"]),
    ("биография", []), ("короткометражка", []), ("アクション", ["Action"]), ("恋愛", ["Romance"]), ("日常", ["Slice of Life"]),
    ("ホラー", ["Horror"]), ("学園", ["School"]), ("SF", ["Sci-Fi"]), ("Komödie", ["Comedy"]), ("Abenteuer", ["Adventure"]),
    ("Science-Fiction", ["Sci-Fi"]), ("Horreur", ["Horror"]), ("Aventure", ["Adventure"]), ("Comédie", ["Comedy"]),
    ("Ciencia ficción", ["Sci-Fi"]), ("Terror", ["Horror"]), ("Krimi", ["Crime"]), ("Liebesfilm", ["Romance"]),
    ("Fantastyka naukowa", ["Sci-Fi"]), ("Комедія", ["Comedy"]), ("Жахи", ["Horror"]), ("Пригоди", ["Adventure"]),
]


class Normalizer:
    def __init__(self, encoder: Encoder, threshold: float):
        self.encoder, self.threshold = encoder, threshold
        self.exact: dict[str, str] = {}
        tags, vectors = [], []
        for tag, texts in PROTOTYPES.items():
            for text in texts:
                norm = normalize(text)
                self.exact.setdefault(norm, tag)
                tags.append(tag)
                vectors.append(encoder.embed(norm))
        self.tags, self.vectors = tags, np.stack(vectors)

    def score(self, raw: str) -> tuple[str | None, float, bool]:
        norm = normalize(raw)
        if not norm:
            return None, 0.0, False
        if norm in self.exact:
            return self.exact[norm], 1.0, True
        sims = self.vectors @ self.encoder.embed(norm)
        best = int(sims.argmax())
        return self.tags[best], float(sims[best]), False

    def __call__(self, raw: str) -> str | None:
        tag, score, _ = self.score(raw)
        return tag if score >= self.threshold else None


def app_baseline(raw: str) -> str | None:
    """mapApiGenresToTagIds: case-insensitive equality with the tag id (= English label) or the Russian label."""
    ru = {"Isekai": "Исекай", "Games": "Игры", "Martial Arts": "Боевые искусства", "Magic": "Магия", "Mecha": "Мехи",
          "Shounen": "Сёнен", "Shoujo": "Сёдзё", "Seinen": "Сэйнен", "Slice of Life": "Повседневность", "Ecchi": "Этти",
          "Hentai": "Хентай", "School": "Школа", "Detective": "Детектив", "Sports": "Спорт", "Action": "Экшен",
          "Adventure": "Приключения", "Comedy": "Комедия", "Drama": "Драма", "Fantasy": "Фэнтези", "Romance": "Романтика",
          "Horror": "Ужасы", "Sci-Fi": "Фантастика", "Thriller": "Триллер", "Mystery": "Мистика", "Crime": "Криминал",
          "Western": "Вестерн", "Animation": "Анимация", "Documentary": "Документальный", "Sitcom": "Ситком",
          "Reality": "Реалити-шоу"}
    low = raw.strip().lower()
    for tag, label in ru.items():
        if low in (tag.lower(), label.lower()) or low.replace("-", " ") == tag.lower().replace("-", " "):
            return tag
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--encoder", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/catalog/genres")
    parser.add_argument("--threshold", type=float, default=0.0, help="0 = choose on the Wikidata development split")
    args = parser.parse_args()
    encoder = Encoder(np.load(args.encoder).astype(np.float32))
    probe = Normalizer(encoder, 2.0)
    genres = json.loads((args.corpus / "genres.json").read_text(encoding="utf-8"))
    # A Wikidata genre maps to a tag when one of its English/Russian labels equals a prototype exactly.
    rows = []
    for q, labels in genres.items():
        tags = {probe.exact[normalize(t)] for lang, t in labels if lang in ("en", "ru") and normalize(t) in probe.exact}
        if len(tags) > 1:
            continue
        tag = next(iter(tags), None)
        held = int(q[1:]) % 7 == 0  # these QIDs were withheld from encoder training
        for lang, text in labels:
            if normalize(text) and normalize(text) not in probe.exact:
                rows.append((q, lang, text, tag, held))
    dev = [r for r in rows if not r[4]]
    test = [r for r in rows if r[4]]

    def predict(items):
        y = np.asarray([r[3] is not None for r in items])
        scores, hits = [], []
        for _, _, text, tag, _ in items:
            guess, score, _ = probe.score(text)
            scores.append(score)
            hits.append(guess == tag)
        return y, np.asarray(scores), np.asarray(hits)

    def evaluate(predictions, threshold: float) -> dict:
        y, scores, hits = predictions
        accept = scores >= threshold
        mapped = y & accept
        correct = mapped & hits
        precision = float(correct.sum() / max(1, accept.sum()))
        recall = float(correct.sum() / max(1, y.sum()))
        return {"strings": len(y), "with_tag": int(y.sum()), "accepted": int(accept.sum()),
                "accuracy": float((correct | (~accept & ~y)).mean()),
                "precision": precision, "recall": recall,
                "f1": 2 * precision * recall / max(1e-9, precision + recall),
                "fpr_unmapped": float((accept & ~y).sum() / max(1, (~y).sum()))}

    dev_predictions, test_predictions = predict(dev), predict(test)
    threshold = args.threshold
    if not threshold:
        best = (0.0, 0.7)
        for t in np.arange(0.40, 0.96, 0.02):
            r = evaluate(dev_predictions, float(t))
            f = 2 * r["precision"] * r["recall"] / max(1e-9, r["precision"] + r["recall"]) if r["precision"] >= 0.9 else 0.0
            if f > best[0]:
                best = (f, float(t))
        threshold = best[1]
    normalizer = Normalizer(encoder, threshold)
    challenge = {"n": len(CHALLENGE), "model_correct": 0, "baseline_correct": 0, "model_wrong_tag": 0, "misses": []}
    for raw, accepted in CHALLENGE:
        got, base = normalizer(raw), app_baseline(raw)
        ok = (got in accepted) if accepted else got is None
        challenge["model_correct"] += ok
        challenge["baseline_correct"] += (base in accepted) if accepted else base is None
        challenge["model_wrong_tag"] += got is not None and got not in accepted
        if not ok:
            challenge["misses"].append([raw, accepted, got, round(normalizer.score(raw)[1], 3)])
    report = {"threshold": threshold, "prototypes": sum(len(v) for v in PROTOTYPES.values()),
              "wikidata_dev": evaluate(dev_predictions, threshold), "wikidata_unseen_genres": evaluate(test_predictions, threshold),
              "challenge": challenge}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "report.json").write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({k: (v if k != "challenge" else {kk: vv for kk, vv in v.items()}) for k, v in report.items()
                      if k != "wikidata_dev"} | {"dev": {k: v for k, v in report["wikidata_dev"].items() if k != "detail"}},
                     ensure_ascii=False)[:3000])


if __name__ == "__main__":
    main()
