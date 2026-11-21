# Nabrio 0.4.7

**Play in Kodi, VLC or any other player**, **audio passthrough control**, **Dolby Vision Profile 7 on more TVs**, **Usenet through TorBox or Deepbrid**, **built-in seek previews** and **no more buffering on big debrid files**.

## New

- **Play videos with another app** (Settings → Playback → Play videos with): the built-in player, **Ask each time**, or any player installed on the device: Kodi, Dune HD Media Center, VLC, Just Player, MX Player…
  - Every link works, Usenet included: Nabrio serves the video to the other app from the device itself.
  - **Continue Watching and your tracker (WeTrakr/Simkl) keep your progress**, and the other app opens where you left off (Kodi and VLC included).
  - **Stop in Kodi** brings you back to Nabrio's link list. Allow Nabrio to *Display over other apps* (Android settings → Apps → Special app access) to keep Kodi running in the background; without it Nabrio closes Kodi to come back.
  - A full-screen card shows the title's logo while the link opens, with Back to cancel.
- **Audio passthrough** (Settings → Audio): **Automatic** (as before), **Decode lossless** (TrueHD and DTS-HD are decoded in the app, for sound bars that play them as static) or **Decode everything**.
  - If your TV's own *Surround sound* setting is **Never**, Android blocks passthrough for every app; the setting now tells you so and where to change it.
- **Dolby Vision Profile 7 → 8.1:** on a Dolby Vision screen whose decoder only takes Profile 8 (many Android TV boxes), Profile 7 films (UHD Blu-ray remuxes) are converted on the fly and play in Dolby Vision instead of falling back.
- **Usenet through TorBox or Deepbrid** (Settings → Usenet → Play Usenet links with):
  - Nabrio gets the NZB from your indexer **with your own Usenet headers** and sends the file, never the indexer link.
  - TorBox Pro: links TorBox already has play at once (**CACHED · TORBOX USENET**); others start when TorBox has fetched them, with its progress on screen.
  - Deepbrid plays at once (**DEEPBRID USENET**).
- **Deepbrid** joins the debrid services: torrent links are checked when played, and a link that isn't ready can be **left downloading on Deepbrid** to play later.
- **Built-in seek previews** (Settings → Playback, off by default): thumbnails while seeking for any link, without Seekr.
  - Made on the loading screen before the video starts, up to the share you choose ("12% · plays at 20%"); OK plays at once.
  - Made from a lighter copy of the same film when the list has one (much faster for 4K remuxes); black bars are trimmed.
  - The rest are made while you watch (not for 10-bit films without a lighter copy).
- **Auto subtitle sync:** subtitles Nabrio adds from OpenSubtitles or your add-ons are lined up with the video automatically. Moving the timing by hand turns it off for that video.
- **Spanish (Latin America)** as an audio language: Latino tracks and links first, then other Spanish.
- **The loading screen says what's happening:** under the pulsing logo, the current step (Usenet: getting the NZB, opening the release on your provider, checking it, downloading the start; debrid: connecting to the service) and a bar that fills as the video arrives. Stalls show *Buffering* with the same bar.

## Better

- **No more buffering on big debrid and direct files:** once playback reads on, the video downloads over four connections. A remux whose single connection was just slower than the film (stalling every few seconds) now plays smoothly.
- After repeated stalls the player waits for a bigger cushion (10, 20, then 30 s) instead of stopping again a few seconds later.
- Extra online subtitles no longer reload the video right after it starts when your language is already built in: they join when you open the subtitle menu.
- The player controls appear when the picture starts, not over the loading screen; Back while loading goes back to the links.
- New settings and messages are translated into Spanish, French, German, Italian, Portuguese and Dutch.

## Fixed

- WeTrakr and Simkl personal lists: the focused row was partly hidden at the bottom of the screen.
