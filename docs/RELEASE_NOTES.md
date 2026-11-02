# POV Rajput 0.3.12

## New

- **Easynews.** Settings → Sources → **Easynews**: sign in with your Easynews username and password, and Usenet results join the link list beside your add-ons' links. They play directly, and the player shows the Easynews logo while one streams. Your login stays encrypted on the device and is only ever sent to easynews.com. Experimental: it uses the Easynews members website, so it may break if Easynews changes the site. Adapted from the upstream POV Android TV fork.
- **Easynews download server.** Choose Auto, Amsterdam, Virginia or Los Angeles if another server streams faster for you. (Easynews ignores the download port, so the server is the setting that matters.)
- **Debrid services page.** Settings → Sources → **Debrid services** lists every service with ▲ ▼ to set which plays first, an **On/Off** switch, and **Manage** or **Connect**. Settings is much shorter as a result.
- **Switch debrid services off without disconnecting.** Off keeps the key saved but skips the service for cache checks and playback. Turn it back on any time.
- **Source providers page.** Settings → Sources → **Source providers** works the same way: reorder, switch on or off, edit or remove each add-on, and add new ones.
- **Switch tracking off without signing out.** Settings → Tracking → **Use WeTrakr / Use Simkl**. Off hides its Home rows, watched badges and lists and sends no playback updates. You stay signed in.

## Fixes

- **Phone Streams panel.** Quality badges (4K, size, HDR and so on) were huge in the landscape player on phones. They're now the normal size.
