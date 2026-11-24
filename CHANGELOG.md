# Vetro changelog

## v3.6.1-Alpha — November 24, 2026

App version: `v3.6.1-Alpha`. Android version code: `361`.
Changes since 3.6.0-Beta. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.
Both features below are experiments: each has its own switch in Settings, For developers, and both are off by default, so nothing changes until you turn them on.

### Details from search (experimental)

- With the "Details from search" switch on, tapping a result in search opens a title page with the poster, name, chips, genres and description taken from the search result. There is no Watch, Download or timers there: first add the title, then work with it. Audiobook results still behave as before.
- The "+ Add" pill is alive: it glints now and then; on tap the label disappears, the plus slides to the centre and spins while the title is being saved, then slows down and turns into a check mark.
- Right after the check an iOS NameDrop-style effect plays: the screen is pulled up and blurred, a lens wave runs down from the top, and under its cover the page is replaced by the usual title page with Watch, Download, the bell and the rest. Then a mini notification says that the title was added to your collection.
- The effect is a shader and runs on Android 13 and newer. On Android 12 it is a simple pull with blur, and on older versions a plain pull. A title that is already in the collection opens the usual title page at once, with no animation.

### New bookshelves (experimental)

- With the "New bookshelves" switch on, the audiobook shelves are redrawn. Books become thin and strict instead of the old chunky low-poly ones: a slice of the cover for the spine, a hinge, a page edge and a contact shadow.
- A shelf with up to 3 books shows them thin and slightly turned, in real perspective. A shelf with more than 3 books becomes a real shelf: every book stands spine out, the cover shows through the shade, the title runs along the spine, and a row that does not fit scrolls sideways.
- Inside an opened shelf the books lie almost flat in a two-column grid. A strip of spines on top flies in from the shelf card; tapping a spine highlights the book and scrolls the grid to it.
- With the switch off the shelves are exactly as in 3.6.0-Beta.

## v3.6.0-Beta — November 22, 2026

App version: `v3.6.0-Beta`. Android version code: `360`.
Changes since 3.4.9-Stable. This build rolls up every Alpha from 3.5.0 to 3.5.9 (the in-app updater ignores Alpha tags, so this is the first update that carries them) and adds the share-card and title-page polish made after 3.5.9.

### Audiobooks: the Vetro book catalogue

- A new first source for audiobooks: a catalogue of about 176,000 books. It keeps a search index on the phone, so lookups work offline and instantly, and asks the server only when the local index is not enough. The index is refreshed to version 3.
- The book page and the library show the catalogue details: description, genres, series, the original title and the rating. Covers come from the same catalogue (about 1,000 new ones). If the index cannot be refreshed, the reason is written to the log instead of being swallowed.
- The app proves to the catalogue server that it is the real Vetro (Android Key Attestation, no Google services needed) and gets an install token for 30 days. Phones without hardware attestation, and builds the server does not know, keep working with the sources used before. The token goes only to the catalogue host, and tokens are refused for devices whose attestation certificates Google has revoked.

### Search, catalogue linking and recommendations

- Search by direction asks the Vetro catalogue first: Anime looks only in the anime database, TV Series in series and films (each result keeps its own type), so an anime query no longer returns books or series. A film found under the TV Series tab is labelled as a film. Manga and Books work as before.
- First start of this version: a short screen with the mascot links the titles of your collection to the Vetro catalogue, first by the ids they already have (AniList, MAL, Shikimori, TMDB, IMDb), then by name, and downloads a cover where a title lacks one. If the network is down nothing is marked and it tries again next launch; after 30 seconds you can continue in the background. Titles are linked once; new ones are linked quietly when recommendations refresh.
- Recommendations use the same algorithm, but candidates are also checked against the catalogue: a title that is already in your collection under other ids or another name is no longer offered, and records of one title from different sources are merged by the catalogue id.

### Smart models and local AI

