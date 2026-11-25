-- Выравнивает файлы миграций с боевой базой. Там ограничение уже именно такое (проверено
-- 2026-11-25), а 20260626100000 разрешает только ANIME/MANGA/TV_SERIES: в базе, поднятой из
-- файлов, строка 'MOVIE' или 'SERIES' от клиента валила бы upsert всей пачки.
ALTER TABLE public.anime
    DROP CONSTRAINT IF EXISTS anime_media_type_check;

ALTER TABLE public.anime
    ADD CONSTRAINT anime_media_type_check
    CHECK (media_type IN ('ANIME', 'MANGA', 'MOVIE', 'SERIES', 'TV_SERIES'));

NOTIFY pgrst, 'reload schema';
