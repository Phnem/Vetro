// Query normalisation. Mirrors book-dictionary/v2/build/common.py `norm` and the app's BookNorm.kt;
// the shared golden cases live in book-dictionary/v2/tests/golden_norm.json.

const EXTRA = { "ß": "ss", "æ": "ae", "œ": "oe", "ø": "o", "ł": "l", "đ": "d", "ı": "i", "þ": "th", "ð": "d" };
const MARK = /\p{M}/u;

export function norm(text) {
  if (!text) return "";
  // й is a letter of its own (война != воина): hide it from NFKD, which would split off the breve.
  const s = text.toLowerCase().replaceAll("й", "").normalize("NFKD");
  let out = "";
  for (const ch of s) {
    if (MARK.test(ch)) continue;
    out += EXTRA[ch] ?? ch;
  }
  return out.replaceAll("", "й").replace(/[^0-9a-zа-я]+/g, " ").trim();
}
