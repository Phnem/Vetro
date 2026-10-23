# Vetro changelog

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
