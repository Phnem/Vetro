-- Клиент давно пишет в anime.media_type значения 'MOVIE' и 'SERIES', а ограничение из
-- 20260626100000 разрешает только ANIME/MANGA/TV_SERIES: такая строка валит upsert всей пачки.
ALTER TABLE public.anime
    DROP CONSTRAINT IF EXISTS anime_media_type_check;

ALTER TABLE public.anime
    ADD CONSTRAINT anime_media_type_check
    CHECK (media_type IN ('ANIME', 'MANGA', 'MOVIE', 'SERIES', 'TV_SERIES'));

NOTIFY pgrst, 'reload schema';
