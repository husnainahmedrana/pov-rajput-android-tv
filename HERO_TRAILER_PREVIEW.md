# Native hero trailer preview

Local experiment based on v0.3.17-rajput. Not published.

- Separate package: `dev.pov.tv.rajput.trailerpreview` (normal app settings remain separate).
- TV only, opt-in under Settings → Home & appearance → Trailers (preview).
- The top Home recommendations rotate still backdrops only. Focusing a Home shelf poster plays its trailer; details pages also play trailers. Native AVC video is capped at 720p.
- Trailer sound is on by default. Settings → Home & appearance → Trailer sound turns it off/on.
- AAC audio is merged with native video when sound is on; muted previews do not fetch a separate audio track.
- Audible previews request media audio focus and release it when stopped.
- Title, synopsis, gradients, shelves and remote navigation remain native app views above video.
- On details pages, app overlays and gradients fade after two idle seconds of actual trailer playback.
  Any remote key reveals details; that first key gesture only reveals them. After two idle seconds
  they fade again. Holding a button keeps details visible until release. Home does not auto-hide.
- Trailer video keeps its aspect ratio. Fill trailer screen is on by default and adds 35% zoom to trim common cinematic black bars; picture edges are cropped, never stretched. Turn it off for the original framing.
- Artwork remains visible until the first decoded video frame; unavailable trailers keep artwork.
- Changing selection, opening menus, leaving a title page or backgrounding the app releases the player.
- Normal playback has priority over hero trailers. No account credentials are used by trailer lookup.
- Public trailer metadata comes from Cinemeta. Public YouTube media availability is not guaranteed;
  restricted videos, failed extraction and rate limits fall back to the artwork.
- URLs are held only in a small memory cache; no hardcoded YouTube API key or user cookies.

## Source acknowledgment

The native trailer approach and public Android player profile were informed by
[NuvioTV by NuvioMedia](https://github.com/NuvioMedia/NuvioTV), licensed under GPL-3.0,
particularly `data/trailer/InAppYouTubeExtractor.kt` and
`data/trailer/YoutubeChunkedDataSourceFactory.kt` on its `dev` branch.
The local range reader was implemented with explicit file-length and seek boundaries.
POV's existing author credits and GPL-3.0 license remain intact.

## Local build

Use the normal Android SDK/JDK setup and:

```
gradle :app:assembleTvDebug -PpovAppIdSuffix=.trailerpreview "-PpovAppLabel=POV Rajput Trailer Preview"
```

Diagnostic instrumentation is opt-in with
`-PpovTestRunner=dev.pov.tv.HeroTrailerInstrumentation`.
Home uses the normal catalog shelves; the diagnostic examples shelf has been removed.

## Verification

TV preview APK and instrumentation APK built; lint passed. Twenty-four focused unit tests passed, including launcher reconciliation, resume-link validation and playback-history regression tests.
Live tests on the bedroom Android TV verified decoded first frames for Inception and Breaking Bad,
D-pad navigation, stopping the old trailer when selecting another title, stopping on page exit
and backgrounding, missing-trailer artwork fallback, and disabling the preview toggle. The expanded TV test also verified trailer video/audio on movie
details, muted series details, audio rendering on Home, the sound-on default, and changing the
Trailer sound switch off and back on through the actual Settings UI.

The idle-overlay TV audit verified hidden chrome after two seconds, revealing with OK without
starting the movie, and hiding again after the next idle pause. TMDB cast lookup was also verified
with the configured device key; no key was copied to logs or source. Automated public-preview
mode skips saved API keys; start the app normally to use configured metadata providers.

## TV home Continue Watching

Settings → Home & appearance → TV home Continue Watching publishes this app's own preview channel
and Watch Next cards on supported Android TV launchers. Artwork and identity-only resume intents
are published; API keys, stream links and headers are never sent to the TV provider.
Cards use the selected tracker's cached Continue Watching list, or local progress without a tracker.
Current local playback can update a cached tracker entry before its next remote refresh. Completed,
removed and launcher-dismissed cards are reconciled. Deliberate new playback can restore a dismissed
card. Only this app's own rows are changed. A persisted Android JobScheduler task refreshes progress
about every 15 minutes when the network is available. Switching the setting off clears this app's
published cards and cancels the periodic job.

Google TV's stock Continue Watching surface requires a certified integration. Standard Android TV
channels/Watch Next data can be published successfully without being displayed by that launcher.
The app cannot force visibility or placement. Official documentation:
https://developer.android.com/training/tv/get-started/google-tv
https://developer.android.com/training/tv/discovery/recommendations-channel

Live launcher verification on the bedroom TV: nine real Simkl Continue Watching entries were
published to the app channel and Watch Next, with valid identity-only resume intents. The user
confirmed that the app channel is visible on this TV. Placement is controlled by the launcher.

The final live TV audit passed for focused Home-poster video/audio, suppressing trailers when
focus returns to the rotating top recommendations, details trailers, idle overlay fade/reveal,
Fill/original framing, and nine synced launcher items. Fill framing is retained as the default.

Home trailer framing now uses a 16:9 viewport anchored to the upper-right, above the shelf line.
Its left and bottom edges are alpha-feathered into the artwork instead of drawing a boxed player.
Details pages retain their existing full-backdrop framing. The live TV audit verified the corner
geometry, native video/audio, details fade/reveal, and nine synced launcher cards; lint passed.

The trailer feature is currently enabled only for TV layouts. Native YouTube URL extraction is not
an approved embedded-player integration. GPL permissions for the app's source do not grant rights
to YouTube content or establish compliance with YouTube's service terms; public release should
revisit an authorized trailer source or supported playback method.
