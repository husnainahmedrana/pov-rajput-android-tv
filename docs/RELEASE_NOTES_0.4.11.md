# Nabrio 0.4.11

**Rank links by where they come from, put your Prefer switches in your own order, and in-app updates work on Fire TV.**

## New

- **Source sorting:** Settings → Links & sorting → Link sorting has a new **Source** row (off by default). Press **Edit** to put TorBox, Offcloud, Real-Debrid, AllDebrid, Premiumize, Debrid-Link, Deepbrid, Easynews, Usenet and other add-ons in the order you want, e.g. TorBox, then Easynews, then Offcloud. Torrents that aren't cached anywhere always come last. Move the Source row above Quality if it should win over quality too.
- **Prefer order:** Settings → Links & sorting → Link filters → **Prefer order** lets you rank the Prefer switches that are on, e.g. HDR and Atmos above HEVC.

## Fixed

- **Fire TV updates:** on Fire TV Cube 3 and other Fire OS 7 devices, in-app updates failed with "The update signing certificate does not match this app." **Fire TV users: install this version by hand once** (download it with the Downloader app and install it over the top; your settings stay). In-app updates work after that.
- **Prefer switches:** a link now counts every Prefer switch it matches, the top one weighing most. Before, only the first match counted, so an HEVC-only link could beat an HDR + Atmos one. The default order is now Dolby Vision, HDR, Atmos, Lossless audio, Remux, WEB-DL, HEVC, 10-bit, Proper.
- **News provider logos:** Tweaknews bought through a Newshosting deal now shows the Tweaknews logo, and the Newshosting logo is a small icon like the others, so link badges no longer squeeze into a tall column on phones.
- **Phone dialogs:** Link sorting no longer runs off the bottom of the screen, stacked dialog buttons have space between them, and long translated button labels fit inside their buttons.
