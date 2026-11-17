# Nabrio 0.4.5

**Torznab arrives:** your own **Jackett** and **Prowlarr**, or any Torznab address, now add torrents to the link list. **Real-Debrid follows your priority**, **Continue Watching updates** as soon as you close the player, and **several devices on one account** stay in step.

## New

- **Torznab** (Settings → Sources → **Torznab**): *Add Torznab indexer* offers three kinds.
  - **Jackett:** the address you open Jackett at and its API key. All its indexers are searched together.
  - **Prowlarr:** the address and its API key (Settings → General). Each switched-on **torrent** indexer is added; its Usenet indexers belong in Settings → Usenet.
  - **Torznab address:** any Torznab URL, such as a Comet or StremThru instance. A key in the address (`?apikey=…`) is picked up by itself.
  - Results are torrent links that play through your **debrid services**, checked and badged like Torrentio's. Connect a debrid service first.
  - Only releases named after this title are kept (aggregators match loosely, even by IMDb id), and for a show only this episode or a pack of its season.
  - **Priorities and backups** work as for Usenet indexers; **Results per indexer** (50 / 100 / 200 / 300) keeps the best-seeded links.
  - Links show the **Jackett** or **Prowlarr** logo, or **Comet** / **StremThru** for those instances, in the list and in the player.
  - A Torznab address pasted into **Source providers** is recognised and moved here.
  - The **Real-Debrid name filter** applies to these links too.
- **Several devices on one account:** when another TV or phone saved newer settings (add-ons, debrid services, Usenet providers and indexers with their auto-tune results, preferences), Nabrio offers to **Load** them (the app restarts) or **Keep this device's**. It checks when the app opens or comes back, never during playback. A device no longer overwrites a newer backup from another one: automatic backups wait, and **Back up now** asks.

## Better

- **Real-Debrid priority is respected:** with Real-Debrid above TorBox or Offcloud in Debrid services, a link they have cached gets **one try on Real-Debrid first**. If Real-Debrid doesn't have it, is pausing adds or fails, TorBox or Offcloud plays it straight away. Below them, nothing changes.
- **Continue Watching updates after playback:** closing the player now refreshes Home's Continue Watching, also when you played from a Continue Watching card (focus moves to the title you just watched) or from a details page (the row catches up when Home is back). WeTrakr and Simkl get a second refresh a few seconds later, as they record the stop late.
