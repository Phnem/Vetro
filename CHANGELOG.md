# Vetro changelog

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
