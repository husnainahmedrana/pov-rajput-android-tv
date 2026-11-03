# POV account backup server

The server behind **Settings → Account** in POV Rajput: sign up with email and password, and the app keeps an
encrypted backup of your setup that you can bring back on any TV or phone. It is a single Cloudflare Worker with a
D1 database, and it runs on Cloudflare's free plan.

## What the server can and cannot read

| Stored | Who can open it |
|---|---|
| **Secrets**: debrid services, add-on addresses, TMDB / Easynews / Seekr / Wyzie keys, WeTrakr and Simkl logins | Only you, with your password or recovery key. Encrypted on the device ([`BackupCrypto.kt`](../../app/src/main/java/dev/pov/tv/data/BackupCrypto.kt)) with a random data key the server never receives. |
| **Settings**: Home rows, appearance, playback, subtitles, audio, source sorting, skip buttons, local watch progress | Encrypted with a settings key the server keeps, so a password reset by email alone can bring your settings back. |
| Your email address | Kept to send sign-up and password-reset codes. |
| Your password, recovery key | Never sent. The app derives a separate login token from the password (PBKDF2-HMAC-SHA256, 210,000 rounds, salted with your email); the server keeps only a SHA-256 of that token. |

**Forgot your password?** Reset it with a code sent to your email. With your recovery key everything comes back;
without it your settings come back, and debrid services and add-ons have to be linked again, because nobody (not
the server, not the developer) can open them.

**Phone sign-in on a TV.** The TV shows a QR code whose address carries the TV's public key after the `#`, which
browsers never send to a server. The phone page encrypts your email and password to that key (ECDH P-256,
HKDF-SHA256, AES-GCM) and the server only passes the sealed text along.

Other protection: a lockout after 5 wrong passwords (15 minutes), at most 5 tries per email code, per-IP rate
limits, 256 KB per layer, and accounts unused for 180 days are deleted.

## Run your own

You need a Cloudflare account with a domain on it, and a [Resend](https://resend.com) account with that domain
verified for sending (the free tier is enough).

```bash
cd server/backup
cp wrangler.example.jsonc wrangler.jsonc         # wrangler.jsonc is git-ignored
npx wrangler login
npx wrangler d1 create pov-backup                # put the database_id into wrangler.jsonc
npx wrangler d1 execute pov-backup --remote --file schema.sql
npx wrangler secret put RESEND_API_KEY
# set routes[0].pattern and vars.MAIL_FROM in wrangler.jsonc to your own domain, then:
npx wrangler deploy
```

Then in the app open **Settings → Account → Backup server** and enter your address, or build the app with
`-PpovBackupServer=https://backup.example.com`.

## API

All `POST` bodies are JSON; errors are `{"error": "..."}` with a message meant for people.

| Call | Body | Notes |
|---|---|---|
| `POST /v1/signup/code` | `email` | Emails a 6-digit code. |
| `POST /v1/signup` | `email, code, auth, wrapped_pw, wrapped_rk, settings_key, settings, secrets` | Creates the account. |
| `POST /v1/login` | `email, auth` | Returns the locked data key and both layers. |
| `POST /v1/backup` | `email, auth, settings, secrets` | Replaces both layers. |
| `POST /v1/password` | `email, auth, new_auth, wrapped_pw` | |
| `POST /v1/reset/code` | `email` | Emails a reset code. |
| `POST /v1/reset/verify` | `email, code` | Returns a reset token and what the device needs to rebuild the account. |
| `POST /v1/reset` | `email, reset_token, auth, wrapped_pw, wrapped_rk, secrets` | |
| `POST /v1/delete` | `email, auth` | Removes the account and its backup. |
| `POST /v1/link`, `GET/POST /v1/link/{id}`, `GET /l/{id}` | | Phone sign-in relay and its page. |