- Three small model packs now run on the phone: Identity (title matching across anime, films, series, manga and audiobooks), Catalog (file-name parsing for audiobook folders, person names, title language, genre aliases) and Experience (genre associations and a ranker that learns from your own ratings and picks). They start in observation mode and change nothing until you turn an effect on.
- Settings has a Smart models section with one switch per model: title matching, files and genres, taste recommendations. All are off until you turn them on, and each switch also enables what that model can change (search order, genre aliases, recommendation order). Together they are one "Local AI" row with a single switch; the developer section has one "Local AI" row with the "previous version" switches and the observation counters.
- "Files and genres" also recognises the stock picture some sources serve instead of a cover (Shikimori's "404 not found" girl): it is no longer saved as a cover, and covers already in your collection are checked once and replaced from the Vetro catalogue or removed so the usual cover search takes over. Nothing changes if the catalogue cannot be reached.
- "Help improve Vetro" is off by default and asks for your consent before it turns on. Once a day it can send anonymous numbers about how the models work, plus the public facts of books that other sites returned but the catalogue lacks. Search queries and your collection are never sent, and "Show the exact text of the next upload" lives in the option's details. It exists only in builds signed by the developer.

### Player and notifications

- "Not dubbed yet" screen: when an anime source stays silent twice in a row and the episode has fewer than 4 dubbing studios and came out at most 2 days ago, the player shows the mascot with "Looks like the studios haven't dubbed this episode yet" and three buttons: Exit, Settings and retry.
- New switch Availability threshold (off by default): a new episode with fewer than 4 dubbing studios and at most 2 days since release gives no notification and no card until either condition stops holding. The audit repeats every 6 hours.
- A bell button on the title screen switches notifications of that title off and on: no push, no Home card, no notification-centre entry, no waiting for dubbing; the release calendar still shows it. The setting stays on the phone, series keep counting episodes so nothing floods in later, and the words "Notifications on / off" are only for screen readers.
- Quiet reminders: a gentle notification when you left a title for 3 weeks or more after at least 2 episodes or chapters and there is still something released to continue. Completed and Dropped titles, titles with the bell off and titles in the secret folder are skipped. It has its own low-priority channel, comes at most once in 3 days between 10:00 and 21:00, never about the same title within 45 days and at most twice for one pause. Tapping it opens the title; there is a switch in Settings.

### Settings

- Press and hold any setting, including the developer section and the smart models, to read what it does in a bottom sheet with a grey "Back" button and an orange "Turn on" / "Turn off" / "Open" button. Rows without a written description get one built from their title and subtitle.
- Coming from the player's "Settings" button scrolls to the option and flashes it; the first time, a pointer also shows how to hold a row.

### Text fields and mini notifications

- Every text field got a new feel: each letter drops in with a bounce, deleted letters float away, the caret glides and stretches with its speed, the label rises above the text and the frame thickens on focus. With "Remove animations" on, the letters simply appear. The drop is about a quarter gentler than in the first Alpha.
- Mini notifications (experimental, Settings, For developers): short messages and sync progress appear as a compact pill at the bottom that grows and shrinks like Dynamic Island, centred and above the floating dock, instead of toasts. It can unfold into a card with a title, a tag and a subtitle for sync, switching the source, list sync, saving and sign-in errors, and it replaces the old "Sync" pill at the top.

### Share card: "Now watching"

- A share bubble next to the "⋯" menu on a title opens a preview of a frosted-glass post frame you can send to friends: the logo, your nickname and the type, the cover with progress and your rating, a 10-segment progress bar, the current episode or chapter, a short text and a footer, on a soft backdrop that takes its colours from the cover. It follows the app theme and language and has "Save PNG" (Pictures/Vetro) and "Share".
- The text under the title is your own note, or the cleaned description of the title if there is none.
- The nickname is edited in its own sheet with the Letter Limit field (a ring, a counter, letters spilling out), so the keyboard never covers it, and it is remembered.
- Data level and the comment are round icon buttons with animated icons, like the notification bell: Full / Minimal data, and show / hide the comment.
- The preview is drawn live with springs. Switching Minimal / Full stretches the backdrop and the card smoothly with no empty space under the content: the episode line, the note and the genres grow and shrink, the text rises into place or settles away, and the rating and the progress percentage fade in. The saved picture is drawn by the same code.
- A comment of up to 52 characters (spaces and punctuation included) is drawn as a thought bubble above the card; turning it on or off stretches the backdrop and the bubble pops out of its tail like a small cloud.
- The comment is now saved per title. The title page shows it as a "My comment" section: tap it to edit, or use the trash button, confirm, and the section turns red and flies off the screen.

### Title page

- The description sits in a rounded card with a "Show more" button inside; the card grows with a spring.
- "Next episode in:" (when there is a schedule) and the "Where to watch" source icons are one block. The countdown switches to a compact form ("13h 36m 40s") instead of wrapping when the line is too narrow.

### Fixes

- Returning to Vetro from Recents no longer starts a full sync every time (the account session re-announced "signed in" on each token refresh).
- This build contains the model files of Title matching, Files and genres and Taste recommendations; an earlier Alpha shipped without them.

## v3.5.9-Alpha — November 22, 2026

App version: `v3.5.9-Alpha`. Android version code: `359`.
Changes since 3.5.8-Alpha. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### Share card: "Now watching"

- A new share bubble next to the "⋯" menu on a title opens a preview of a picture you can send to friends or post. It is drawn as a frosted-glass post frame: the logo, your nickname and the type of the title, the cover with the progress and your rating, a 10-segment progress bar, the current episode or chapter, a short text and a footer. A soft backdrop takes its colours from the cover.
- The card follows the app theme (light or dark) and the app language, not the system ones.
- The text under the title is your own note for the title; if there is none, it is the description of the title, cleaned of markup.
- The preview has a nickname field (remembered), a Full data / Minimal data switch, "Save PNG" (saved to Pictures/Vetro) and "Share".

### Card comment

- Another bubble next to Share (a speech bubble with a plus) adds a comment of up to 52 characters, spaces and punctuation included. It uses the Letter Limit field: a ring and a counter show what is left, and extra letters spill out under the field.
- The comment is drawn as a thought bubble above the card (light on dark theme, dark on light) and goes into the saved picture; the picture grows by the height of the bubble.

### Quiet reminders about unfinished titles

- A gentle notification when you left a title for 3 weeks or more after at least 2 episodes or chapters, and there is still something released to continue. Titles the app already shows as Completed or Dropped, titles with the bell switched off and titles in the secret folder are skipped.
- It has its own low-priority channel (no sound, no pop-up), comes at most once in 3 days between 10:00 and 21:00, never about the same title again within 45 days and at most twice for one pause. Tapping it opens the title. There is a switch in Settings.

### Mini notifications (experimental)

- The pill can now unfold into a card with a title, a tag and a subtitle for sync, switching the source, list sync, saving and sign-in errors (while the "Mini notifications" switch is on).

## v3.5.8-Alpha — November 21, 2026

App version: `v3.5.8-Alpha`. Android version code: `358`.
Changes since 3.5.7-Alpha. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### Mini notifications (experimental) fixed

- The pill is now centred: the icon and the text used to stick to its top edge and poke out of it.
- It sits higher, above the floating dock and the bottom buttons, instead of overlapping them.
- Copying a title in the edit screen now shows the pill ("Copied!") instead of the system toast.
- Sync progress now appears as the pill ("Syncing…", then "Synced") on the Home screen and replaces the old "Sync" pill at the top, while the "Mini notifications" switch in Settings, For developers is on.

## v3.5.7-Alpha — November 20, 2026

App version: `v3.5.7-Alpha`. Android version code: `357`.
Changes since 3.5.6-Alpha. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### Settings: hold for details works everywhere

- Holding a row in Settings now opens its details on every item, including the developer section and the smart models. Rows that had no written description get one built from their title and subtitle; the main ones (local AI, data collection, each developer switch) have full explanations with a Turn on / Open button.
- "What will be sent" is no longer a separate row. The exact text of the next upload now sits inside the details of "Help improve Vetro" (hold the row, then "Show the exact text of the next upload").

### Local AI is one item

- The three local models (title matching, files and genres, taste recommendations) are now a single "Local AI" row with one switch that turns all three on or off. The details explain what each of the three does.
- In the developer section the five separate AI rows (TitleMatch observations, MicroML packs and three "previous version" switches) are replaced by one "Local AI" row: its subtitle shows the state of the models, and tapping it opens a window with the "previous version" switches and the observation counters.

### Small fixes

- The bell on the title screen no longer shows "Notifications on / off": the colour and the icon are enough. The words stay only for screen readers.
- The letters in the new text fields drop in about a quarter more gently: smaller offset, tilt and bounce.

## v3.5.6-Alpha — November 20, 2026

App version: `v3.5.6-Alpha`. Android version code: `356`.
Changes since 3.5.5-Alpha. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### Notifications can be turned off per title

- A new bell button on the title screen, next to the trailer, switches notifications of that title off and on. The bell swings when you switch it on and goes quiet, crossed out, when you switch it off.
- A title with notifications off gives no push about new episodes or chapters, no card on the Home stack, no entry in the notification centre, and does not wait for dubbing. The release calendar still shows it. The setting stays on your phone and does not touch cloud sync. For series the episode counter keeps counting, so nothing floods in when you turn notifications back on.

### Text fields

- Every text field in the app got a new feel: each letter drops in with a bounce, deleted letters float away, the caret glides and stretches with its speed, the label rises above the text, and the frame thickens on focus. With the system "Remove animations" setting on, the letters simply appear.

### Mini notifications (experimental)

- New switch in Settings, For developers: "Mini notifications" (off by default). When on, short messages and the sync progress appear as a compact pill at the bottom of the screen that grows and shrinks like Dynamic Island, instead of system toasts.

## v3.5.5-Alpha — November 18, 2026

App version: `v3.5.5-Alpha`. Android version code: `355`.
Changes since 3.5.4-Alpha. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### Fix: the smart models are in the build again

- v3.5.4-Alpha was built without the model files of Title matching, Files and genres, and Taste recommendations, so their switches in Settings did nothing. This build contains them again.

### Smart models: stock images instead of covers

- "Files and genres" now also recognises the stock picture some sources serve instead of a cover (Shikimori: a girl with a raised hand and "404 not found" on her shirt). Such a picture is no longer saved as a cover, and the ones already in your collection are checked once: a stock picture is replaced by the cover from the Vetro catalogue, or removed so the usual cover search takes over. If the catalogue cannot be reached, nothing is changed. It works only while the "Files and genres" switch is on.
- The model is tiny (10 KB) and learned from this one picture; other stock pictures are not recognised yet.

### Search

- A film found under the TV Series tab is now labelled as a film, not as a series.

## v3.5.4-Alpha — November 18, 2026

App version: `v3.5.4-Alpha`. Android version code: `354`.
Changes since 3.5.3-Alpha. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### Search: films and series from the Vetro catalogue

- Search by direction now asks the Vetro catalogue first: Anime looks only in the anime database, TV Series in series and films (each result keeps its own type), so an anime query no longer returns books or series. Manga and Books work as before.

### First start: your collection meets the catalogue

- On the first launch of this version a short screen with the mascot appears while the app links the titles of your collection to the Vetro catalogue: first by the ids they already have (AniList, MAL, Shikimori, TMDB, IMDb), then by name. Where the catalogue has a cover that a title lacks, it is downloaded. If the network is down, nothing is marked and the screen simply tries again next launch. After 30 seconds you can continue in the background.
- Titles are linked once and stay linked; new ones are linked quietly when recommendations are refreshed.

### Recommendations

- The algorithm is the same, but candidates are now also checked against the catalogue: a title that is already in your collection under different ids or another name is no longer offered. Records of one title from different sources are merged by the catalogue id.

## v3.5.3-Alpha — November 16, 2026

App version: `v3.5.3-Alpha`. Android version code: `353`.
Changes since 3.5.2-Alpha. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### Player: "not dubbed yet" screen

- When an anime source stays silent twice in a row (the second "Source no longer responds"), the player first checks the episode. If it has fewer than 4 dubbing studios and came out no more than 2 days ago, you now see the mascot with "Looks like the studios haven't dubbed this episode yet" instead of the same error again.
- The screen has three buttons: a grey "Exit" capsule (opens the title), an orange "Settings" capsule (opens Settings on the new Availability threshold option) and a translucent retry bubble.

### Settings: Availability threshold

- A new switch, off by default. When it is on, every time a tracked title gets a new episode the app runs a quick audit: how many dubbing studios offer it and how long ago it came out. With fewer than 4 studios and at most 2 days since release there is no notification (and no card in the app). As soon as either condition stops holding, the notification arrives. While an episode waits, the audit repeats every 6 hours.

### Settings: explanations

- Press and hold a setting to read what it does, in a bottom sheet with a grey "Back" button and an orange button that replaces the control: "Turn on" / "Turn off" for switches, "Open" for everything else. Available for the main settings; the Smart models switches and the Developer section do not have descriptions yet.
- When you come from the player's "Settings" button, the page scrolls to the option and flashes it softly. The first time only, a pointer also shows how to hold a row, the description opens, and a hint explains the gesture.

## v3.5.2-Alpha — November 15, 2026

App version: `v3.5.2-Alpha`. Android version code: `352`.
Changes since 3.5.1-Alpha. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### Settings: Smart models

- A new section in Settings (no longer inside Developer) with one switch for each on-device model: title matching, files and genres, taste recommendations. All three are off until you turn them on; each switch also turns on everything that model can change (search order, genre aliases, recommendation order).
- "Help improve Vetro" is off by default and asks for your consent in a dialog with OK and Cancel before it turns on. When it is on, once a day the app sends anonymous numbers about how the models work, and the public facts (title, authors, narrators, cover link) of books that other book sites returned in a search but the catalogue does not have, so the catalogue can grow. What you type in a search and what is in your collection is never sent. "What will be sent" shows the exact text of the next upload.
- The option exists only in builds signed by the developer.

## v3.5.1-Alpha — November 14, 2026

App version: `v3.5.1-Alpha`. Android version code: `351`.
Changes since 3.5.0-Alpha (v3.5.0 shipped without the on-device models by mistake). This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### On-device models (MicroML)

- Three small model packs now ship inside the app and run on the phone: Identity (title matching across anime, films, series, manga and audiobooks), Catalog (file-name parsing for audiobook folders, person names, title language, genre aliases) and Experience (genre associations and a ranker that learns from your own ratings and picks).
- They start in observation mode: the app compares what a model would do with what it does today and changes nothing. Settings → Developer has a switch and a rollback for each pack; the effects you can turn on are search order, exact genre aliases and recommendation ranking.
- Nothing is sent anywhere. The optional, off-by-default contribution to model training exists only in builds that have a collector address configured, and this build has none.

### Catalogue

- The catalogue grew: more descriptions, genres, series and covers (about 1,000 new covers), and the on-phone search index is refreshed to version 3.
- Install tokens are refused for devices whose attestation certificates Google has revoked.

## v3.5.0-Alpha — November 14, 2026

App version: `v3.5.0-Alpha`. Android version code: `350`.
Changes since 3.4.9. This is a pre-release: the in-app updater ignores Alpha tags, install it by hand.

### Audiobooks: the book catalogue

- A new first source for audiobooks: a catalogue of about 176,000 books. It keeps a search index on the phone, so lookups work offline and instantly, and asks the server only when the local index is not enough.
- The book page and the library show the catalogue details: description, genres, series, the original title and the rating. Covers come from the same catalogue.
- If the index cannot be refreshed, the reason is written to the log instead of being swallowed.

### Catalogue access

- The app now proves to the catalogue server that it is the real Vetro (Android Key Attestation, no Google services needed) and gets an install token for 30 days. Phones without hardware attestation, and builds the server does not know, keep working with the sources used before; nothing is lost.
- The token is sent only to the catalogue host, never to other sites.

## v3.4.9-Stable — November 7, 2026

App version: `v3.4.9-Stable`. Android version code: `349`.
Changes since 3.4.8.

### Manga: Mihon extensions inside Vetro

- The "Mihon extensions" button no longer sends you to GitHub. It opens a sheet with the Keiyoushi catalogue: one button installs the recommended sources for your app language (13 Russian or 9 English general catalogues), the rest of the catalogue is searchable, and installed extensions can be updated or removed. NSFW extensions are filtered out.
- Nothing is installed on the system. An extension is downloaded into the app's own storage, checked (package name, extension library 1.6 and the Keiyoushi signing certificate), made read-only and loaded by Vetro. Its sources are searched together with the built-in ones, and the source picker searches again right after an install.
- An extension that turns out to be incompatible fails its own search instead of closing the app.
- Remanga works again: it moved to its v2 API after v1 started answering 404, so it had silently disappeared from catalogue search and from the reader's source picker. Chapter dates are read correctly again.
- The built-in InkStory source is removed: the site no longer has a stable API. InkStory is still available as a Mihon extension.

### Audiobooks: books as 3D models

- Books on the shelves and on the home screen are drawn as small 3D models: a real binding with light, spine and page edges, the cover laid on the front board, and the back cover tinted from the cover's own colour. Books without a cover get a placeholder with the title.
- The edge of the model is smooth instead of stair-stepped, and the large books on the shelf use a sharper texture than the small ones on the home screen.
- A book added from a folder on your device now opens the same details page as any other book: facts, description and cover from the library record, chapters and their lengths from the files.
- The dark rim around the mini player capsule is gone.

### Release calendar: a week view

- Tap a day to fold the month into that day's week; the freed space fills with the same release cards as on the home screen. A Month/Week switch appears with it, arrows page by week, and the handle under the week pulls the month back open.

### Your collection

- A title you add without any progress now reads as **Not Started** instead of Completed. Titles that were already in your collection keep their status, and a status you set yourself always wins.
- Cover cards on the home screen get a light haze and dark text in the light theme instead of the dark shading meant for the dark theme.
- Empty screens have a small animated mascot: on the first run, in an empty folder or search, in notifications, the release calendar, the secret folder, books and manga. It holds still when the system asks for reduced motion.
- Search by frame is redrawn in the app's own style: a warm gradient, a frame zone with an orange "Choose a frame" button, and mascot scenes while it loads or when it fails.

### Fixes

- Returning to Vetro from Recents no longer flashes the update sheet for a moment.
- The new navigation and the swipe-back animation are now the only option; their developer switches are gone.

### Under the hood

- Kotlin 2.4, OkHttp 5, kotlinx-serialization 1.11 and kotlinx-coroutines 1.11, the versions Mihon extensions are built against.

## v3.4.8-Beta — November 3, 2026

App version: `v3.4.8-Beta`. Android version code: `348`.
Changes since 3.4.7.

### Audiobooks: a live mini player

- The mini player is now a small capsule, two cells wide and one tall on the widget grid: cover, a wave and play/pause. It sits above the dock only while a dock is on screen. To close it, flick it off the bottom edge of the screen, like the YouTube mini player.
- The wave is alive: it follows the sound of the book itself. The microphone is not used and no permission is asked. The loudness (RMS and peak) of the audio that the player sends to the output is measured about every 20 ms, joined into a smooth curve, and the seven bars read that curve 35 ms apart, so the picture runs together with the narrator's voice. Bars rise in about 12 ms and fall in about 90 ms, the same on 60 and 120 Hz screens, and they settle when you pause.
- The wave keeps time between the player's position reports, which arrive about once a second, so it no longer freezes and jumps.

### Audiobooks: playback that recovers

- If the sound disappears because the output died (a Bluetooth hiccup, for example), the player now notices after 2.5 seconds without audio and recreates the output where it left off. The output buffer grew from 0.5 to 2 seconds, so short stalls no longer empty it.
- A streamed book that goes silent without an error is reopened: after 12 seconds of buffering (5 seconds when it claims to be ready) the player steps 3 seconds back and asks the source again. Torrent sources re-request the missing pieces every 3 seconds, and a silent peer is dropped after 10 seconds instead of 50.

### Audiobooks: the book page

- The book page has the same "..." menu as a title page: mark as listened, change the cover, edit author and narrator, "wrong source".
- Author and narrator can be edited, with a swap button. The names are saved to the work, so the book moves to the right shelf, and they are remembered when you reopen the book at its source.
- The cover picker offers matches by title, other works by the same author, results for your own query and a picture from the gallery.
- With your own AI key connected, a Russian title and author are translated into the English title and Latin names for the cover search, and the AI picks the right work among the author's works. Nothing is sent without a key.

### A new startup animation

- "Vetro" collapses into the app's mark: the right leg of the V closes like scissors, the letters e, t and r are pulled into the o, and the o flies to its place on an arc and fills with colour with a glow and ripples. It plays in full on the first launch after you install or update, and about twice as fast on every launch after that.
- The old animation is still there: Settings → developer options → "Legacy startup splash" (takes effect on the next launch).

### Video player

- "Retry" after the connection was lost now really retries. Before, sources that had failed stayed marked as dead until you reopened the episode, so Retry showed the same message again. Now Retry clears those marks, searches again without excluding anything, and reloads the current link if nothing else is found. When the internet comes back while the message is on screen, the retry starts by itself.
- Fill-to-screen is kept when the player switches to another source.
- Switching sources on a stall is calmer: the watchdog waits 20 seconds at the start of a source and 12 seconds during playback, does nothing while the phone is offline, and a switch it makes no longer removes the source from the dub list (it only leaves the fallback pool for that episode).

### Title page

- Hold an episode or a season to lift it with round glass buttons: rate, not watched, watched. Season actions apply to every episode in it. The old hold-and-drag rating gesture is gone.
- The audio button in the player opens its sheet even when there is one track or none.

### Statistics

- The three-card deck is replaced with ordinary tiles, like the insights below them. Tap a tile to flip it to a back with the description of the metric and the AI comment. Charts, bars, the heatmap and rings grow in when the tile first comes into view.

### Under the hood

- A small on-device model for matching titles across catalogues ships in the app. For now it runs in the background in a shadow mode and does not change search or enrichment results.

### Roadmap

- **Vetro API.** We are planning an API of our own, so that the app asks one service for its data instead of many sites. It starts with books, then movies, anime, series and manga follow. Nothing changes in this build; sources you connect yourself keep working as they do now.

## v3.4.7-Beta — October 27, 2026

App version: `v3.4.7-Beta`. Android version code: `347`.
Changes since 3.4.6.

### Recommendations: rate with a swipe

- Swipe a recommendation card up to approve it (it turns green on the way) or down to reject it (it turns red). The verdict is saved as the card leaves the deck, an Undo button brings the last card back, and the ratings stay on the device.
- Your verdicts now shape the picks: genres of approved cards count for you, genres of rejected ones count against you (the effect fades over about a year), a title you have already judged never comes back, and a title whose picks keep getting rejected loses weight as a source of new ones.

### Movies and series in recommendations

- Recommendations now include movies and series, not only anime. The mix follows your collection: an anime-only library gets anime picks, a mixed one gets both, interleaved in the deck, and each card carries a small type label. Movies and series come from TMDB.
- Movies and series in your collection that have no TMDB id now get one automatically. The title is searched by name, year and type, and the match is checked: the name must match, the year may differ by at most one, and when two candidates fit equally (same-name remakes) nothing is guessed. Titles that do not match are not searched again for two weeks.
- Ranking was reworked: scores are normalized, every pick is weighed by how strongly it is tied to the titles you rated highly, and the deck is built so it is not six near-copies of one show. The random shuffle between sessions is much smaller, so the picks are steadier.
- Two more sources, Trakt and TasteDive, are built in but switched off until they are verified on real collections.

### Fewer repeats in recommendations

- Anime you already have are no longer offered under another name: sequels, later seasons, movies, spin-offs and other adaptations of the same story are filtered out through the AniList relations (for example, season 2 of a show whose first season is in your collection). On a test collection none of 40 anime picks was already covered by a franchise you own; before the change about a quarter were.
- Titles are also compared without the format, year or season marker, so "Hunter x Hunter (2011)" is recognised as "Hunter x Hunter".
- A network failure never empties the deck: if the relations cannot be loaded, the picks are shown unfiltered.

### Movies and series: the source is picked for you

- Opening a movie or an episode no longer asks you to choose from a list of torrent releases. The best-ranked source is used and you are asked only for the quality, the same as for anime. Downloads use the same flow.
- The list of sources and audio is still there when you want to change it, behind the quality button. It is now titled "Source and audio"; it used to say "Watch", even for downloads.

### Player

- Playback speed in the local player is now a ruler sheet like in the audiobook player, and the sheet stays open while you drag.

## v3.4.6-Stable — October 25, 2026

App version: `v3.4.6-Stable`. Android version code: `346`.
Changes since 3.4.5.

### New episodes, checked hourly

- Titles that are airing right now and your favorites get their own check every hour, separate from the full collection check that runs every few hours. A push about a new episode now arrives soon after it comes out, not at the next big pass.
- The hourly pass touches only those titles and keeps the "airing now" progress of all the others intact.

### Seasons, countdown and calendar

- A season that AniList has only announced is no longer shown as a real one. "The Apothecary Diaries" showed a fourth season that does not exist yet (it is "Season 3 Part 2", not released); because of it the title had no next-episode countdown and was missing from the release calendar. Both are back, and announced or cancelled entries stay out of the season list.
- The airing season is now picked as the last season that is actually airing, so one extra row after it can no longer hide the countdown.

### Audiobook covers and English titles

- More places to find a cover when the book site has none: the same book on other sites, Apple's audiobook catalogue (square covers up to 600×600), Open Library and Google Books. Covers are checked against the title (and the author when the alphabets allow it), so a wrong cover is better skipped than shown.
- Alternative English titles for Russian books: Wikidata, then Open Library, then your AI provider if one is connected. A Russian title is also searched under its English name in the catalogues and on English sites.
- Books in your library that have no cover look for one on their own; a book page without a cover does the same. "Refresh cover" now tries all of these sources.

### Faster polling of sources

- The search trick (sources in parallel, a hard limit each, the answer as soon as there is something to show) now applies to the other places that ask many sources at once.
- Playback: once a source returns a playable video, the others get a few more seconds instead of the full wait (up to 24 seconds for the slowest). Sources that were let go early are not marked as failed.
- "Find more" for seasons, manga search and audiobook search stop waiting for the slowest source after the first answers arrive.
- Cover search runs its branches in parallel and stops at the first working cover; found lists are cached for 15 minutes.

### Settings and fixes

- The "Collection Enrichment" item is gone from Settings. Background enrichment is always on (every 6 hours and when you add a title); the old switch no longer exists, and anyone who had switched it off gets it back on.
- The "Source is no longer responding" panel in the player now has rounded corners like the rest of the interface.
- The audio (dub) menu in the player scrolls again: it was swallowing swipes. While the menu is open, swipes no longer seek or change brightness underneath it.

## v3.4.5-Stable — October 24, 2026

App version: `v3.4.5-Stable`. Android version code: `345`.
Changes since 3.4.4.

### Secret folder

- A new folder to the left of Home. Swipe right on the Home page: the edge stretches like rubber and resists more the further you pull. Let go early and it springs back; pull almost to the far edge and it breaks free with a haptic click and opens full screen.
- The gesture adapts to where your finger starts. The barrier breaks at 85% of the distance your finger can still travel, and a start too close to the right edge does not trigger it at all, so a short accidental swipe cannot open the folder.
- Every opening starts with a bolt screen that asks for your fingerprint or face through Android's system prompt, the same as the app lock. Content is not even loaded until the bolt is open.
- After the bolt: the folder itself, in an orange-and-mono palette that differs from the main screen, or a short animated empty state when nothing has been added yet.
- Add a title from the "⋯" menu in Details (manga included); the same item returns it to the collection. Secret titles disappear from the main feed, statistics, the new-episode tray and push notifications.
- The folder closes with the Back gesture, the close button, a swipe left, or when the app goes to the background, and asks for biometrics again next time.

### Faster search

- Anime and manga sources are queried at the same time instead of one after another, each with a 5-second limit. Search time is now the time of the slowest source, not the sum of all of them, and one stuck source no longer holds up the results.
- Results appear as soon as the first source answers and fill in as the others arrive.
- Finished searches are cached for 10 minutes, so repeating or retyping a query answers instantly. Incomplete results (a source timed out) are not cached.
- The pause before a search starts is shorter (300 ms instead of 400 ms). The Russian "via the English title" fallback now runs its three requests in parallel.

### Redesigned settings panels

- Account settings, AI Connect and Collection Enrichment now use the same card layout as Additional sources: a header with "Done", rounded cards with a tinted icon tile, a title, a description and an accent status.
- Input fields are pill-shaped with a round icon tile and a raised eye button for showing the key or password. Secondary buttons are raised pills with an icon instead of bare text.
- Account settings is split into cards: cloud sync, sign-in methods with one card per provider, and the email password.
- "Get" in AI Connect now opens the OpenRouter key page instead of OpenAI's.

### Fixes and defaults

- Details: pulling the sheet down now stops at the bottom edge of the cover instead of exposing a black gap.
- Search: after adding a title and then deleting it from the collection, the result no longer keeps the "Added" button. The added state now follows the real collection, including titles hidden by the current filters.
- The favorites strip (formerly "Experimental grid") is now on by default. Turn it off in Developer Settings if you prefer the old layout.
- Bottom sheets: the thin light rim is brighter and wider, with a highlight on the top corners.

## v3.4.4-Stable — October 23, 2026

App version: `v3.4.4-Stable`. Android version code: `344`.
Changes since 3.4.3.

### Title ratings from episode scores

- Added a star button next to the watch status in Details. Choose **Auto rating** or **Manual rating**.
- **Auto rating** keeps the existing behavior and uses your title rating.
- **Manual rating** calculates the arithmetic mean of all rated episodes across seasons. Unrated episodes and removed scores of 0 are excluded. For example, scores of 8, 9 and 10 produce a title rating of 9.0.
- The calculated rating appears in Details and on the collection card, and is used when sorting by rating.
- Manual mode requires at least one rated episode. Otherwise, the app explains why it cannot be enabled.
- Changing an episode score updates the title rating. Removing the final episode score returns the title to Auto and displays a notice.
- While Manual mode is active, the title rating cannot be edited directly from its card. Edit the episode scores or switch back to Auto in Details.
- Switching back to Auto restores the original title rating; the episode average does not overwrite it.

### Watch start and finish dates

- Added a clock button next to the rating controls for automatic or manual watch dates.
- Auto records the start date when you first open the first episode. It records the finish date when you open the final episode, provided the total episode count is known and the title has finished airing.
- Opening the final episode marks the title as **Completed**. Reopening episodes does not overwrite saved dates.
- For ongoing titles or titles with an unknown episode count, the latest available episode is not treated as the final episode.
- Manual dates are selected in a calendar inside the bottom sheet. You can save a start date alone, add a finish date later, or remove the finish date.
- A finish date cannot be earlier than the start date. Saving a manual finish date also marks the title as Completed.
- Saved dates appear on the button in Details. Automatic and manual dates are stored separately, so you can switch between modes.
- The watch-date calendar is separate from the episode release calendar.

### Fingerprint and face app lock

- Added an optional app lock in Settings using Android's system biometric prompt.
- Fingerprint or face authentication is available according to your device's hardware and enrolled biometrics.
- When enabled, authentication is requested at launch and when returning from the background. Navigating between screens does not require repeated authentication.
- Direct entry into a player or reader is protected too: content stays behind the lock screen until authentication succeeds.
- Enabling or disabling the lock requires biometric confirmation. If biometrics are unavailable or not enrolled, the app explains what to do.
- App lock is off by default and applies only to the current device.

### Experimental favorites strip

- Added **Experimental grid** in Developer Settings, off by default.
- When enabled, favorite titles appear in a horizontal strip above the rest of the collection.
- The strip has a localized **Favorite** heading with a theme-appropriate text color and a title count.
- Cards use full-cover artwork and show their position, media type, title, available year and genre, and rating.
- Favorites are not duplicated in the regular list. The strip follows the current search, filters and sort order.
- Opening Details preserves the cover transition. Long-press actions remain available: toggle favorite, delete, edit and open Details.
- The action menu fits narrow portrait cards, keeping all four buttons within the screen.
- No empty section is shown when the current selection has no favorites. Turning the setting off restores the previous collection layout.

### Audio selection in the player

- Replaced the audio-selection wheel with a bottom sheet listing the available options.
- Open it from the player's audio button; the current selection is highlighted.
- The new selector is available in both the streaming player and the downloaded-episode player.

### Bottom sheets and frosted glass

- Watch status, rating mode and watch-date mode in Details now use the shared iOS-style bottom sheet.
- The manual watch-date calendar opens inside the same sheet.
- Restored the background recession effect when opening sheets in Extera back mode.
- Exposed margins continue the current page's edge colors, including cover artwork and backgrounds after scrolling, while preserving the scale animation.
- Margin rendering reuses the page background instead of recording the whole screen with its glass controls again.
- Fixed frosted glass on the top dock. Its live backdrop refreshes after the dock finishes appearing and after the card menu closes.
- Checked sheet margins in both light and dark themes.

### Season and volume animations

- Added a reveal animation for section rows, including the final season or volume at the bottom of the list.
- Opening the final section makes room for its first rows above the bottom dock.
- Removed competing row-movement animations and the reset to zero when reopening a section.
- Closing now plays the reverse animation. Rows remain in the list until it finishes, then disappear.
- Quickly reopening a section continues from its current animation position.
- Long sections render rows as needed. Tested with a 500-chapter volume, including access to its final chapter.

### Data and defaults

- Rating modes and watch dates are saved locally. Existing synchronization of individual episode scores is unchanged.
- Automatic dates do not replace manual dates, and episode averages do not overwrite the original title rating.
- App lock and the experimental grid are separate opt-in settings, both off by default.
