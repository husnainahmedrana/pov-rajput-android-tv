# POV Rajput 0.3.15

## New

- **POV account and backup.** Create a free account with your email and a password, and your whole setup is backed up: debrid services (with their on/off switches and play order), stream and catalog add-ons, TMDB, Easynews, Seekr and Wyzie keys, WeTrakr and Simkl logins, Home rows, playback and subtitle preferences, and your local watch progress. Sign in on any TV or phone, or after reinstalling, and it all comes back. The app offers it once when it starts, and it's always in **Settings → Account**.
- **Encrypted on your device.** Debrid keys, add-ons and logins are locked with your password before they leave the TV or phone. The backup server can't open them, and neither can the developer. The server is open source in `server/backup`, and you can run your own copy (**Settings → Account → Backup server**).
- **Recovery key.** You get one when you sign up (with a QR code to keep it on your phone). Forgot your password? Reset it with a code sent to your email. With the recovery key everything comes back; without it your settings come back and you link your debrid services and add-ons again.
- **Automatic backup.** Whenever you leave the app and something changed, it's backed up. **Back up now**, **Change password**, **Sign out** and **Delete account** are in Settings → Account.
- **Use phone.** On a TV, sign in or sign up by scanning a QR code and typing your email and password on your phone. They are encrypted on the phone for that TV only.
- **New Settings tab bar.** The section cards are now a single bar along the top (Account, Home, Playback, Subtitles, Sources, Tracking, Updates, About), with the same gliding highlight as the player's buttons.
- **Report a bug and request a feature.** Settings → About now links to **bug.rajput.cc** and **feature.rajput.cc** (a QR code on the TV). No account needed; your email is optional, only if you'd like a reply. A bug report fills in the app version and device, and you can see or clear that before sending.
- **Website.** Settings → About → **pov.rajput.cc**.

## Fixes

- **Continue Watching without a tracker.** Without WeTrakr or Simkl, what you watched only appeared on Home after restarting the app. It now shows as soon as you come back from the player.
