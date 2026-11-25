# Nabrio 0.4.16

**Search Usenet by name for things no catalog lists, like sports events, see how often a Usenet release was grabbed, and external players no longer mark episodes watched when their buffer fills.**

## New

- **Usenet search by name:** the Search page has two chips next to the field, **Titles** (your catalogs, as before) and **Usenet**. Pick Usenet, type a name such as "ufc 310" and press OK: the name goes to all your indexers at once, plus Easynews when it's connected. It's made for things your catalogs don't list, like sports events, concerts and specials. On phones, the search screen has a Usenet button.
  - Filter by **All, Movies, TV or Sports**. All searches every category your indexer account allows.
  - The results use the usual link list, badges and sorting, and the same release from several indexers is shown once.
  - **More results** at the bottom fetches the next page, only from the indexers that had more, so a search costs one API hit per indexer until you ask for more.
  - These links have no catalog title, so nothing is sent to Trakt, Simkl or WeTrakr and they aren't kept in Continue Watching.
- **Grabs badge:** Usenet links from your indexers show how many times they were grabbed, such as **212 GRABS** or **1.2K GRABS**. More grabs usually means a complete release.
- **Sort by grabs:** Settings → Playback → Links & sorting has a new **Grabs** row (off by default). Switched on, the most grabbed Usenet links come first.

## Changed

- **Large posters are the default card shape** for anyone who hasn't picked one. If you chose a card shape yourself, it stays.
- **Loading card:** "Downloading the start of the video" now reads **"Getting ready to play"**.

## Fixed

- **External players (Vimu, VLC, MX Player, Just Player):** when the player's buffer filled up and it paused reading, Nabrio took that as "stopped". It marked the episode watched and, on Android 9 and older (Fire TV), jumped back to the front so the player seemed to close. Now Nabrio waits for the player to close and uses the position it reports. If no position comes back, the trackers get a pause, never a watched mark. Kodi works as before.
- **Loading bar:** the moving light now reaches the last frame before turning back, and the bar fills all the way while buffering.
- **TV search page:** the focused poster in the last row is no longer cut off at the bottom, and the fifth column no longer runs past the right edge.
