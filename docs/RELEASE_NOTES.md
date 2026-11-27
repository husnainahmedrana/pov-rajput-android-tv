# Nabrio 0.4.18

**New: trailers in your language.** Go to **Settings → Trailers → Trailer language** and pick yours. The trailers on title pages and Home then play in that language wherever one exists.

## New

- **Trailer language** (Settings → Trailers): Español (España), Español (Latinoamérica), Français, Deutsch, Italiano, Nederlands, Português (Brasil), Português (Portugal), हिन्दी and اردو. Automatic keeps the English trailers as before.
  - With a TMDB key, the trailers come from TMDB, which labels each video's language and country. A studio's own trailer starts at once.
  - Without a key, Nabrio searches YouTube. It puts the country's own studio channels first (for Castilian, Spain's channels and never Latin American ones), and it skips subtitled copies, reactions and other titles' trailers.
  - When a trailer has several audio tracks, the one in your language plays. YouTube sometimes adds an automatic English dub to a foreign trailer and makes it the default.
  - When no trailer exists in your language, for example for a film released only with subtitles, the English one plays.
- **mpv as a second player engine** (Settings → Playback → Player → Player engine). **Auto**, the default, plays in the main player and switches to mpv by itself when a file's format or container isn't supported. You can also choose the main player only, or mpv always. mpv runs inside Nabrio's own player screen, with the same controls, audio and subtitle choices, subtitle style and sync, volume boost, zoom, frame-rate matching and seek previews.
- **Start over** in the hold menu of titles you've started, and on the title page.
- **NZB prefetch:** while the link list is open, Nabrio downloads the NZB of the first Usenet link, so pressing play starts 1–2 seconds sooner. It counts as one grab at the indexer. It's on by default; turn it off in Settings → Sources → Usenet. It's skipped for indexers with a small daily grab limit.
- **Usenet auto-next:** when a release has been taken down, or a set can't be streamed, Nabrio says so and tries the next link by itself.

## Changed

- **Faster Usenet start, resume and seek**, especially on slower lines and far-away servers:
  - The first articles are asked for ahead of everything else on each connection.
  - When you resume or seek, the full read-ahead starts at once. Seek previews read only what they need, so they no longer hold up the video.
  - Heavy files (above about 12 Mbit/s, like 4K) start once a few seconds are buffered. Lighter files wait for a slightly fuller buffer, so they don't stall in the first seconds.
  - With a backup news provider, an article that's slow to arrive is also asked for from the backup, and whichever comes first is used.
- **Faster seek previews.** Keyframes are read four at a time, and 10-bit videos now get previews too (through mpv). A lighter 8-bit copy is still preferred when one exists.
- **Cast without a TMDB key** in the player's Info panel, from TVmaze for shows and Wikidata for films, as on title pages.
- Video labelled above the TV's limits now plays when the TV's hardware decoder can handle its size and frame rate.

## Fixed

- RAR sets whose video file has no video extension now play.
- Settings pages with only a few tiles, like Trailers, no longer stretch the tiles to the bottom of the screen.
- Accounts at their news provider's connection limit are no longer refused a connection right after a seek.
