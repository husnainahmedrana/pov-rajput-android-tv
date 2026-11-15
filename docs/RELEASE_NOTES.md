# Nabrio 0.4.2

Usenet providers can now be **tuned automatically**, shows open on **the episode you're up to**, and the **"stream error" message no longer repeats**.

## New

- **Auto-tune for news providers** (Settings → Sources → Usenet, hold a provider → **Auto-tune…**):
  - Tick what to tune: **Port**, **Pipeline** and/or **Connections**. Settings you don't tick stay as they are.
  - **Port** tries 563 and 443 (SSL) and 119, 80 and 23 (without SSL), measuring speed and how fast the first article arrives.
  - **Pipeline** tries a spread of depths, then the ones next to the best, and re-tests the top two so one lucky reading can't win.
  - **Connections** tries more and more until your account's limit or until more stop helping, and picks the fewest that are as fast.
  - A live line shows what's being tested; the result is saved and shown with what it was before.
  - It downloads real articles from a video you played, so it uses data, and nothing should play meanwhile.
- **Allow ports without SSL** (hold a provider): off by default. Turning it on warns once that your login and downloads would travel unencrypted. With it off, auto-tune only ever saves an SSL port and just mentions a clearly faster plain one.
- **Shows open on your next episode:** a show you're part-way through opens on the episode after the last one you watched (on this device, or marked watched on Simkl or WeTrakr), in the right season, with that episode focused on TV — OK plays it.
- **Prowlarr and NZBHydra2 logos** on links and in the player.

## Better

- **Backups** now say what they cover: debrid services, add-ons, **Usenet providers and indexers, Easynews**, Home rows and watch progress, with every on/off switch and their order. Turning a debrid service or Easynews back **on** now restores on another device too, and the app language, screen fit and the release group remembered per show are backed up. Today's indexer search and grab counts stay on each device.
- **Usenet playback uses less memory** on TV boxes while it plays.

## Fixed

- **The "stream error" message popped up again and again** (about a dozen times) when a Usenet link failed. It now shows once, and a link already known not to play isn't retried.
- **Stopping a Usenet link while it was starting**, seeking, or switching links could mark the link as unplayable for the rest of the session.
- **The app could freeze** when going back to the link list or opening Streams while a Usenet link was starting.
- **A brief network drop or a slow indexer** could mark a Usenet link as unplayable until the app restarted, and a few unrelated timeouts during a long film could too.
- **Replaying quickly** could close the new stream by mistake.
- **One damaged article** could make the good ones downloading next to it fail.
- A temporary "too many connections" answer from the news server lowered the connection count for the rest of the video.
- Safer handling of damaged RAR archives, very large NZBs and oversized subtitle downloads.
- **The app could crash at start** on some boxes after a system update made saved accounts unreadable; it now starts and asks to link those accounts again.
- Other apps can no longer hide items from the TV's Continue Watching row.
