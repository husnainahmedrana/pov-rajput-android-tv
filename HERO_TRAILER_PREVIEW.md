# Trailers and TV home Continue Watching

## Trailers (TV)

- On by default. All trailer settings are on one page: Settings → Home & appearance → Trailers.
- Trailers play on details pages and when a Home shelf poster has focus. The rotating top
  recommendations show backdrop images only.
- Trailer sound is on by default (Trailer sound on the Trailers page).
- Fill trailer screen (on by default) zooms wide trailers to trim black bars; turn it off for
  the original framing. Video is capped at 720p.
- High quality trailers (on by default) uses VP9 video when the TV has a hardware VP9 decoder,
  which looks cleaner than AVC at the same 720p size. Turn it off, or on TVs without hardware
  VP9, trailers use AVC.
- Artwork stays visible until the first video frame. If a trailer is unavailable, restricted or
  rate-limited, the artwork is kept.
- Changing selection, opening menus, leaving a page or backgrounding the app stops the trailer.
  Normal playback always has priority.
- Trailer IDs come from Cinemeta. Video is fetched from public YouTube without accounts, cookies
  or hardcoded API keys. YouTube may change its service at any time, so trailers can stop working
  until the app is updated.

## TV home Continue Watching

Settings → Home & appearance → TV home Continue Watching publishes the app's own channel and
Watch Next cards on Android TV launchers. Only artwork and an identity-only resume link are sent
to the launcher; API keys, stream links and headers never are. Cards follow the selected
tracker's Continue Watching list, or local progress without a tracker, and refresh about every
15 minutes.

The launcher decides whether and where the row appears. Google TV's own Continue Watching row is
limited to certified partner apps.

## Source acknowledgment

The native trailer approach was informed by
[NuvioTV by NuvioMedia](https://github.com/NuvioMedia/NuvioTV) (GPL-3.0), particularly
`data/trailer/InAppYouTubeExtractor.kt` and `data/trailer/YoutubeChunkedDataSourceFactory.kt`.
POV's existing author credits and GPL-3.0 license remain intact.
