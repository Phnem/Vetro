-- Аудиокниги в облаке: только данные (книга, озвучки, ссылки на источники, прогресс, закладки).
-- Аудиофайлы сюда не попадают никогда; ссылки на локальные папки (content://) клиент не выгружает.
-- Клиент: sync/supabase/AudiobookSyncRepository.kt. Поля DTO = колонки 1-в-1.

CREATE TABLE IF NOT EXISTS public.audiobook_work (
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    work_id text NOT NULL,
    cluster_fingerprint text NOT NULL DEFAULT '',
    title text NOT NULL DEFAULT '',
    title_original text,
    authors jsonb NOT NULL DEFAULT '[]'::jsonb,
    series_title text,
    series_index double precision,
    description text,
    genres jsonb NOT NULL DEFAULT '[]'::jsonb,
    language text NOT NULL DEFAULT 'RU',
    year integer,
    is_collection boolean NOT NULL DEFAULT false,
    cover_url text,
    selected_narration_id text,
    is_favorite boolean NOT NULL DEFAULT false,
    in_library boolean NOT NULL DEFAULT false,
    -- [{narration_id, narrators[], kind, duration_ms, chapter_count, variants:[{variant_id, source_id, source_url, …}]}]
    narrations jsonb NOT NULL DEFAULT '[]'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, work_id)
);
CREATE INDEX IF NOT EXISTS audiobook_work_user_updated ON public.audiobook_work (user_id, updated_at);

CREATE TABLE IF NOT EXISTS public.audiobook_progress (
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    narration_id text NOT NULL,
    work_id text NOT NULL,
    variant_id text,
    global_ms bigint NOT NULL DEFAULT 0,
    chapter_idx integer NOT NULL DEFAULT 0,
    chapter_offset_ms bigint NOT NULL DEFAULT 0,
    total_ms bigint,
    speed real NOT NULL DEFAULT 1,
    finished boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, narration_id)
);
CREATE INDEX IF NOT EXISTS audiobook_progress_user_updated ON public.audiobook_progress (user_id, updated_at);

-- Закладки озвучки одним набором: удаление закладки — это новый набор без неё.
CREATE TABLE IF NOT EXISTS public.audiobook_bookmarks (
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    narration_id text NOT NULL,
    work_id text NOT NULL,
    -- [{id, global_ms, chapter_idx, chapter_offset_ms, note, created_at}]
    bookmarks jsonb NOT NULL DEFAULT '[]'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, narration_id)
);
CREATE INDEX IF NOT EXISTS audiobook_bookmarks_user_updated ON public.audiobook_bookmarks (user_id, updated_at);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['audiobook_work', 'audiobook_progress', 'audiobook_bookmarks']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS "own rows: select" ON public.%I', t);
        EXECUTE format('DROP POLICY IF EXISTS "own rows: insert" ON public.%I', t);
        EXECUTE format('DROP POLICY IF EXISTS "own rows: update" ON public.%I', t);
        EXECUTE format('DROP POLICY IF EXISTS "own rows: delete" ON public.%I', t);
        EXECUTE format('CREATE POLICY "own rows: select" ON public.%I FOR SELECT TO authenticated USING ((select auth.uid()) = user_id)', t);
        EXECUTE format('CREATE POLICY "own rows: insert" ON public.%I FOR INSERT TO authenticated WITH CHECK ((select auth.uid()) = user_id)', t);
        EXECUTE format('CREATE POLICY "own rows: update" ON public.%I FOR UPDATE TO authenticated USING ((select auth.uid()) = user_id) WITH CHECK ((select auth.uid()) = user_id)', t);
        EXECUTE format('CREATE POLICY "own rows: delete" ON public.%I FOR DELETE TO authenticated USING ((select auth.uid()) = user_id)', t);
    END LOOP;
END $$;

NOTIFY pgrst, 'reload schema';
