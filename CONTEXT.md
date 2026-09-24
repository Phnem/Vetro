# Vetro Collection — domain vocabulary

## Collection

- **Collection entry**: a row in the existing `anime` table. Its `mediaType` says whether it
  represents anime, manga, a movie, a series, or an audiobook. The table name is historical.
- **Audiobook**: the fifth media type. Its collection entry owns common library attributes such
  as rating, favorites, tags, and notes. Audiobook-specific structure lives in separate tables.
- **Books home**: the specialized shelf view of audiobooks in the collection. The Home “All”
  filter also includes them.

## Audiobooks

- **Work**: a literary work. `WorkId` is a permanent UUID used by references and storage.
- **Cluster fingerprint**: a reproducible hash of normalized author, title, and language used
  only to find likely matching works. It can change when normalization improves.
- **Narration**: a particular recording/reading of a work. It has a permanent `NarrationId` and
  is the unit to which listening progress is attached.
- **Provider variant**: one source's available copy of a narration. The player can switch
  variants without changing the selected narration.
- **Book timeline**: the narration's logical time axis across its files and chapters. Playback
  progress is saved on this axis, independently of a provider's file split.
- **Restricted variant**: a copy that its source marks unavailable, including a rightsholder
  restriction. The variant cannot be played there; an independent source may provide the same
  narration.

## Sync boundary

Audiobook collection entries and audiobook-specific tables remain local until a compatible
client release has been rolled out and cloud synchronization is explicitly enabled.
