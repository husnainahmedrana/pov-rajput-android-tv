# POV Rajput 0.3.32

A big Usenet update: **7z releases now play**, videos **start much faster**, and big files stream more smoothly.

## New

- **7z releases play over Usenet.** Indexers such as Miatrix post most releases as split 7z archives (`.7z.001`, `.7z.002` …). These now stream like any other link:
  - **Stored 7z** (the usual kind for video) plays straight away, and you can seek anywhere.
  - **Password-protected 7z** plays too, when the NZB carries the password. You don't type anything; the app reads it from the NZB.
  - **Compressed 7z** plays from a temporary copy on your device. It needs free space for the whole video, and seeking far ahead takes a while.
- **Pipeline** for each news provider (Settings → Usenet → your provider). It sets how many pieces each connection asks for at once. This helps a lot on slow or far-away connections: big files (19 GB tested) stream without buffering. **Default 2, up to 20.**
- **Warm connections** (Settings → Usenet → News providers). While the link list is open, the app signs in to your main provider ahead of time, so pressing Play skips the sign-in wait. It also keeps those connections open while a video is paused, so resuming starts at once. Off / 2 / 4, **default 2**.
- **Usenet Fallback** (Settings → Usenet → Indexers). When on, your indexers are searched only if your add-ons and debrid services find no links for a title. Off by default.
- **Turn all on / Turn all off** for source add-ons, instead of switching each one separately.

## Faster start for Usenet links

Usenet videos now start several times faster. On a 10 GB split 7z set the wait went from about **2 minutes to under 20 seconds**; a plain 4 GB MKV from about **27 to 13 seconds**.

- The pieces a video needs to open (its first and last pieces and each part's details) are asked for **all at once**, not one after another.
- The **check that a release is still on your provider** runs alongside the opening instead of before it.
- The video's own **seek index at the end of the file** (what the player reads before the first picture) is fetched straight away, ahead of everything else.
- Split sets open only one part of each size up front, not every part (3 instead of 29 on a 15 GB set).
- **Password-protected 7z:** working out the key and reading the archive's file list now take about a second (they took 10 seconds and more on a TV), and reading the video no longer slows to a crawl near the end of the file.

## Fixed

- **"Error, then it plays when I try again":** a slow moment on the network no longer ends the link. A piece that hasn't arrived after 12 seconds is asked for again on another connection; a short network drop keeps your connections and carries on where it was. A link is given up only for a real problem (missing pieces, an archive that can't play, a wrong login).
- **Choppy 4K Dolby Vision (profile 7) on TVs without Dolby Vision**, such as the onn 4K: the step that keeps the HDR10 picture was too slow for 4K frames. It now plays smoothly. This applies to every source, not just Usenet.
- **A piece that fails its checksum twice** now counts as broken, so your backup provider is asked for it instead of the damaged copy being played.
- **Releases the indexer marks as passworded** are no longer hidden: their NZB often carries the password, which the app now uses.

## Changed

- **News providers and indexers:** **OK turns one on or off**; **hold OK** for Edit, Test, Move and Remove (on the phone: tap, and press and hold). The list shows a reminder.
- **Source add-ons:** moving Up and Down now stays on the same button (On/Off to On/Off), so turning several off is quicker.
- **Add / edit news provider:** all fields line up on the TV, each with its name above it, and **Pipeline** is visible without scrolling. The **Test** result now stays in view just above the buttons, in the indexer form too. On the TV the on-screen keyboard slides over the form instead of squeezing it.
- **Easynews:** "NNTP streaming (experimental)" is now **Easynews NNTP streaming**.

## Good to know

- Usenet links count against your auto-play size limit by the indexer's listed size, which includes repair files; a link close to your limit may be skipped.
- Indexers post some releases with compression or settings the app can't stream yet. Those say why when you press Play and are greyed out as **UNAVAILABLE**.
