# POV Rajput 0.3.7

## New

- **Top 10 that changes every day.** The Top 10 Movies and Top 10 Shows shelves now follow a live trending chart instead of a fixed all-time list. With your TMDB key they use TMDB's *Trending Today*; without one they use Queued Up's global trending list. The rotating feature at the top of Home follows the new Top 10.
- **Latest Releases rows.** *Latest Releases · Movies* and *Latest Releases · Shows* show the newest titles, from Queued Up. If Queued Up can't be reached, the rows quietly use TMDB's or Cinemeta's newest titles instead, and Home never waits on it.
- **No repeats on Home.** A title in a Top 10 no longer shows up again in the rows below it, and the old *Trending Today* rows are gone now that they are the Top 10.
- **Discover by genre.** The Movies and Shows pages now open on the **Newest** titles, with sort chips (Newest, Popular and Top rated with a TMDB key; Newest, Popular and Featured without) and a row of genres to filter by. Without a TMDB key, Newest comes from Queued Up's latest releases. Paging keeps your choice.
- **Playback speed.** A new **Speed** button in the player plays from 0.5× to 2×, with voices at their normal pitch. Dolby or DTS audio that normally goes straight to your TV or receiver is decoded by the app while sped up, and goes back to passthrough at 1×.

## Changes

- **One Audio & Subtitles menu.** Audio and Subtitles share one button and one menu with two columns, so either can be changed in one step. Volume boost has moved into the audio column, and Subtitle sync and Subtitle style stay under subtitles. The player's control bar has more room for the now-playing badges.
- **Update screen on phones.** Settings → Check for updates now fits phone screens: full-width notes and progress bar, stacked buttons, and nothing hidden under the status or gesture bar.

## Fix

- **Liked lists on Home.** A WeTrakr liked list that its owner made private or deleted no longer adds an "Account authorization is required or has expired" message to Home. That list is simply left out.

## Credits

Thanks to **[Queued Up](https://queued-up.app/)**, the free community Stremio catalog add-on behind the Latest Releases rows, the Top 10 and Newest without a TMDB key, and to **TMDB** and **Cinemeta** for the rest of the catalogs. This product uses the TMDB API but is not endorsed or certified by TMDB.
