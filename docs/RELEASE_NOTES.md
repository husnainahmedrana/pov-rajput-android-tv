# POV Rajput 0.3.9

## New

- **Seek previews from Seekr.** While you seek, a thumbnail of the scene appears above the seek bar and follows the spot you're seeking to, with the time in a small pill under it. Previews come from **[Seekr](https://seekr.tv)**, which has them ready for many movies and shows, so nothing extra is downloaded from the video. They work with the remote's Left/Right on TV, and with the seek bar or a swipe on the phone. Pictures come every 10 seconds, and the closest one is shown.
  - Get your own free key at **seekr.tv/join** (the QR code in Settings takes you there) and paste it in **Settings → Playback → Seekr API key**. The app checks the key with Seekr when you save it.
  - Turn previews on or off with **Seek previews (Seekr)** in the same place. Titles Seekr doesn't have just show the time.
  - Seekr is run by an independent developer. If it works well for you, consider supporting it at **buymeacoffee.com/seekrtvdev** (also a QR code in Settings).

## Changes

- **Calmer hold-to-seek.** Holding Left/Right now climbs gently: one-second steps for the first 10 seconds, then 5, 10 and 20 seconds, and never more than 30 seconds a step. It used to jump up to 2 minutes at a time and race past scenes.
- **Seeking looks the same everywhere.** Without a preview picture, the time shows in the same small pill above the seek bar instead of a large box in the middle of the screen.

## Fixes

- **Settings stays where you were.** Connecting Offcloud, signing in to TorBox, or saving any key in a dialog no longer jumps back to the top of Settings.

## Credits

Seek previews: **[Seekr](https://seekr.tv)** with your own key, through its open-source Android library ([seekr-android-sdk](https://github.com/AKhalil609/seekr-android-sdk), Apache 2.0). Support Seekr at [buymeacoffee.com/seekrtvdev](https://buymeacoffee.com/seekrtvdev).
