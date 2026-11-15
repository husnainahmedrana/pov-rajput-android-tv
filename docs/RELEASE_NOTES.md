# Nabrio 0.4.3

**Auto-tune** for news providers gives steady, repeatable results now, even on an unsteady connection, and Usenet links can be sorted by **NZB age**, newest first.

## New

- **NZB age** (Settings → Sources → Link sorting → **NZB age**): switch it to *Newest first* and, within a quality, links from your Usenet indexers are listed by how recently they were posted. Newer NZBs are less likely to be missing articles. Move the row up or down like the others; it's off by default. Links without a date (debrid, torrents) come after the dated ones. Each of these Usenet links also shows its **age** as a badge next to its size ("Today", "12d", "5mo", "2y").

## Better

- **Auto-tune measures properly on any line** (Settings → Sources → Usenet, hold a provider → **Auto-tune…**):
  - Each step downloads for **10 to 25 seconds** and at least 50 MB, so a slow line still gets a fair reading.
  - **Pipeline is tested with 4 connections**, where its effect shows (with all of them your line is usually full already, so every pipeline looks the same). A deeper pipeline has to be **at least 10% faster than pipeline 1** to be chosen; otherwise pipelining stays off. Connections are then tested on your real line.
  - **No single reading decides anything:** the top two settings in each round are measured twice, a reading far below its neighbours is measured again, and the final choice needs two good readings.
  - **If your connection was unsteady** during the test (the same setting measured very differently), nothing is saved until you choose **Save anyway** or **Keep current**.
  - "Too slow right now" is no longer reported as "articles missing", and one slow step no longer ends the connections test. The window says why the connections test stopped.
  - **Share** (phone): sends the full results — every step with its speed and first-article time, why rounds stopped, and what was saved.
  - The window always fits the screen with **Share** and **Close** visible, and keeps the newest result in view. The Easynews NNTP speed test window too.
