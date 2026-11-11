# Usenet streaming in POV Rajput: status and plan

Written 2026-11-10 as a handover, updated at the 0.3.29 release. It describes what exists, what was verified, and what to build next, in priority
order. Nothing here needs the earlier conversation.

## What this feature is

The app searches the viewer's own Usenet indexers, and plays the result by fetching articles straight from the viewer's
own news providers over NNTP. Nothing is downloaded first and no server sits in between: the engine runs inside the app
on the TV or phone. Seeking works because any position in the video maps to a known article.

It exists beside, not instead of, the older Easynews path (HTTPS range requests to the Easynews members website), which
is unchanged.

## Status

| Part | State |
|---|---|
| NNTP engine (connections, yEnc, pipelining, read-ahead, seeking) | Working on the TCL TV |
| Plain video files | Working |
| RAR sets, stored (not compressed), RAR4 and RAR5 | Working on the TV with real Easynews sets (RAR4). RAR5 is covered by unit tests only |
| News providers (presets + custom, main and backup) | Working; the viewer confirmed playback |
| Indexers (presets + generic Newznab, incl. home NZBHydra2 / Prowlarr) | Working with Usenet Crawler. Other presets are untested |
| Indexer headers with daily and manual update | Built; unit-tested; the live GitHub lookup was not exercised on the TV |
| NZB kept on the device so resuming costs no grab | Built (14 days, 40 files) |
| Easynews web links played over NNTP (experimental switch) | Working |

**Released in 0.3.29** (2026-11-11), marked experimental. All five release checks passed for that release. The phone
build compiles and passes lint but its Usenet screens have not been tried on a phone.

## How it fits together

```
Link search (data/SourceResolver.search)
  ├─ Stremio add-ons            (unchanged)
  ├─ Easynews web search        (unchanged)
  └─ one search per indexer     data/UsenetSearch → data/Newznab
        result = SourceItem with directUrl "<nzb link>#pov-nzb"

Play (EasynewsRoutingDataSource asks UsenetPlayback for every address)
  └─ "#pov-nzb" link
        1. NZB from the device cache, else from the indexer   (the only moment a grab is spent)
        2. usenet/Nzb.parse
        3. ProviderChain( NntpPool per provider, main ones first, then backups )
        4. usenet/UsenetMedia.open → plain file, or VolumeSet over RAR volumes
        5. UsenetDataSource reads that ByteSource for Media3
```

### Files

Engine, pure Kotlin with no Android imports (`app/src/main/java/dev/pov/tv/usenet/`):

- `Nzb.kt`: reads an NZB by walking the text in place. Do not replace this with regular expressions or an XML parser:
  a big video's NZB is about 10 MB and regex matching crashed the TV (native out-of-memory in `Matcher.setInput`).
- `NntpConnection.kt`: one TLS or plain connection; sign-in; `BODY`; yEnc decoding and CRC check inline; `Nntp.check`
  for the Test buttons. Server certificates are checked against the host name.
- `NntpPool.kt`: `ArticleSource` interface; `NntpPool` (one worker thread per connection, sliding-window pipelining,
  urgent queue, connections the server refuses are dropped from the limit, idle connections close after 45 s);
  `ProviderChain` (providers in order; the next is asked when one fails; `rescued` counts backup saves).
- `UsenetStream.kt`: `ByteSource` interface; one posted file as random-access bytes. Position to article is a division
  by the part size, corrected from each article's own `=ypart begin`. Read-ahead window is capped at 32 MB.
- `Rar.kt`: `Rar.entries` (RAR4 and RAR5 headers) and `VolumeSet` (a stored file read across volumes).
- `UsenetMedia.kt`: picks the video in an NZB and opens it.

App side:

- `data/UsenetSettings.kt`: `NewsProviders`, `NewznabIndexers`, `UsenetHeaders`, with their preset lists.
- `data/Newznab.kt`: search, capabilities, result parsing, `UsenetLinks` (the `#pov-nzb` mark).
- `data/UsenetSearch.kt`: turns enabled indexers into searches; starts the daily header refresh.
- `data/EasynewsNzb.kt`: gets an NZB for an Easynews web search result.
- `UsenetDataSource.kt`: `UsenetSessions`, `UsenetPlayback`, `EasynewsNntp` (incl. the speed test), `UsenetDataSource`.
- `MainActivity.kt`: `usenetPage()` and the dialogs after it; three NNTP rows in `easynewsActions()`.
- Small edits in `EasynewsDataSource.kt`, `InAppPlaybackSession.kt`, `data/EasynewsApi.kt`, `data/SourceResolver.kt`.

