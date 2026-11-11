# POV Rajput 0.3.29

## New

- **Usenet streaming, built in (experimental).** Add your own news provider and indexers under **Settings → Sources → Usenet**, and their results appear in the link list like any other link. The app streams straight from your news provider: nothing is downloaded first, no server sits in between, and seeking works. You need your own news provider account and your own indexer account.
- **News providers.** Pick yours from a list of 28 well-known providers (Newshosting, Easynews, Tweaknews, Eweka, UsenetServer, Giganews, Frugal Usenet and more) or enter any server by hand, with its port, SSL on or off, and how many connections to use. **Test** signs in before you save. Mark a provider **Backup only** and it is asked just for the articles your main provider is missing.
- **Indexers.** Pick from 23 known Newznab indexers and enter your API key, or enter any Newznab address. A self-hosted **NZBHydra2** or **Prowlarr** works too, including a plain `http://` address on your home network. Each indexer shows today's API and download use when it reports them.
- **Sets in RAR volumes play too.** Most Usenet releases are split into RAR parts. When the video is stored in them without compression (nearly always), the app reads it straight through the parts. Compressed or password-protected sets are refused with the reason.
- **An indexer download is spent only when you play.** Browsing the list costs nothing, and the article list is kept on the device for 14 days, so resuming a video doesn't fetch it again.
- **Indexer headers.** Indexers often answer only clients they know. The app names a well-known search tool and download client, keeps both at their current versions by itself about once a day, and has an **Update now** row. You can type your own.
- **Usenet links have their own look.** A turquoise **USENET** badge, then your news provider's and the indexer's logos when the app has them.
- **Easynews over its news servers (experimental).** Easynews → **NNTP streaming** plays Easynews search results from the Easynews news servers instead of its website, with up to 60 connections. Links it can't play that way fall back to the website as before. **NNTP speed test** shows the speed at several connection counts.

## Changed

- **Search goal** says more plainly what each choice costs: "Time limit ends" is the default and finds the most links; the goals that stop early can miss links in your preferred audio language.

## Good to know

- Usenet streaming is new and marked experimental. It has been used on one Android TV with Easynews, Newshosting and one indexer; the phone app has the same screens but they have not been tried there yet.
- Not supported yet: compressed sets, season packs, sets split as `.001 .002` files, and 7z. The same release found on two indexers shows as two links.
- Usenet settings are not included in the account backup yet. After a restore, add your providers and indexers again.
- Speed depends on your own internet line and on your provider. More connections help only up to what your line can carry.
