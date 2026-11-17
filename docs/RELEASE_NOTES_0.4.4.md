# Nabrio 0.4.4

**Real-Debrid works much better:** links it refuses are hidden, packs and its short "pause" are handled, and your own **MediaFlow Proxy** can make every debrid service see your server's IP. Skip times now also come from **SkipDB**, and the player's buttons always fit on screen.

## New

- **MediaFlow Proxy** (Settings → Sources → **MediaFlow Proxy**): add your own [MediaFlow Proxy](https://github.com/mhdzumair/mediaflow-proxy) server (address + API password, with **Test connection**), switch it on, then choose **which debrid services and which add-ons** use it.
  - Videos from the ticked services and add-ons stream through your server.
  - The ticked debrid services' **API calls go through it too**, so the service only ever sees your server's IP (Real-Debrid ties links to one IP).
  - **Usenet never uses it** (in-app Usenet, Easynews and Usenet links from add-ons stay direct), nor do links on your own network.
  - A **MediaFlow logo** shows next to the debrid logo on links that will go through it, and in the player while one does.
- **SkipDB** skip times: shows and movies now ask **TheIntroDB and SkipDB** together. Each skip (intro, recap, credits) comes from TheIntroDB when it has one, otherwise from SkipDB, so more titles get a Skip button. Anime still uses AniSkip first. SkipDB is credited in **About** (data under ODbL 1.0).

## Better

- **Real-Debrid refuses some release names** (its copyright filter, since 2026): `WEB-DL`, `WEBRip`, `BDRip`, `HDRip`, `DVDRip`, `BluRay.x264`, `HDTV.x264` and a few more.
  - With **only Real-Debrid** connected, links it would refuse are **hidden** from the results page, auto-play and the player's Streams panel. **Show N links Real-Debrid refuses** below the list brings them back.
  - The check also reads the **torrent's own name**, not just the file's: an episode inside a season pack named `…[2022 WEB-DL 1080p]…` is refused by Real-Debrid even when the file name looks fine. Works with Torrentio, Meteor, Comet, TorrentsDB, MediaFusion and StremThru.
  - A refused link you still pick says so clearly instead of "HTTP 451".
- **Real-Debrid's short pause on adds:** after several new torrents in a row Real-Debrid refuses every add for a while. Nabrio now notices, stops trying more links, and shows **"Real-Debrid is pausing adds · retrying in 30 s"**, then tries the same link again (30 s, 90 s, 3 min; Back cancels).
- **Fewer tries on Real-Debrid:** check-on-play tries **3 links** on Real-Debrid instead of 7, so the pause is reached less often. AllDebrid and Debrid-Link keep 7.
- **Season packs on Real-Debrid:** every video in the pack is selected, then the chosen episode plays, so cached packs are ready at once.
- **Instead of "?":** Real-Debrid, AllDebrid and Debrid-Link show **~N** (how many links they would try) on the results page, and their logo shows **faded** on those links ("checked when you play").
- **"Playing the next link: …"** briefly says so when the link you picked was skipped and a later one plays.
- The player shows the **add-on's logo** (Torrentio, Meteor…) for Real-Debrid, AllDebrid and Debrid-Link links too, as it already did for TorBox and Offcloud.
- **Player buttons fit on screen** (TV): Audio & Subtitles, Speed, Screen, Streams, Info and Episodes show their icon, and the focused one also its name. The Episodes button no longer runs off the edge. Phones keep the labels.
