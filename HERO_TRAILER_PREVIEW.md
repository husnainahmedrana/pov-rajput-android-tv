# Native hero trailer preview

Local experiment based on v0.3.17-rajput. Not published.

- Separate package: `dev.pov.tv.rajput.trailerpreview` (normal app settings remain separate).
- TV only, opt-in under Settings → Home & appearance → Hero trailers (preview).
- Muted AVC video up to 720p replaces the hero backdrop after a short selection delay.
- Title, synopsis, gradients, shelves and remote navigation remain native app views above video.
- Artwork remains visible until the first decoded video frame; unavailable trailers keep artwork.
- Changing selection, opening menus, leaving Home or backgrounding the app releases the player.
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

TV preview APK and instrumentation APK built; lint passed. Seven focused unit tests passed.
Live tests on the bedroom Android TV verified decoded first frames for Inception and Breaking Bad,
D-pad navigation, stopping the old trailer when selecting another title, stopping on page exit
and backgrounding, missing-trailer artwork fallback, and disabling the preview toggle.
