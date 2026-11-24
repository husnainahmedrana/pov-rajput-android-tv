# Nabrio 0.4.14

**A new Expanded focus look for Home, a new search page, all your ratings in one line, and Home uses far less memory, so playback no longer closes on 2 GB boxes.**

## New

- **Expanded focus (the new default card shape):** Home rows show posters, and the title you're on opens up into a wide card with its backdrop and logo. Moving along a row, the wide card stays on the left and the next posters slide into it. A single press widens the card smoothly; holding ◀/▶ keeps the row flowing. Top 10 rows do the same, with the rank number beside the wide card. You can still pick Wide backdrops or Small, Medium or Large posters in Settings → Home → Card shape.
- **Endless rows:** at the end of a row (after See all) the row carries on from its first title, so you can keep scrolling without jumping back.
- **One row at a time:** with poster cards, Home shows only the row you're on, placed near the bottom of the screen whatever the poster size, so the next row never peeks in.
- **New search page (TV):** a mic button and a wide search field along the top, your recent searches as chips, a list of categories on the left (Popular Movies, Popular Shows and every genre) and a poster grid on the right. Moving through the list shows each category. Typing shows results straight away and suggests completions as chips; the keyboard's Search opens the full results page.
- **Ratings on Home:** the title at the top of Home shows its IMDb rating (the live IMDb score). Add your own free MDBList key in Settings → Home → **MDBList** and it also shows TMDB, Trakt, Letterboxd and Rotten Tomatoes (critics 🍅 and audience 🍿) ratings, each with its own badge. Ratings are asked for once the screen settles on a title and kept for a few hours, well within MDBList's free daily limit.

## Fixed

- **Playback closing on its own:** on TV boxes with 2 GB of memory (like the onn 4K), a Usenet video could close back to Home after a minute or two with no error, because Home kept every poster of every row in memory (up to about 780 MB). Now only the posters on or near the screen are kept, each at the size of its card, and while a video plays only what was on screen stays. Home now uses about 50-70 MB. This may also stop external players from closing when their buffer fills.
- **Scrolling stays quick:** posters you scroll back to come from memory or the app's image cache on the device (now 150 MB), not the internet, and posters ahead are loaded before they slide on screen.
- **Smoother rows:** rows slide sideways with one short, even motion, and holding ◀/▶ glides instead of jerking.
- **Focus outline:** the white outline around the focused card is no longer cut off at the top, bottom or left side of a row.
- **Rows above the one you're on** now disappear at once when you move down, instead of sliding up over the artwork first.
- **Wide backdrop cards** keep the Home layout they had before: the next rows stay in view below the one you're on.
