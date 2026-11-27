# Nabrio 0.4.19

**New: your settings sync between devices by themselves, and a link that fails moves on to the next one by itself.** Sign in to the same Nabrio account on your TV and phone with Automatic backup on: change something on one, and the other has it the next time you open the app.

## New

- **Automatic settings sync** (with your Nabrio account and Automatic backup on):
  - A setting you change is backed up a few seconds later, while the app is still open, not only when you leave it.
  - When the app starts, settings another device changed are loaded before the first screen, with no restart. The check never holds the start up for more than a moment, and an offline start goes on with this device's settings.
  - When you come back to an app that's already open, newer settings load by themselves (the app reopens quickly) if you haven't pressed anything yet. Otherwise they wait for the next time you open it, so nothing changes while you're using it.
  - The last change wins: if both devices changed something, the one changed most recently is kept, with no question to answer.
  - With Automatic backup off, Nabrio still asks whether to load another device's newer settings, as before.
- **Automatic fallback** (Settings → Playback, on by default; Feature #11): when a link fails, the next link in your list plays by itself, from the same spot, in your own sort order. It works for Usenet and debrid links alike, for network and server errors as well as Usenet releases that are gone. "Stream unavailable · trying the next link" says which one plays. Failed links are skipped until you close the app, and after five links in a row Nabrio stops and lets you choose. Turn it off to get the Retry and Browse sources choices for every failure instead.
- **Start timeout** (Settings → Playback, Off by default): with automatic fallback on, a link that shows no picture within 5, 10, 15 or 30 seconds is skipped for the next one.
