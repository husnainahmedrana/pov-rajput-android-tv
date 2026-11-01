# POV Rajput 0.3.8

## New

- **Catalog add-ons on Home.** Add your own Stremio catalog add-ons, such as AIOMetadata, in **Settings → Home & appearance → Home rows → Add catalog add-on**: paste the add-on or manifest URL and pick the catalogs you want. Each one becomes a Home row that plays, tracks and shows watched ✓ badges like any other. Add-on addresses are kept encrypted on the device, and a slow or failing add-on only affects its own row.
- **Arrange Home your way.** The same **Home rows** page lists every row after Top 10, built-in and add-on alike. Move rows up or down, switch any of them off, or reset to the default order. Continue Watching and Top 10 stay at the top. Titles in a Top 10 still don't repeat in the rows below, add-on rows included.
- **"See all" keeps going.** A row's *See all* page now shows its whole catalog instead of stopping at the titles on Home: **More titles** at the bottom adds the next page. This works for add-on rows and the built-in Latest, Popular and Featured rows.
- **Find missing subtitles.** When a video has no subtitles in your language, the best matches from **OpenSubtitles** are added and the first one is turned on; the others are in the player's subtitle list. Matches that share the file's release name come first, as they usually fit its timing best. With English fallback on, English is looked for if your language has none. Turn it off in **Settings → Subtitles & audio → Find missing subtitles**. You can also add your own free **Wyzie Subs** key there for more sources.

## Changes

- **Lighter artwork from add-ons.** Add-on rows often link full-size images. The app now asks TMDB for the size a card needs (780 px wide cards, 500 px posters) and Metahub for its small size, so a typical backdrop drops from about 1 MB to under 100 KB. Images from hosts with only one size, such as Fanart.tv or rating posters, load as before. *Tip for AIOMetadata users: choose The Movie Database (TMDB) as the Poster and Background art source for the fastest rows.*

## Fixes

- **Playback no longer stops after switching apps.** Some phones freeze apps in the background and cut their internet, so a movie could end on "Playback stopped" (ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) when you came back. Connection errors while POV is in the background, or just after you return, are now retried quietly from the same spot. Another app playing sound while you're away no longer stops the movie resuming when you come back. This applies to the TV app too.

## Credits

Subtitles found online come from **[OpenSubtitles](https://www.opensubtitles.org/)** through its free Stremio add-on, and optionally **[Wyzie Subs](https://sub.wyzie.io/)** with your own key.
