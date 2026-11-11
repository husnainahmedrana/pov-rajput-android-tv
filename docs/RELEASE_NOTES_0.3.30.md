# POV Rajput 0.3.30

## New

- **Jump buttons in the player.** Up to four buttons after Next episode take you straight to a point in the video. They are spread over each video's own length: a 56-minute episode gets 10m · 20m · 35m · 45m, a 2½-hour movie 30m · 1h · 1h 30m · 2h. TV only.
- **Back to your links when a video ends.** Three seconds after a movie ends, the player closes and you are back on the link list. Episodes do the same when auto-play next episode is off; with it on, the next episode plays as before.
- **Usenet links in the player** show a turquoise **USENET** badge with the other badges, and your news provider's and the indexer's logos Resumed videos (Continue Watching) keep their logos too, for add-on links as well.
- **A quick check before a Usenet link plays.** Your news providers are asked whether a spread of the release's articles still exist (about a second). A release that has been taken down now fails at once, with the reason, instead of stopping partway through the movie.
- **Videos split into numbered parts** (`.001`, `.002` …) now play over Usenet.

## Changed

- **Seeking moves 10 seconds a press** on the seek bar (it was 1 second). Holding the key still speeds up, to 20 and then 30 seconds a step.
- **Usenet links that can't play say why, every time:** for example "This release is a 7z archive, which can't be streamed yet". A link that failed is greyed out as **UNAVAILABLE** in the list as soon as you are back, and auto-play skips it.
- **Tweaknews:** the preset now uses `newshosting.tweaknews.eu`, the server most Tweaknews accounts sign in to. The old address is still there as "Tweaknews (older accounts)". If your Tweaknews login was refused, edit its server to the new address.

## Coming next

- **7z support for Usenet streaming.** Some indexers (Miatrix, for example) post most releases as split 7z archives. Those can't play yet; support for them is planned for the next build.
