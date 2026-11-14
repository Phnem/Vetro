-- Vetro MicroML collector. Apply with: wrangler d1 execute vetro-microml --remote --file=schema.sql
-- Nothing here identifies a person: `install` is a random id the app generates after consent
-- and replaces every 30 days. There is no IP, user id, title, path or timestamp finer than a day.

CREATE TABLE IF NOT EXISTS uploads (
  install TEXT NOT NULL,
  day     TEXT NOT NULL,
  PRIMARY KEY (install, day)
);

-- Weights of the on-device taste ranker (11 numbers), one row per install, week and content type.
CREATE TABLE IF NOT EXISTS taste (
  install TEXT NOT NULL,
  week    TEXT NOT NULL,
  type    TEXT NOT NULL,
  version TEXT NOT NULL,
  events  INTEGER NOT NULL,
  weights TEXT NOT NULL,
  PRIMARY KEY (install, week, type)
);

-- Similarity vectors of two records with the label the user's own action produced. No install id.
CREATE TABLE IF NOT EXISTS pairs (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  day              TEXT NOT NULL,
  features_version TEXT NOT NULL,
  pack             TEXT NOT NULL,
  source           TEXT NOT NULL,
  label            INTEGER NOT NULL,
  kind_a           INTEGER NOT NULL,
  kind_b           INTEGER NOT NULL,
  features         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS pairs_day ON pairs(day);

-- Genre names an API returned and the app could not map. Analysed only through the view below.
CREATE TABLE IF NOT EXISTS genre_strings (
  text      TEXT NOT NULL,
  install   TEXT NOT NULL,
  first_day TEXT NOT NULL,
  PRIMARY KEY (text, install)
);
CREATE VIEW IF NOT EXISTS genre_candidates AS
  SELECT text, COUNT(*) AS installs FROM genre_strings GROUP BY text HAVING COUNT(*) >= 20;

-- Shadow-mode observation counters and pack versions in use, summed per day.
CREATE TABLE IF NOT EXISTS counters (
  day   TEXT NOT NULL,
  key   TEXT NOT NULL,
  value INTEGER NOT NULL,
  PRIMARY KEY (day, key)
);
