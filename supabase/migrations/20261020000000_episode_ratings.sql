-- Личные оценки серий (аниме, сериалы, фильмы).
-- Запусти ОДИН РАЗ в Supabase Dashboard → SQL Editor → Run (или `supabase db push`).
--
-- Отдельная таблица, а не колонка в episode_progress: прогресс пишется раз в секунду просмотра
-- со своим updated_at, а слияние идёт LWW на строке целиком — оценка, выставленная вручную,
-- проигрывала бы любой следующей секунде воспроизведения. Плюс оценить можно серию, которую в
-- приложении не открывали: позиции и длительности у неё нет, а строка прогресса без них не пишется.
--
-- rating — целое ×10 (0..100), как anime.rating; 0 = оценка снята. Строку при снятии НЕ удаляем:
-- иначе снятие не доедет до остальных устройств (LWW-слияние ничего не знает про удалённое).
--
-- updated_at клиент пишет свой локальный, а не now(): курсор pull иначе считал бы чужую запись
-- новее собственной (та же логика, что в progress_sync).
--
-- FK к public.anime намеренно НЕТ: оценка может приехать по тайтлу, которого на этом
-- устройстве ещё нет. Целостность по пользователю — RLS + FK к auth.users.

CREATE TABLE IF NOT EXISTS public.episode_ratings (
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    anime_id text NOT NULL,
    season integer NOT NULL,
    episode integer NOT NULL,
    rating integer NOT NULL DEFAULT 0 CHECK (rating BETWEEN 0 AND 100),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, anime_id, season, episode)
);

CREATE INDEX IF NOT EXISTS episode_ratings_user_updated
    ON public.episode_ratings (user_id, updated_at);

ALTER TABLE public.episode_ratings ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'episode_ratings' AND policyname = 'Users can view their own episode ratings'
    ) THEN
        CREATE POLICY "Users can view their own episode ratings"   ON public.episode_ratings FOR SELECT USING (auth.uid() = user_id);
        CREATE POLICY "Users can insert their own episode ratings" ON public.episode_ratings FOR INSERT WITH CHECK (auth.uid() = user_id);
        CREATE POLICY "Users can update their own episode ratings" ON public.episode_ratings FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
        CREATE POLICY "Users can delete their own episode ratings" ON public.episode_ratings FOR DELETE USING (auth.uid() = user_id);
    END IF;
END $$;

-- Realtime не включаем: на таблицу никто не подписывается (SupabaseSyncCoordinator слушает
-- только `anime`), публикация гнала бы WAL-трафик впустую.

-- Важно: сбросить кэш схемы PostgREST — иначе таблица ещё «не видна» API.
NOTIFY pgrst, 'reload schema';