Tests: `UsenetStreamTest` (15, a fake news server on localhost that builds real yEnc articles and RAR4/RAR5 volumes) and
`UsenetIndexerTest` (7, Robolectric because the settings use `org.json`).

### Stored settings (all in SecureStore)

`usenet.providers`, `usenet.indexers` (JSON lists), `usenet.usage.<indexer id>`, `usenet.header.query`,
`usenet.header.grab`, `usenet.header.checked`, plus `easynews.nntp` and `easynews.nntp.connections` for the Easynews
experiment. NZB files are cached in `cacheDir/nzb/<sha1 of link>.nzb.gz`.

## Things verified against real services

- **Easynews NZB endpoint:** `POST https://members.easynews.com/2.0/api/dl-nzb`, Basic auth, form fields `autoNZB=1`
  and a key `0&sig=<sig>` with value `<hash>|<base64 name, no padding>:<base64 ext, no padding>`. Without the `sig`
  from the search answer it returns an empty NZB. The `sig` is kept in memory only, so an Easynews link opened after an
  app restart uses the web path.
- **Easynews NNTP:** `news.easynews.com:563` (also `news.eu`, `news.us`) takes the web login and supports pipelining.
- **Easynews sets:** RAR4, stored, volumes listed in order (`.rar`, `.r00`, …), all the same size, subjects scrambled.
  In one sample search 13 of 14 large results were multi-part sets, so RAR support is not optional.
- **Usenet Crawler:** capabilities list `imdbid` for movies and shows; the usage element is
  `<newznab:apilimits apicurrent=… apimax=… grabcurrent=… grabmax=…/>`; links look like
  `…/getnzb/<guid>.nzb&i=<user>&r=<key>`.
- **Speed on the viewer's TV:** throughput stopped growing at about 7 Mbps because the home line syncs at about
  13 Mbps. Pipelining made no difference there. Opening connections on the TCL is slow (about 0.5 to 1 s each).
  The CPU cost at high bitrates has **not** been measured properly: do that before claiming 4K remux works.

## Design rules worth keeping

- Grabs are precious. Never fetch an NZB while listing results; only on play, and reuse the cached copy.
- Only two extra articles are fetched to map a whole RAR set (volume 1 and volume 2). Middle volumes are assumed
  identical to volume 2 and every article read from them must report that same size, or the read fails. Do not go back
  to fetching every volume's first article: on a slow line that is tens of megabytes before playback starts.
- Scrambled subjects: trust the NZB's order only after volume 1 proves it is the start and volume 2 proves it follows.
  Otherwise fall back to reading every file's name from its first article.
- A refusal must tell the viewer why, in plain words, and the link must not be retried in a loop.
- Logins and API keys never go into logs, links shown to the viewer, or this repository.
- Keep `usenet/` free of Android imports so the unit tests can run it.

## Before each release

1. Run all five checks the release workflow runs: `testTvDebugUnitTest lintTvDebug lintTvRelease lintMobileDebug
   lintMobileRelease`.
2. Search the tree for API keys, logins and private addresses before committing. None belong in this repository.
3. Commits and pull requests in this repository carry no assistant attribution lines.

## What to build next, in order

### 1. Finish what a release needs

- **Account backup:** the Usenet settings are not in the encrypted account backup. Find how SecureStore items such as
  the source-provider list are backed up (`data/BackupSnapshot.kt` lists preference files only) and add the three
  lists and two headers the same way.
- **Phone build:** build and try the mobile flavour; check the two dialogs fit a phone screen.
- **Merge duplicates:** the same release from two indexers shows twice. Merge on normalised title plus size within
  about 1%, keep the others as fallbacks, and try the next one when a grab or the first articles fail.
