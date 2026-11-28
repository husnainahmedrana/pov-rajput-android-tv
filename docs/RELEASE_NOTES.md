# Nabrio 0.4.21

**Faster Usenet starts and seeks, a "You're watching" screen when you pause, and real rating logos.**

## New

- **"You're watching" when you pause.** About a second after you pause, the picture dims and Nabrio shows what's playing: the title's logo, the episode title (or a film's year and length), its synopsis, the cast with their photos, and the time. **OK** or **Play** resumes; any other key brings back the player controls. It shows once per pause.
- **Rating logos.** With an MDBList key (Settings → Home → MDBList), the ratings on title pages and on Home now show the IMDb, TMDB, Trakt, Letterboxd and Rotten Tomatoes logos instead of text labels.
  - Rotten Tomatoes shows **Certified Fresh**, **Fresh** or **Rotten**, and the audience score shows **Verified Hot**, **Hot** or **Stale**, following Rotten Tomatoes' own rules.
  - Scores out of 100 now show a **%**.

## Faster

- **Usenet videos start sooner.** On a 22 GB film over a 13 Mbit/s line, the start went from about 22 seconds to about 13.
  - The beginning of the video downloads at the same time as its seek index, instead of after it.
  - Playback starts with 3 seconds of video ready instead of 5.5. After a stall Nabrio still waits for more, so playback doesn't stutter.
  - Releases split into many RAR parts open without waiting for the last part.
  - 7z releases get the same head start as other videos.
- **Seeking is faster.** After you jump, playback resumes with 2 seconds of video ready, the first piece at the new spot is asked for on two connections at once, and connections that are nearly done aren't dropped.
- **Continue watching starts faster.** Resuming partway through no longer downloads the beginning of the film first.
- Built-in seek previews now start downloading only once the picture is showing, so they don't slow the start.

## Fixed

- **Rows scrolled sideways no longer cut off the first poster.** After scrolling a row to the right and back, the first poster sat half hidden on the left. It now lines up with the rows above again. Thanks to the Discord report.

## Tip

- **News providers:** if starts or seeks feel slow, run **Auto tune** for your provider. On most lines, pipeline 1 with a moderate number of connections is faster than more connections or a deeper pipeline.
