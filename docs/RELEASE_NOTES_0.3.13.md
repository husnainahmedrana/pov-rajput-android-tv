# POV Rajput 0.3.13

## New

- **Faster Easynews streaming.** Easynews limits the speed of each download connection, so the player now fetches Easynews videos over **8 parallel connections** by default and plays the pieces in order. In a test on the same 4K file, one connection managed about 2.8 Mbps, four about 9 Mbps and eight about 11 Mbps. Change it in Settings → Sources → Easynews → **Connections** (1–16; 1 is the old single connection). If a connection drops, that piece resumes where it stopped, and your login is still only sent to easynews.com.

## Fixes

- **Easynews results match the title.** Searching for a movie such as *Verity* used to list any file with that word in its name. Movies are now searched with their release year and kept only when the file name starts with the title followed by a year within one of the release; episodes need the show name and the right SxxEyy.
- **Easynews results sort properly.** Easynews reports each file's audio and subtitle languages and picture size; these now feed your Sort setting, so Easynews links rank by language and quality alongside add-on links instead of falling behind them. Scope 4K films (3840 × 1600) count as 4K.
- **Subtitle languages in release names.** "Sub Rus Ukr Eng", "Sub (Rus Eng Ukr)" and "sub ita.eng" (common in Torrentio and Comet results) now count every listed language as a subtitle, not English audio, and "ESub" means English subtitles. Meteor's 🌐 / 💬 lines work as before.
