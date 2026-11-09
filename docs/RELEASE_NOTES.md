# POV Rajput 0.3.26

## New

- **Add an add-on from your phone.** In Settings → Sources → Add source provider, press **Use phone** on the TV and scan the QR code. Paste the add-on's install link on your phone and it arrives on the TV filled in, with the add-on's own name. The link is encrypted on the phone for that TV only; the relay server can't read it.
- **Add-on links open POV.** On the phone, tap a `stremio://` install link on an add-on's setup page and pick POV Rajput, or share an `https://` add-on link to POV. Add source provider opens filled in; nothing is added until you press Save.
- **Debrid logos on add-on links.** Add-ons set up with your own debrid key (Torrentio, Comet, MediaFusion, StremThru, Debridio…) name the service in each link, like `[TB+]` or `[RD download]`. POV now reads that tag: the link shows the service's logo and "Cached" or "Not cached" instead of "Direct", the player shows the logo, and links the add-on still has to download are skipped by auto-play and hidden by Hide uncached links.
- **Scraper logos.** Links from Debridio, Torrentio, Comet, Meteor and StremThru show the scraper's logo next to the debrid service's, both when the add-on holds your debrid key and when POV's own debrid plays its torrents. The player shows them side by side too, as logos only.

## Fixed

- **Auto-play next episode** searched the next episode's links but didn't start one. It now plays the best link as soon as the search ends.
- Torrentio links with settings (`providers=…|sort=…`) were refused when saving a source provider. Characters like `|` are now stored percent-encoded, which the add-on reads the same way.

## Improved

- The logos at the top of the link list leave out debrid services with no links (for example when every link came ready-made from add-ons with their own key). Real-Debrid-style services show "?" only when there are torrents to check.
- Scraper logos stay on their links and no longer take space at the top of the link list.
