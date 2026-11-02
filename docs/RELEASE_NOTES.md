# POV Rajput 0.3.10

## New

- **Streams in the player.** The player's **Browse** button is now **Streams**: the title's other links in a panel over the video, with the same badges as the link list. Pick one and it loads right there, continuing from the same spot. The player never closes. Packs with several files play the right one or let you choose. Started from Continue Watching? The links are searched quietly in the background, so the panel is usually ready when you open it. When a video fails, **Browse sources** opens the same panel.
- **Streams beside Play.** Movie, show and anime pages have a **Streams** button next to Play. It opens the full link list to pick by hand, and it never auto-plays, even with Auto-play on.
- **Hold OK, the same everywhere.** Holding OK on a poster (any row, Top 10, search, anime), an episode or a Continue Watching card opens one menu: **Play**, **Streams**, **More Info** / **Open Media**, and **Mark as watched** when a tracker is connected.
- **A new show page.** Episodes are a sideways row of wide cards with their stills. The header follows the focused episode: its number, title and synopsis, and **Play** and **Streams** play it. One press Down from the buttons lands on your next episode with the season tabs in view. Anime pages use the same row.
- **Hide uncached links.** A new switch in **Settings → Playback** keeps only links that play at once (cached or direct) in the link list and the player's Streams. Real-Debrid, AllDebrid and Debrid-Link can't tell in advance, so their links stay.
- **Mini player switch.** Turn the mini player off in **Settings → Playback** and leaving the player stops the video instead (progress saved, like Stop).

## Changes

- **Cleaner link rows.** Each link shows only its badges, now with a green **CACHED · service** / **DIRECT** or grey **NOT CACHED** / **CHECK ON PLAY** badge, the release name and the name you gave the source. The scrapers' extra lines (seeders, P2P, repeated sizes, emoji) are gone.
- **Seeking.** A seek lands as soon as you release the key, on the nearest keyframe in the direction you pressed, so video that is already loaded plays at once, even on 4K files with keyframes far apart. The preview stays on screen for a second and fades out.
- **Seek preview card.** It is the size of WuPlay's: a fixed height with the width of the film's own shape. Seekr's black bars are trimmed, so wide films get a wide, filled picture. The time sits in a pill on the seek bar.
- **Back in the player.** The first Back shows the controls, the second leaves.
- **Motion.**
  - The player's button highlight glides between controls.
  - One white outline glides along Home, catalog and Top 10 rows, and down the episode row.
  - The Home backdrop cross-fades between titles.
  - Menus and player panels open at full size with a quick fade.
- **Quicker first moves.** The app now ships a baseline profile, so Android compiles its code at install. Home also prepares the first backdrops while you look, so the first move after opening the app no longer stutters.

## Fixes

- Player panels no longer open small and then grow.
- A just-opened hold menu shows its first option highlighted.
- The first and last episode cards are no longer cut off at the screen edges.
