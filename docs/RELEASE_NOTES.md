# Nabrio 0.4.10

**Clearer WeTrakr sign-in limit, and it no longer pauses syncing.**

## Fixed

- **WeTrakr:** WeTrakr allows only a few sign-in attempts every 15 minutes. Going over that used to show "WeTrakr asked us to wait" and also paused all WeTrakr syncing (Continue Watching, watchlists, scrobbling) for 15 minutes, even if you were already connected. Now only signing in waits, and the message tells you when you can try again: "Too many WeTrakr sign-in attempts. Try again at 2:10 AM."
- If Settings → Tracking → Account already says **Connected**, you don't need to sign in again. Selecting Account starts a new sign-in.
