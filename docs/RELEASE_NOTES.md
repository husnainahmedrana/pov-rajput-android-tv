# Nabrio 0.4.1

Nabrio now speaks **seven languages**, Usenet indexers can be searched **in priority order** like NZBHydra2, and the phone player can **fill the screen**.

## New

- **App language** (Settings → Home & appearance → Language): **English, Español, Français, Deutsch, Italiano, Português and Nederlands**, or *Device language* to follow your TV or phone. Every menu, setting, player control and message is translated, and dates and language names follow your choice ("Telugu primero").
- **Screen size in the player:** a new **Screen** button next to Speed switches between **Fit** (the whole picture), **Fill** (no black bars, edges trimmed slightly) and **Stretch**. On the phone, **pinch out** to fill the screen and **pinch in** to fit again. Your choice is remembered.
- **Usenet indexer priorities** (Settings → Sources → Usenet, hold an indexer → Priority):
  - Indexers with the **same priority are searched together**; lower numbers go first.
  - The **next priority is searched only when the links so far fall short** — by default when there's no 1080p or better yet (also: no 4K, no 720p, or nothing found).
  - **Use only as backup (free tier):** searched only when nothing at all was found.
  - **Daily search limit** and **daily grab limit** per indexer: once reached, the indexer is skipped until tomorrow. Indexers that report their own allowance as used up ("API 25/25", "grabs 10/10") are skipped too. Resuming a video from your device never costs a grab.
  - The list shows one card per priority, saying when each one is searched, and each indexer shows today's searches and grabs.
  - With every indexer on the same priority (the default), searching works exactly as before.

## Better

- **TorBox sign-in** and the TV home screen's **Continue Watching** row now show the Nabrio name and logo (the row updates itself the first time it refreshes).
- **Discord release posts** carry the Nabrio logo.

## Fixed

- **Films offered for a show's episode.** For a show like *Dark*, some add-ons also returned a film with the same name ("Dark (2026) … [Telugu + Hindi]"), which could even sort to the top. Links with a year but no season or episode in their name are now hidden for episodes; season packs, dated daily shows and anime numbering are kept.
