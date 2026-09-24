# ADR-0001: Audiobook storage and stable identity

Status: accepted — 2026-09-23

## Context

Vetro stores its common library records in the historically named `anime` table. Rating,
favorites, tags, notes, backup, and Home use these records. A book also needs works, narrations,
source variants, chapters, progress, and bookmarks. The initial audiobook draft used a SHA-1 of
normalized author, title, and language as the work primary key; improving normalization would
then change the identity of an existing book. Older Vetro clients do not understand
`MediaType.AUDIOBOOK`: `MediaType.fromPersistedValue` falls back to `ANIME` for unknown values.

The repository's Supabase migration
`20260626100000_add_media_type_to_anime.sql` declares a CHECK for only `ANIME`, `MANGA`, and
`TV_SERIES`. It does not match the current app enum's `MOVIE` and `SERIES` values. The deployed
schema has not been verified and must be checked before any cloud rollout.

## Decision

1. An audiobook added to the library gets an `anime` collection row with
   `mediaType = AUDIOBOOK`. Work, narration, variant, timeline, progress, bookmark, and session
   data live in dedicated local audiobook tables.
2. Home “All” includes audiobook entries. The Books page remains their specialized shelf home.
3. `WorkId` and `NarrationId` are permanent UUIDs. Every audiobook PK/FK and progress reference
   uses these IDs. The old normalized-field SHA-1 is named `clusterFingerprint`, indexed for
   matching candidates, and may be recalculated without changing identity. A true merge of
   two existing works is a separate transactional operation with an alias for the retired UUID.
4. Audiobook collection rows are excluded from Supabase push and pull until a later compatible
   client release and an explicit sync rollout. Audiobook-specific tables are local in V1.
5. The `MediaLibraryService` is a separate Android component in the main app process. No
   `android:process` attribute is used for it.

## Alternatives considered

- A fully separate audiobook library would duplicate existing collection behavior and UI.
- Fitting all audiobook data into the `anime` row would lose the Work → Narration → Variant
  structure and reliable progress mapping.
- A normalized-field hash as work identity would make normalizer improvements into identity
  migrations and require alias/progress repair for routine metadata changes.
- Immediate cloud sync would expose unknown media types to older clients and could trigger
  anime enrichment on audiobook rows.

## Consequences

- AB-12 must explicitly guard all sync paths, enrichment, episode workers, and Home routing
  before creating `AUDIOBOOK` collection rows. The existing Home “All” filter can include them.
- AB-13 migration uses UUID columns as PK/FK and separate indexed cluster fingerprints.
- Backup/export can reuse collection metadata, while a complete audiobook backup must also
  include audiobook-specific tables; this is a required acceptance check in AB-13.
- Cloud sync rollout requires deployed schema verification, an appropriate CHECK migration,
  compatibility testing with old clients, and its own release decision.

## Codebase impact observed at decision time

- Model and persisted conversion: `data/models/Anime.kt`, `data/local/AnimeLocalDataSource.kt`.
- Sync: `sync/supabase/SyncRepository.kt` reads and writes media type.
- Home and routing: `ui/home/HomeViewModel.kt`, `HomeScreen.kt`, `HomeComponents.kt`,
  `ui/details/DetailsScreen.kt`, workspace Books page.
- Enrichment and update work: `domain/enrichment/`, `domain/titles/`, `updates/`, `worker/`.
- Search/add/edit and recommendations: `domain/search/`, `domain/addedit/`,
  `domain/recommendations/`.

`graft callers MediaType --depth 2` found no indexed incoming edges for the enum; the above
impact list was confirmed with repository text search. It is a planning inventory, not proof
that every call site has been guarded. AB-12 must do the full call-site audit.
