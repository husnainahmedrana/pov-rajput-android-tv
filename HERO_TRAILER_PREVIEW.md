# Native hero trailer preview

Local experiment based on v0.3.17-rajput. Not published.

- Separate package: `dev.pov.tv.rajput.trailerpreview` (normal app settings remain separate).
- TV only, opt-in under Settings → Home & appearance → Hero trailers (preview).
- AVC video up to 720p replaces the Home or details backdrop after a short selection delay.
- Trailer sound is on by default. Settings → Home & appearance → Trailer sound turns it off/on.
- AAC audio is merged with native video when sound is on; muted previews do not fetch a separate audio track.
- Audible previews request media audio focus and release it when stopped.
- Title, synopsis, gradients, shelves and remote navigation remain native app views above video.
- On details pages, app overlays and gradients fade after two idle seconds of actual trailer playback.
  Any remote key reveals details; that first key gesture only reveals them. After two idle seconds
  they fade again. Holding a button keeps details visible until release. Home does not auto-hide.
- Trailer video keeps its aspect ratio. Cinema-format or encoded black bars may remain.
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
The temporary examples shelf is debug-only and appears when preview trailers are enabled.

## Verification

TV preview APK and instrumentation APK built; lint passed. Thirteen focused unit tests passed.
Live tests on the bedroom Android TV verified decoded first frames for Inception and Breaking Bad,
D-pad navigation, stopping the old trailer when selecting another title, stopping on page exit
and backgrounding, missing-trailer artwork fallback, and disabling the preview toggle. The expanded TV test also verified trailer video/audio on movie
details, muted series details, audio rendering on Home, the sound-on default, and changing the
Trailer sound switch off and back on through the actual Settings UI.

The idle-overlay TV audit verified hidden chrome after two seconds, revealing with OK without
starting the movie, and hiding again after the next idle pause. TMDB cast lookup was also verified
with the configured device key; no key was copied to logs or source. Automated public-preview
mode skips saved API keys; start the app normally to use configured metadata providers.
