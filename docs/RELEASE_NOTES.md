# Nabrio 0.4.12

**Poster services and poster cards, an episode calendar, search history, cast without a TMDB key, Easynews as a Usenet indexer, and Trakt watched marks that show up straight away.**

## New

- **Poster services:** Settings → Home → **Poster service** shows every poster from RPDB, TOP Posters, AIOratings, OpenPosterDB, BetterPosters or your own Custom URL (use `{imdb_id}` where the IMDb id goes). RPDB works without a key of your own (it uses RPDB's free key), and BetterPosters needs no key at all. If a service has no poster for a title, the usual one shows.
- **Card shape:** Settings → Home → **Card shape** switches rows from wide backdrop cards to tall poster cards in three sizes: Small, Medium or Large. Poster services need poster cards to show on every row, so the app offers the switch when you pick one. Continue Watching keeps its wide cards, and Top 10 follows the poster size.
- **Poster quality:** Settings → Home → **Poster quality**: Fast (smaller pictures, quickest on a slow connection), Standard or Sharp.
- **Calendar:** your tracker page has an **Airing soon** row for the next 7 days and a **Calendar** with every new episode of your shows over the next 30 days, by day. It covers shows you're watching, follow, have on your watchlist or have watched, with WeTrakr, Trakt or Simkl.
- **Search history:** the Search screen lists your recent searches. Tap one to search again, hold one to remove it, or use **Clear all**. While you type, it suggests your past searches and matching titles. Your history stays on this device only.
- **Cast without a TMDB key:** shows get their full cast with photos and character names from TVmaze. Films get their cast from Wikidata, with photos from Wikimedia Commons and the leads first. Tap a person to search for their name. With a TMDB key, cast works as before.
- **Easynews as a Usenet indexer:** Settings → Sources → Usenet → Add indexer → **Easynews (search only)**. Easynews finds the releases and your own news providers download them, like the Easynews-as-indexer bridges. It has its own Easynews login, separate from the Easynews section.
- **Home rows from more catalog add-ons:**
  - Catalogs that need a genre or year can now be Home rows: pick the values you want, and each one becomes its own row (for example "Top · Movies · Action").
  - Add-ons that list titles by TMDB or Kitsu id now fill their rows.
  - A row with nothing playable now says so instead of disappearing.
- **About:** credits TVmaze, Wikidata and the poster services.

## Fixed

- **Trakt watched marks:** episodes and films you finish in the player now get their ✓ as soon as you're back, and season marks catch up a few seconds later. Before, it could take up to 10 minutes, so it looked as if nothing had been marked.
- **Back keeps your place:** going back from a title returns to the same row and poster on Home, on both TV and phone, instead of jumping to the top.
- **Phone player:** the Back gesture first hides the playback controls; a second Back leaves the player.
- **Faster opening on phones:** with poster cards, a title's backdrop starts loading as soon as your finger touches its poster.
