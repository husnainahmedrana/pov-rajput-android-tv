# Cast and discovery

- Large portrait cast cards on TV and mobile (default), with actor and character names, or the earlier round cards (Settings → Home & appearance → Cast cards). Actor-page navigation remains available.
- Tappable Genres using the existing discover catalogs; no additional key is required for Cinemeta genres.
- With the viewer's TMDB key: Keywords open a filtered discover grid; the Similar titles button opens a related-title grid. Details keep the existing IMDb rating without a separate TMDB rating badge. An About section closes the page with director or creators, dates, runtime, studio or network, language and budget/box office when TMDB has them.
- The Manage button groups watchlist, watched and supported playlist actions in a submenu, retaining confirmation before removals and marking watched.
- Similar Titles leads with up to five relevant releases from the last five years, then retains strong older recommendations. TMDB recommendations are complemented by a recent pool filtered by the selected title's genres and keywords; Similar results remain the fallback. Upcoming, duplicate and adult titles are omitted.
- **More titles** appends further TMDB recommendation and similar-result pages without removing the grid already loaded. Duplicates are excluded, failed requests can be retried, and the button ends when all available pages have been read.
- TMDB detail extras are cached in memory for six hours (40 titles), scoped to the configured key. Errors do not prevent playback or the remaining metadata from loading.
- Keywords and Similar Titles support both movies and TV shows. Related titles without an IMDb mapping or poster are omitted. Keyword results retain the media type and page through the TMDB discover API.

Provider documentation:

- https://developer.themoviedb.org/reference/movie-keywords
- https://developer.themoviedb.org/reference/tv-series-keywords
- https://developer.themoviedb.org/reference/movie-similar
- https://developer.themoviedb.org/reference/discover-movie

