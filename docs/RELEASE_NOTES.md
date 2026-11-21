# Nabrio 0.4.8

**Trakt**, **several trackers at once**, **Movies and Shows like Home**, **a clear LAT badge** and **fewer pop-ups**.

## New

- **Trakt** joins WeTrakr and Simkl (Settings → Tracking → Service): Continue Watching, Up Next, watchlists, personal and liked lists, watched badges and scrobbling. Sign in with a code at **auth.trakt.tv/activate**.
  - While Trakt reviews Nabrio for everyone, Trakt sign-in is open to a limited number of accounts.
- **One main tracker, the others keep your history too:** the service you choose supplies Continue Watching, Up Next and watchlists. Sign in to the other two as well (Settings → Tracking) and they also get your plays and watched marks, each with its own **Send watch history to …** switch.
- **Liked lists on Home from every connected service** (WeTrakr and Trakt; Simkl has no list API).
- **Service logos** in Settings → Tracking and in the service chooser.
- **Movies and Shows tabs like Home:** the focused poster fills the feature at the top with its synopsis, backdrop and trailer, and rows rise beneath it. Sort, genre and search are in the feature's **filter button** ("Newest · All genres ▾").
- **Connections per video** (Settings → Playback, this device only): 4 (default), 2 or 1 for debrid and direct videos.

## Better

- **WeTrakr uses far fewer requests:** watchlists and lists are kept for 15 minutes, the same read is never sent twice at once, closing the player refreshes Continue Watching once instead of four times, a rebuffer no longer sends another "start", and watched badges are checked every 10 minutes. When WeTrakr's daily limit is reached, its page shows one message with the time it resets instead of an error on every row.
- **Latin American Spanish shows as 🇲🇽 LAT**, Spain's Spanish as 🇪🇸 SPA. A Latino link no longer also shows a Spain badge.
- **Fewer pop-ups during Usenet playback:** no more "Streaming from … · connections · pipeline" messages (the loading screen shows each step). An error appears only when the video really can't play, on the player's error screen. An indexer that doesn't answer the first time is asked once more before the link counts as failed.
- **Debrid and direct videos fall back to one connection** by themselves if several connections stop delivering.

## Fixed

- Phones and tablets: switching a setting (such as built-in seek previews) no longer jumps Settings back to the top.
- The "load backup settings / keep this device" choice fits on smaller screens.
- Phones and tablets: **Play now** and **Leave** can be tapped on the seek-previews loading screen.
