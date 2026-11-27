# Nabrio 0.4.17

**Usenet links start much faster: a few seconds from pressing play to the picture.**

## Changed

- **Faster Usenet start:** several steps that used to run one after another now overlap or are gone.
  - Connections to your news provider sign in while the NZB is still downloading, so they are ready the moment the first article is wanted.
  - The availability check no longer holds up playback. It still runs alongside the start; if it finds a release taken down, the video stops with that reason and you can pick another link.
  - For MKV files, Nabrio now fetches exactly where the video's seek index is instead of the last 5 MB of the file. On slower lines that leaves the bandwidth for the start of the video.
  - The player reads that seek index faster, and its one-time setup (decoder lists, audio library) now happens while you're choosing a link instead of after you press play.
