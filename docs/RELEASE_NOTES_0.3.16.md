# POV Rajput 0.3.16

## New

- **Redesigned link list.** The title's artwork now fills the background behind the links (the backdrop on TV, the poster on phones), and the list opens with the same rise-in as a title's details page.
- **Link counts at the top.** The top-right corner shows each of your services' logos with how many links are cached there, plus Easynews, PenguPlay and CNCVerse when they return links, and the total. Real-Debrid, AllDebrid and Debrid-Link show **?** because they can only check a link when it plays.
- **Logos on every link.** The "Cached · …" badge is replaced by the logos of the services that have the link cached, on the right of each card. Easynews, PenguPlay and CNCVerse links show their own logo.
- **PenguPlay and CNCVerse in the player.** While one of their links plays, the player's badge shows the add-on's logo and name, just as it does for debrid services. They're recognised by their address, so it works whatever you named the add-on.
- **Cleaner PenguPlay and CNCVerse links.** Their cards show just the release name, without the server names, sizes and extra text those add-ons attach to it, and the line underneath shows the site and server the link comes from (e.g. *4KHDHub · R2*, *VegaMovies · V-Cloud FSL Server*).
- **Player Streams panel matches.** Links picked from the player's Streams button use the same see-through cards and service logos as the link list.
- **Hide cam & low-quality releases** (Settings → Playback, on by default). Leaves out theatre recordings and early copies: CAM, HDCAM, TS/telesync, TC/telecine, screeners, workprints and HDRip.
- **Cached links first.** With uncached links shown, they now come after all the cached ones, in the same order.

## Fixes

- **Audio language before subtitles.** Torrentio, AIOStreams, Comet and StremThru often list every language in a file, subtitles included, without saying which is which. A long list like that was read as many dubs, so a link with only Spanish subtitles could rank above one with Spanish audio. Now the first language counts as the audio and the rest as subtitles; Meteor's and StremThru's labelled audio and subtitle lines are read as they are.
- **Update notice buttons on phones.** "View update" and "Later" were stacked with no gap between them. They now have room, on the phone and on TV.
