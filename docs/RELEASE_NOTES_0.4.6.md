# Nabrio 0.4.6

**Subtitles from your add-ons**, **self-hosted add-ons on your own network**, **metadata in your language** (Arabic and 23 others) and **Match frame rate** for smooth films on any TV.

## New

- **Subtitles from your add-ons:** add-ons you installed that offer subtitles are now asked for them, next to the built-in OpenSubtitles search.
  - Each add-on adds its best two matches, named after it (for example *Arabic · My Subs*), in Audio & Subtitles.
  - Subtitles an add-on sends **with a link** are used too; they were made for that exact file, so they come first.
  - When the video already has your language built in, add-on subtitles are still offered as extra choices without being switched on.
  - A subtitles-only add-on added under Source providers no longer shows as *unavailable* when links are searched.
- **Add-ons on your local network:** Add source provider (and catalog add-ons) now accept plain `http://` addresses on your own network: `192.168.x.x`, `10.x.x.x`, `172.16–31.x.x`, `localhost`, `.local` names and Tailscale `100.x` addresses, with or without `/manifest.json`. Public add-ons still need HTTPS.
- **Metadata language** (Settings → Home, under TMDB): titles, descriptions and Home rows from TMDB in **Arabic** or 23 other languages, or the same as the app's language.
  - Details pages use TMDB's own translation; where there is none, English stays.
  - The Latest Releases rows are translated title by title (brand-new releases often have no translation yet).
  - Cast and crew always stay in English, so names match posters, links and IMDb.
  - Episode names and synopses stay as Cinemeta / TVDB give them.
- **Match frame rate** (Settings → Playback → Player): **Always** switches the TV to the video's frame rate (23.976, 24, 25, 50…) so films play without judder, and back when the player closes. The screen may go black for a moment when playback starts. **Only when seamless** (default) and **Off** keep the old behaviour.

## Better

- New settings and messages are translated into Spanish, French, German, Italian, Portuguese and Dutch.
