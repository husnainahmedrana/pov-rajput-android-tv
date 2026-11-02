# POV Rajput 0.3.14

## New

- **Skip buttons.** During an intro, recap or end credits, a glowing **Skip Intro** / **Skip Recap** / **Skip Credits** button appears just above the title row. With the controls hidden, press OK to skip; with them showing, press Up to reach it, then OK (tap it on the phone). Ignored, it tucks away after 12 seconds and comes back with the controls. Times come from TheIntroDB for shows and movies, and AniSkip for anime (opened from its anime page), matched to your copy's length; titles they don't know show no button. Settings → Playback → **Skip buttons**.
- **Next Episode.** Once an episode's end credits start (or in its last 40 seconds when the credits aren't known), the button becomes **Next Episode**: it finds the following episode, or the next season's first, and plays its best link.
- **Auto-play next episode.** Settings → Playback, off by default. When the credits start, Next Episode counts down from 10 with a thin line along the button and then plays the next episode (in the last 12 seconds when the credits aren't known). OK starts it at once, pausing holds the countdown, rewinding cancels it.

- **Settings in sections.** Settings now opens with a row of cards: Home, Playback, Subtitles, Sources, Tracking, Updates and About, each with a one-line summary. Pick one to see just that section. On the TV, move along the cards, or press Left/Right from any setting to go to the previous or next section; Down goes into the settings. The last section you used opens next time.

## Fixes

- **Settings stayed stale.** Switching a debrid service off still showed it as connected until Settings was reopened; Settings is now always up to date when you come back to it.
- **No more jumping to the top.** Switching a source provider (or a debrid service, or a Home row) on or off keeps you where you were.
- **Home rows end at See all.** Pressing Right on a row's last card no longer jumps to the next row.
- **"The link may have expired" on Easynews.** The real cause was the parallel downloader: when the player jumped to your resume spot it cut the video reader off mid-step, which crashed it. The downloader now rides out those jumps like a normal connection. Files that failed every time (such as a 10.5 GB 4K film) now play.
- **Recovers by itself.** A stream that stops for a source reason is retried quietly at the same spot (twice) before any message, and an Easynews video that keeps failing falls back to one connection.
- **Clearer error messages.** "Playback stopped" now says what actually happened: the server refused the link, it took too long, the connection dropped, the file couldn't be read, or the TV couldn't decode it.
- **Longer waits for slow links.** Debrid and Usenet-backed links get 30 seconds (was 8) to start sending, and Easynews 45 seconds.