- **Release notes and the About page:** credit the Newznab/NZBHydra2/Prowlarr naming where the presets came from.

### 2. NZB cache (viewer asked for this; mostly done)

Already built: the NZB is saved on first play and reused for 14 days, so resuming does not grab again. Still to do:

- Show it: a small "saved" mark on a link whose NZB is already on the device.
- Tie the cached copy's lifetime to Continue Watching, so it lasts as long as the resume entry does, not a fixed 14
  days.
- Keep the Easynews `sig` (or the Easynews NZB itself) the same way, so Easynews-over-NNTP resumes after a restart.
- A "Clear saved article lists" row in Settings.

### 3. Self-hosted NZBHydra2 and Prowlarr (viewer asked for this; works today, needs a proper flow)

It works now through the two "your server" presets or Generic Newznab, including plain `http://` on the home network.
What is missing is a flow that does not make the viewer edit an example address:

- A dedicated "NZBHydra2" and "Prowlarr" entry that asks for host, port and API key separately and builds the address.
- **Prowlarr:** one address per indexer (`/<id>/api`). Offer to list the indexers from Prowlarr's own API
  (`/api/v1/indexer`, header `X-Api-Key`) and add the chosen ones in one go.
- **NZBHydra2:** one address covers every indexer. Its results name the real indexer in a
  `newznab:attr name="hydraIndexerName"` attribute: show that as the indexer on the link.
- Aggregators are slower: give them the per-source time limit the add-ons already have
  (`SourceSearchPreferences.ownLimit`; indexer ids are `indexer:<id>`), and list them on the Max search time page.
- A remote server needs HTTPS; plain HTTP should be accepted only for private addresses, with a short note saying why.

### 4. Health before play

- A quick check when a link is focused or about to auto-play: `STAT` the first, last and a few random articles
  (pipelined, about a second) and show an "available" mark. `NntpConnection` has no `STAT` yet; add it beside `BODY`.
- After play starts, check the rest in the background and switch to the next link early if a hole is ahead.
- A missing article mid-play currently ends the stream with a reason. Better: ask the backups (already done), then
  fill that one article with zeros and keep going, giving up only after several holes.

### 5. Compressed sets (viewer asked; do last, and only if the viewer's usage grows)

Stored sets can be read at any position. A compressed file cannot: it must be decompressed from its start, so a seek
means decompressing everything before the target. A workable but limited version:

- Decompress forward only, on the device, into a rolling window. Play from the start works; seeking forward means
  waiting while the gap is fetched and decompressed; seeking back needs a re-start or a disk cache of what was
  decompressed.
- RAR5 and RAR4 use different compression; there is no decoder in the app today. A pure-Java unrar exists (junrar,
  RAR4 only); RAR5 would need native code, which this project does not build yet.
- Most video sets are stored, because video does not compress. Measure how many refused links are "compressed"
  before investing here; password-protected and incomplete sets are separate problems this would not fix.

### 6. Smaller items

- Several main providers are asked in order. Using their connections at the same time would raise speed on fast lines.
- Season packs: pick the episode's file inside a pack (the file chooser for torrents, `EpisodeFilename`, is the model).
- `.001 .002` split files and 7z stored archives.
- "Use phone" for typing API keys: needs a new mode on the phone relay page (`server/backup`, `LINK_PAGE`).
- The auto-play rule builder (planned separately) should treat Usenet links like any other.
- The speed test lives under Easynews and only tests Easynews; move a general one to each provider's Test.
- Verify the remaining presets as viewers report them; the Tabula Rasa address in particular is a guess.

## How to build and try it

- Unit tests: `gradle testTvDebugUnitTest --tests "dev.pov.tv.Usenet*"`.
- Preview on a TV, beside the released app:
  `gradle assembleTvRelease -PpovAppIdSuffix=.preview -PpovPreviewRelease=true`, then
  `adb install -r app/build/outputs/apk/tv/release/app-tv-release.apk`.
- Log tag: `PovUsenet` (`adb logcat -s PovUsenet`). It prints what was opened, which provider refused, and the speed
  test lines.
- In the app: Settings → Sources → Usenet. Add a provider, add an indexer, search a title, play a "Usenet" link.
