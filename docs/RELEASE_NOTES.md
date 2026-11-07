# POV Rajput 0.3.22

## Fixed

Playback now recovers on its own from more device problems, on TVs and phones, continuing from the same spot instead of stopping:

- **Audio decoder fails.** If the device's own audio decoder can't start or stops working, POV switches to its built-in decoder.
- **Audio drops out mid-video.** If the audio output fails while playing, for example when a sound bar is switched off or the HDMI audio changes, POV opens the audio again.
- **Video decoder fails mid-video.** If the video decoder stops working partway through, POV restarts the picture on the next available decoder.
